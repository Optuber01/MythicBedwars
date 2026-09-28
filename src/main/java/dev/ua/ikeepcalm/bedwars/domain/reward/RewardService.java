package dev.ua.ikeepcalm.bedwars.domain.reward;

import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.bedwars.audit.RewardAuditFormat;
import dev.ua.ikeepcalm.bedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.bedwars.domain.reward.MatchContributionTracker.Contribution;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardTier;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventReservation;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works out who earned what at the end of an event match, and queues it for collection.
 *
 * <p>Runs on the Bedwars server at round end — the one moment when the match result, the
 * contribution counters, and the players' sandbox pathways are all still available. Everything it
 * produces is intent (percentages), never resolved amounts; the survival server sizes them against
 * the player's real progression.
 */
public class RewardService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static String nameOf(UUID playerId) {
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        return name == null ? playerId.toString() : name;
    }

    private final MythicBedwars plugin;
    private final RewardConfig config;
    private final RewardRoller roller;
    private final RewardQueue queue;
    private final MatchContributionTracker tracker = new MatchContributionTracker();

    public RewardService(MythicBedwars plugin, RewardConfig config, RewardQueue queue) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
        this.roller = new RewardRoller(config);
    }

    public MatchContributionTracker tracker() {
        return tracker;
    }

    /**
     * Rolls and queues rewards for everybody who took part.
     *
     * @param quitWinners players who disconnected but were on the winning side; they still earn,
     *                    which is the whole reason the quit lists are consumed
     */
    public void payOut(EventReservation reservation, Arena arena,
                       List<Player> winners, List<Player> losers,
                       List<UUID> quitWinners, List<UUID> quitLosers, boolean tie) {
        if (!config.isEnabled()) {
            return;
        }

        int matchSize = winners.size() + losers.size() + quitWinners.size() + quitLosers.size();
        if (matchSize < config.minPlayers()) {
            plugin.log("Event {} had only {} player(s); no rewards paid.", reservation.eventId(), matchSize);
            // Tell them, rather than letting the announcement's promise of rewards quietly evaporate.
            java.util.function.Consumer<Player> notify = player -> player.sendMessage(
                    plugin.getLocaleManager().formatMessage(player,
                            "magic.rewards.denied_small_match", "needed", config.minPlayers()));
            winners.forEach(notify);
            losers.forEach(notify);
            auditSmallMatch(reservation, arena, winners, losers, quitWinners, quitLosers, matchSize);
            return;
        }

        Map.Entry<UUID, Double> mvp = tracker.mvp(arena.getName(), config, this::qualifies);

        for (Player player : winners) {
            award(reservation, arena, player.getUniqueId(), player.getName(), true, tie, mvp);
        }
        for (Player player : losers) {
            award(reservation, arena, player.getUniqueId(), player.getName(), false, tie, mvp);
        }
        for (UUID playerId : quitWinners) {
            award(reservation, arena, playerId, nameOf(playerId), true, tie, mvp);
        }
        for (UUID playerId : quitLosers) {
            award(reservation, arena, playerId, nameOf(playerId), false, tie, mvp);
        }
    }

    private void award(EventReservation reservation, Arena arena, UUID playerId, String name,
                       boolean won, boolean tie, Map.Entry<UUID, Double> mvp) {
        Contribution contribution = tracker.of(arena.getName(), playerId);

        Denial denial = denialReason(contribution);
        String arenaName = arena.getName();
        boolean isMvp = mvp != null && mvp.getKey().equals(playerId);
        // Captured on the main thread: the tracker is not read again after the async hop.
        EmitContext context = new EmitContext(reservation.eventId(), arenaName, playerId, name, won, tie,
                isMvp, participationRatio(contribution), contribution.kills(), contribution.finalKills(),
                contribution.bedsBroken(), contribution.actions());
        if (denial != null) {
            plugin.log("No rewards for {}: {}", name, denial.log);
            notifyDenied(playerId, denial);
            auditEmit(context, AuditOutcome.DENIED, denial.name().toLowerCase(java.util.Locale.ROOT), null);
            return;
        }

        double ratio = participationScale(contribution);
        String pathway = eventPathway(playerId);
        String today = LocalDate.now(ZoneOffset.UTC).format(DAY);

        // The daily count is a Redis read, so it has to leave the round-end tick. Rolling then
        // happens back on the main thread, where the config tree cannot be swapped underneath it.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            long paidToday = queue.bundlesToday(playerId, today);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (paidToday >= config.dailyDropThreshold()) {
                    plugin.log("No rewards for {}: {} bundles already today.", name, paidToday);
                    notifyDenied(playerId, Denial.DAILY_CAP);
                    auditEmit(context, AuditOutcome.DENIED, "daily_cap",
                            row -> row.put("paid_today", paidToday));
                    return;
                }

                // Past the soft threshold the tiers that carry the real value are withheld, so a
                // dedicated farmer tapers off instead of hitting a wall.
                boolean participationOnly = paidToday >= config.dailyDowngradeThreshold();

                List<RewardGrant> grants = new ArrayList<>();
                // Participation scales with how much of the match they were actually present for; a
                // win does not, because a carried teammate still genuinely won.
                grants.addAll(roller.roll(RewardTier.PARTICIPATION, ratio));
                if (won && !participationOnly) {
                    grants.addAll(roller.roll(RewardTier.WINNER, 1.0));
                }
                if (isMvp && !participationOnly) {
                    grants.addAll(roller.roll(RewardTier.MVP, 1.0));
                }

                if (grants.isEmpty()) {
                    auditEmit(context, AuditOutcome.DENIED, "no_grants_rolled",
                            row -> row.put("paid_today", paidToday)
                                    .put("participation_only", participationOnly));
                    return;
                }

                RewardBundle bundle = new RewardBundle(
                        RewardBundle.SCHEMA,
                        reservation.eventId(),
                        arenaName,
                        playerId,
                        name,
                        pathway,
                        won && !tie,
                        System.currentTimeMillis(),
                        List.copyOf(grants));

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    RewardQueue.EmitResult emitted = queue.emit(bundle);
                    if (emitted != RewardQueue.EmitResult.QUEUED) {
                        boolean duplicate = emitted == RewardQueue.EmitResult.DUPLICATE;
                        auditEmit(context, duplicate ? AuditOutcome.DENIED : AuditOutcome.FAILED,
                                duplicate ? "already_emitted" : "redis_unavailable",
                                row -> RewardAuditFormat.describe(row, bundle)
                                        .put("paid_today", paidToday)
                                        .put("participation_only", participationOnly));
                        return;
                    }
                    // Only count it once it is genuinely owed, so a duplicate round-end cannot
                    // inflate somebody towards the cap on rewards they were never paid.
                    queue.recordBundleToday(playerId, today);
                    auditEmit(context, AuditOutcome.COMMITTED, null,
                            row -> RewardAuditFormat.describe(row, bundle)
                                    .put("paid_today", paidToday)
                                    .put("participation_only", participationOnly)
                                    .put("participation_scale", ratio));
                    Bukkit.getScheduler().runTask(plugin, () -> preview(playerId, bundle));
                });
            });
        });
    }

    /**
     * Plain values describing one player's result, captured on the main thread so an audit row can
     * be written from any thread.
     */
    private record EmitContext(String eventId, String arena, UUID playerId, String playerName,
                               boolean won, boolean tie, boolean mvp, double participationRatio,
                               int kills, int finalKills, int bedsBroken, int actions) {
    }

    /**
     * Writes a {@code reward.bundle_emitted} row. Touches no Bukkit state, so it is safe on the
     * async emit thread.
     */
    private void auditEmit(EmitContext context, AuditOutcome outcome, String reason,
                           java.util.function.Consumer<BedwarsAuditEmitter.AuditRow> extra) {
        try {
            emitRow(context, outcome, reason, extra);
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort and must never affect a payout.
        }
    }

    private void emitRow(EmitContext context, AuditOutcome outcome, String reason,
                         java.util.function.Consumer<BedwarsAuditEmitter.AuditRow> extra) {
        BedwarsAuditEmitter.AuditRow row = BedwarsAuditEmitter.AuditRow.of("reward.bundle_emitted", outcome)
                .correlation(BedwarsAuditEmitter.bundleCorrelation(context.eventId(), context.playerId()))
                .business(context.eventId())
                .subject(context.playerId())
                .reason(reason)
                .put("actor", "system")
                .put("event_id", context.eventId())
                .put("player_name", context.playerName())
                .put("arena", context.arena())
                .put("won", context.won())
                .put("tie", context.tie())
                .put("mvp", context.mvp())
                .put("participation_ratio", context.participationRatio())
                .put("kills", context.kills())
                .put("final_kills", context.finalKills())
                .put("beds_broken", context.bedsBroken())
                .put("actions", context.actions());
        if (extra != null) {
            extra.accept(row);
        }
        plugin.getAudit().emit(row);
    }

    private void auditSmallMatch(EventReservation reservation, Arena arena, List<Player> winners,
                                 List<Player> losers, List<UUID> quitWinners, List<UUID> quitLosers,
                                 int matchSize) {
        try {
            emitSmallMatch(reservation, arena, winners, losers, quitWinners, quitLosers, matchSize);
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort.
        }
    }

    private void emitSmallMatch(EventReservation reservation, Arena arena, List<Player> winners,
                                List<Player> losers, List<UUID> quitWinners, List<UUID> quitLosers,
                                int matchSize) {
        List<UUID> everyone = new ArrayList<>();
        winners.forEach(player -> everyone.add(player.getUniqueId()));
        losers.forEach(player -> everyone.add(player.getUniqueId()));
        everyone.addAll(quitWinners);
        everyone.addAll(quitLosers);
        for (UUID playerId : everyone) {
            plugin.getAudit().emit(BedwarsAuditEmitter.AuditRow.of("reward.bundle_emitted", AuditOutcome.DENIED)
                    .correlation(BedwarsAuditEmitter.bundleCorrelation(reservation.eventId(), playerId))
                    .business(reservation.eventId())
                    .subject(playerId)
                    .reason("small_match")
                    .put("actor", "system")
                    .put("event_id", reservation.eventId())
                    .put("arena", arena.getName())
                    .put("match_size", matchSize)
                    .put("min_players", config.minPlayers()));
        }
    }

    /**
     * @return why this player earns nothing, or {@code null} if they qualify
     */
    private Denial denialReason(Contribution contribution) {
        if (config.denyRageQuit() && contribution.isForfeit()) {
            return Denial.RAGE_QUIT;
        }

        if (contribution.millisSinceJoin() < config.minPlayTimeSeconds() * 1000L) {
            return Denial.TOO_SHORT;
        }

        if (contribution.actions() < config.minActions()) {
            return Denial.INACTIVE;
        }

        if (participationRatio(contribution) < config.minParticipationRatio()) {
            return Denial.AFK;
        }

        return null;
    }

    /**
     * @return whether this player could receive a reward at all, used to keep the MVP title from
     * landing on somebody who is about to be denied and so awarding it to nobody
     */
    private boolean qualifies(Contribution contribution) {
        return denialReason(contribution) == null;
    }

    /**
     * @return the share of the time they were in play that they looked like they were playing it
     */
    private double participationRatio(Contribution contribution) {
        int sampled = contribution.sampledSeconds();
        if (sampled <= 0) {
            // Never sampled at all: eliminated inside the first second, or the sampler was not
            // running because magic was off. Absence of evidence is not evidence of being away, and
            // min-play-time and min-actions still stand between this and a free reward.
            return 1.0;
        }
        return Math.min(1.0, (double) contribution.activeSeconds() / sampled);
    }

    /**
     * @return what the participation grant is multiplied by. Floored, because an eligible player who
     * spent the match holding a base has earned a smaller reward, not a token one.
     */
    private double participationScale(Contribution contribution) {
        return Math.max(config.participationScaleFloor(), participationRatio(contribution));
    }

    /**
     * Tells the player what is waiting for them, while they are still here to read it.
     */
    private void preview(UUID playerId, RewardBundle bundle) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }

        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.rewards.earned_header"));
        for (RewardGrant grant : bundle.grants()) {
            Component line = describe(player, grant);
            if (line != null) {
                player.sendMessage(line);
            }
        }
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.rewards.earned_footer"));

        announceEpics(player, bundle);
    }

    /**
     * A headline prize is worth more than a line in a list: the winner gets a title and a sound, and
     * everybody online is told what they missed. Wanting the thing is the point — which only works if
     * other people see it happen.
     */
    private void announceEpics(Player player, RewardBundle bundle) {
        List<RewardGrant> epics = bundle.grants().stream().filter(RewardGrant::epic).toList();
        if (epics.isEmpty()) {
            return;
        }

        for (RewardGrant grant : epics) {
            String itemName = itemName(player, grant);

            player.showTitle(net.kyori.adventure.title.Title.title(
                    plugin.getLocaleManager().formatMessage(player, "magic.rewards.epic_title"),
                    plugin.getLocaleManager().formatMessage(player, "magic.rewards.epic_subtitle", "item", itemName),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(300),
                            java.time.Duration.ofSeconds(2),
                            java.time.Duration.ofMillis(600))));
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);

            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other.equals(player)) {
                    continue;
                }
                // Resolved per recipient: the item name itself is translated.
                other.sendMessage(plugin.getLocaleManager().formatMessage(other,
                        "magic.rewards.epic_broadcast",
                        "player", player.getName(), "item", itemName(other, grant)));
            }
        }
    }

    private Component describe(Player viewer, RewardGrant grant) {
        return switch (grant.kind()) {
            case ACTING_PERCENT -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.acting_percent", "percent", RewardRedeemer.percent(grant.amount()));
            case COOLDOWN_CREDIT -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.cooldown_credit", "percent", RewardRedeemer.percent(grant.amount()));
            case ACTING_SPEED -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.acting_speed",
                    "percent", Math.round(grant.amount() * 100),
                    "duration", RewardRedeemer.formatDuration(grant.intArg()));
            case ACTING_ITEM_MULT -> plugin.getLocaleManager().formatMessage(viewer,
                    "magic.rewards.line.acting_item_mult",
                    "multiplier", RewardRedeemer.multiplier(grant.amount()),
                    "duration", RewardRedeemer.formatDuration(grant.intArg()));
            case ITEM -> grant.item() == null ? null : plugin.getLocaleManager().formatMessage(viewer,
                    grant.epic() ? "magic.rewards.line.epic_item" : "magic.rewards.line.item",
                    "amount", grant.count(), "item", itemName(viewer, grant));
        };
    }

    /**
     * Names an item for a player, spelling out an exchange token's power ceiling — a token is only
     * exciting if you can tell at a glance whether it reaches anything you want.
     */
    private String itemName(Player viewer, RewardGrant grant) {
        if (grant.item() == null) {
            return "?";
        }

        String base = plugin.getLocaleManager().getMessage(viewer,
                "magic.rewards.item_name." + grant.item().name().toLowerCase(java.util.Locale.ROOT));

        if (!grant.isExchangeToken()) {
            return base;
        }

        return plugin.getLocaleManager().getMessage(viewer, "magic.rewards.token_suffix")
                .replace("{item}", base)
                .replace("{sequence}", String.valueOf(grant.maxSequence()));
    }

    private void notifyDenied(UUID playerId, Denial denial) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendMessage(plugin.getLocaleManager().formatMessage(player, denial.messageKey));
    }

    /**
     * Why a player earns nothing. An enum rather than a string so renaming the player-facing wording
     * cannot silently detach a reason from the message that explains it.
     */
    private enum Denial {
        RAGE_QUIT("rage quit", "magic.rewards.denied_short"),
        TOO_SHORT("left too early", "magic.rewards.denied_short"),
        INACTIVE("did nothing", "magic.rewards.denied_inactive"),
        AFK("away for most of the match", "magic.rewards.denied_afk"),
        DAILY_CAP("hit the daily reward limit", "magic.rewards.denied_daily_cap");

        private final String log;
        private final String messageKey;

        Denial(String log, String messageKey) {
            this.log = log;
            this.messageKey = messageKey;
        }
    }

    /**
     * @return the pathway they played the match with, for display on the summary back home
     */
    private String eventPathway(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return null;
        }

        PathwayManager.PlayerMagicData data = plugin.getArenaPathwayManager().getPlayerData(player);
        if (data != null) {
            return data.getPathway();
        }

        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        var pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
    }
}
