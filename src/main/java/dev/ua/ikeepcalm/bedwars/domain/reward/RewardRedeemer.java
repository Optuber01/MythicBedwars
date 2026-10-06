package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter.AuditRow;
import dev.ua.ikeepcalm.bedwars.audit.RewardAuditFormat;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardKind;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies queued rewards on the survival server, where the player's real Beyonder lives.
 *
 * <p>Order matters, and it is enforced by sorting rather than by trusting the order the grants
 * happen to arrive in: acting first, then buffs, then items. Acting must precede the buffs because
 * {@code addActing} reads the live acting-speed and item multipliers — applying a buff first would
 * silently inflate the very grant it was rolled alongside. Items are last because a full inventory
 * is the one failure that has to be retried, and it must not block the half of the reward that
 * always succeeds.
 *
 * <p>Every grant is marked settled before its effect is requested, so a failure partway through
 * a bundle re-queues only the grants that were never started. A Circle of Imagination call or the
 * inventory hand-over can fail half-applied: a grant that may have landed is never re-queued, at
 * the cost of losing it if it did not.
 *
 * <p>A bundle stays in Redis, parked under a per-player lease, from the moment it is popped until it
 * has been applied. It is marked as being applied just before the first grant, and a mark left
 * behind by a crash is reported to staff and never paid again.
 */
public class RewardRedeemer {

    /**
     * Acting and cooldown resolve first, then buffs, then items. See the class note on why this is
     * not merely cosmetic.
     */
    private static final Comparator<RewardGrant> APPLY_ORDER = Comparator.comparingInt(grant ->
            switch (grant.kind()) {
                case ACTING_PERCENT, COOLDOWN_CREDIT -> 0;
                case ACTING_SPEED, ACTING_ITEM_MULT -> 1;
                case ITEM -> 2;
            });

    /** Appended to the event id of a bundle re-queued after a partial failure. */
    static final String RETRY_SUFFIX = ":retry";

    /** Appended, with a count, to the event id of a bundle handed back without being applied. */
    static final String RETURN_SUFFIX = ":return";

    /** How long a drain that could not finish waits before trying again, while the player stays online. */
    private static final long RETRY_DELAY_TICKS = 20L * 30;

    private static final long HEARTBEAT_TICKS = 20L * 30;

    private final MythicBedwars plugin;
    private final RewardConfig config;
    private final RewardQueue queue;

    /** Players with a drain under way here, so a second drain cannot take a bundle still being applied. */
    private final Set<UUID> draining = ConcurrentHashMap.newKeySet();
    private final Set<UUID> retryScheduled = ConcurrentHashMap.newKeySet();

    /** Renews the lease of each drain under way, so a slow main-thread apply cannot outlive it. */
    private final Map<UUID, BukkitTask> heartbeats = new ConcurrentHashMap<>();

    public RewardRedeemer(MythicBedwars plugin, RewardConfig config, RewardQueue queue) {
        this.plugin = plugin;
        this.config = config;
        this.queue = queue;
    }

    /**
     * Drains and applies whatever this player is owed.
     *
     * <p>Scheduled a little after login so Circle of Imagination has finished loading their Beyonder
     * — sizing an acting grant against a half-loaded profile would silently pay the wrong amount.
     */
    public void redeemOnJoin(Player player) {
        if (!config.isEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                drain(player);
            }
        }, config.redeemDelayTicks());
    }

    /**
     * @return the token's power ceiling, clamped into range. Older bundles predate the rolled value
     * and decode as {@code 0}, which would hand out an everything-token — treat that as weakest.
     */
    private static int tokenCeiling(RewardGrant grant) {
        if (!grant.isExchangeToken()) {
            return 9;
        }
        int rolled = grant.maxSequence();
        if (rolled < 1 || rolled > 9) {
            return 9;
        }
        return rolled;
    }

    private static RewardGrant withAmount(RewardGrant grant, double amount) {
        return new RewardGrant(grant.kind(), grant.tier(), null, amount, 0, 1, null, 9, grant.epic());
    }

    private static RewardGrant withCount(RewardGrant grant, int count) {
        return new RewardGrant(grant.kind(), grant.tier(), grant.item(), grant.amount(), grant.intArg(),
                count, grant.strArg(), grant.maxSequence(), grant.epic());
    }

    /**
     * Thousands-separated, because a five-digit acting grant is the payoff line of the whole feature
     * and "+13214" does not read as one.
     */
    static String number(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String multiplier(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String formatDuration(int seconds) {
        if (seconds >= 3600) {
            int hours = seconds / 3600;
            int minutes = (seconds % 3600) / 60;
            return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
        }
        if (seconds >= 60) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private void drain(Player player) {
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // One drain per player at a time, here and, by the lease, on every other server.
            if (!draining.add(playerId)) {
                return;
            }

            String drain = UUID.randomUUID().toString();
            if (!queue.lease(playerId, drain)) {
                draining.remove(playerId);
                scheduleRetry(playerId);
                return;
            }

            heartbeats.put(playerId, Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                try {
                    if (!queue.renew(playerId, drain)) {
                        plugin.log("Lost the reward lease for {} while it was held.", playerId);
                    }
                } catch (RuntimeException exception) {
                    plugin.log("Could not renew the reward lease for {}: {}", playerId, String.valueOf(exception.getMessage()));
                }
            }, HEARTBEAT_TICKS, HEARTBEAT_TICKS));
            advance(playerId, drain, 0);
        });
    }

    /** Ends a drain: stops its heartbeat and gives the lease up. */
    private void finish(UUID playerId, String drain) {
        BukkitTask heartbeat = heartbeats.remove(playerId);
        if (heartbeat != null) {
            heartbeat.cancel();
        }
        queue.release(playerId, drain);
        draining.remove(playerId);
    }

    /**
     * Takes, applies and settles one bundle, then the next. Off the main thread; the apply hops onto
     * it and the settling hops back, so one bundle is in flight per player and its record stays in
     * Redis until it has been dealt with.
     */
    private void advance(UUID playerId, String drain, int done) {
        Taken taken = new Taken(null, false);
        try {
            if (done < config.maxBundlesPerJoin()) {
                taken = take(playerId, drain);
            }
        } catch (RuntimeException exception) {
            plugin.log("Failed to take rewards for {}: {}", playerId, String.valueOf(exception.getMessage()));
            taken = new Taken(null, true);
        }

        RewardBundle claimed = taken.bundle();
        if (claimed == null) {
            finish(playerId, drain);
            if (taken.retry()) {
                scheduleRetry(playerId);
            }
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            List<Requeue> giveBack = List.of();
            try {
                giveBack = applyClaimed(playerId, claimed);
            } finally {
                List<Requeue> back = giveBack;
                plugin.offMainThread(() -> settle(playerId, drain, claimed, back, done));
            }
        });
    }

    /**
     * Drops the applied bundle from Redis and carries on while the player is still owed more. Stops
     * when something was handed back: it would only be taken again.
     */
    private void settle(UUID playerId, String drain, RewardBundle bundle, List<Requeue> giveBack, int done) {
        boolean more = giveBack.isEmpty();
        boolean settled = false;
        try {
            settled = queue.unpark(playerId, drain, giveBack.stream().map(Requeue::bundle).toList());
            if (!settled) {
                more = false;
                plugin.log("Could not settle reward bundle {} for {}; it stays parked and is reported at the next start.",
                        bundle.eventId(), bundle.playerName());
            }
        } catch (RuntimeException exception) {
            more = false;
        }
        for (Requeue entry : giveBack) {
            auditRequeue(entry, settled);
        }

        if (more && Bukkit.getPlayer(playerId) != null) {
            advance(playerId, drain, done + 1);
            return;
        }
        finish(playerId, drain);
    }

    /**
     * Takes the next bundle: claims it and marks it as being applied, both durably, before anything
     * is given. A bundle whose claim Redis does not answer stays parked, and a retry with its stored
     * token learns whether the claim landed, even after a restart.
     */
    private Taken take(UUID playerId, String drain) {
        while (true) {
            RewardQueue.Parked parked = queue.park(playerId, drain, UUID.randomUUID().toString());
            if (parked.status() == RewardQueue.ParkStatus.EMPTY) {
                return new Taken(null, false);
            }
            if (parked.status() == RewardQueue.ParkStatus.UNAVAILABLE) {
                return new Taken(null, true);
            }

            RewardQueue.ClaimAttempt result = queue.claimAttempt(
                    playerId, drain, parked.bundle().eventId(), parked.token());
            if (result == RewardQueue.ClaimAttempt.UNAVAILABLE) {
                auditUnresolved(parked.bundle());
                return new Taken(null, true);
            }

            if (result == RewardQueue.ClaimAttempt.DUPLICATE) {
                // Already applied by somebody else; dropping it was the cleanup.
                if (!queue.unpark(playerId, drain, List.of())) {
                    return new Taken(null, true);
                }
                auditAlreadyClaimed(parked.bundle());
                continue;
            }

            // Not applied yet, so nothing can be missing: only now does a restart stop being safe.
            if (!queue.markApplying(playerId, drain, parked.token())) {
                return new Taken(null, true);
            }
            return new Taken(parked.bundle(), false);
        }
    }

    /**
     * Tries again later while the player stays online. Offline players are left to their next join,
     * which finds whatever is still parked.
     */
    private void scheduleRetry(UUID playerId) {
        if (!plugin.isEnabled() || !retryScheduled.add(playerId)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            retryScheduled.remove(playerId);
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                drain(online);
            }
        }, RETRY_DELAY_TICKS);
    }

    /**
     * @return what has to go back to the queue
     */
    private List<Requeue> applyClaimed(UUID playerId, RewardBundle bundle) {
        // Collected rather than pushed inline: returning a bundle is a Redis round trip, and this
        // block runs on the main thread.
        List<Requeue> giveBack = new ArrayList<>();

        // Resolved now, not taken from the join: isOnline() looks the UUID up, so after a relog
        // inside the redeem delay the joined Player is a dead session that still reports online,
        // and items handed to its inventory would vanish with it.
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            // Put it back untouched rather than losing it to a badly timed logout.
            giveBack.add(Requeue.untouched(bundle, "offline"));
        } else {
            Progress progress = new Progress(bundle);
            try {
                apply(player, bundle, progress, giveBack);
            } catch (RuntimeException exception) {
                // The bundle is already claimed, so the grants that did not land have to be put back.
                plugin.log("Failed to apply rewards for {} ({}): {}",
                        player.getName(), bundle.eventId(), String.valueOf(exception.getMessage()));
                handleFailure(bundle, progress, exception, giveBack);
            }
        }

        return giveBack;
    }

    /**
     * Re-queues only what did not land. The original claim is kept, so a stray copy of the original
     * bundle can never be applied again; the remainder travels under its own id.
     */
    private void handleFailure(RewardBundle bundle, Progress progress, RuntimeException exception,
                               List<Requeue> giveBack) {
        if (progress.handedBack) {
            // Already on its way back whole (held for a non-Beyonder); queuing it twice would dupe it.
            return;
        }

        String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        List<RewardGrant> remainder = progress.remainder();
        if (remainder.isEmpty()) {
            // Everything landed; the failure came after the last grant settled.
            if (!progress.reported) {
                auditRedeemed(bundle, progress, AuditOutcome.COMMITTED, "redeemed_with_error", error);
            }
            return;
        }

        RewardBundle retry = new RewardBundle(
                RewardBundle.SCHEMA, bundle.eventId() + RETRY_SUFFIX, bundle.arena(),
                bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                bundle.earnedAtEpochMs(), List.copyOf(remainder));
        giveBack.add(new Requeue(retry, AuditOutcome.FAILED, "failed_requeued", "apply_exception",
                error, bundle.eventId(), progress.settledCount()));
    }

    private void apply(Player player, RewardBundle bundle, Progress progress, List<Requeue> giveBack) {
        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        CoiCapabilities capabilities = plugin.getCoiCapabilities();

        PathwayData primary = primaryPathway(api, player);
        String pathway = primary == null ? null : primary.name();
        boolean beyonder = pathway != null;

        // HOLD means exactly that: park the whole bundle until they have somewhere to put it,
        // rather than applying the item half and quietly binning the rest.
        if (!beyonder && !config.substituteForNonBeyonders()) {
            progress.handedBack = true;
            giveBack.add(Requeue.untouched(bundle, "held_non_beyonder"));
            player.sendMessage(message(player, "magic.redeem.held_until_awakened"));
            return;
        }

        int needed = beyonder ? api.getActingRequiredForNextSequence(player, pathway) : 0;
        Redemption r = new Redemption(api, capabilities, player, pathway, needed,
                primary == null ? -1 : auditSequence(primary), bundle, progress, new ArrayList<>(),
                giveBack);

        List<PendingItem> items = new ArrayList<>();
        List<RewardGrant> ordered = progress.ordered;

        for (int index = 0; index < ordered.size(); index++) {
            RewardGrant grant = ordered.get(index);
            switch (grant.kind()) {
                case ACTING_PERCENT -> {
                    if (beyonder) {
                        applyActing(r, index, grant, "acting_percent");
                    } else {
                        substitute(r, items, index, grant);
                    }
                }
                case COOLDOWN_CREDIT -> {
                    if (beyonder && capabilities.cooldownCredit()) {
                        applyCooldown(r, index, grant);
                    } else if (beyonder) {
                        // Older COI cannot credit cooldowns; pay the equivalent in acting instead.
                        applyActing(r, index, withAmount(grant, config.cooldownSubstitutePercent()),
                                "cooldown_unsupported");
                    } else {
                        substitute(r, items, index, grant);
                    }
                }
                case ACTING_SPEED -> {
                    if (beyonder) {
                        applySpeed(r, index, grant);
                    } else {
                        substitute(r, items, index, grant);
                    }
                }
                case ACTING_ITEM_MULT -> {
                    if (beyonder) {
                        applyItemMultiplier(r, index, grant);
                    } else {
                        substitute(r, items, index, grant);
                    }
                }
                case ITEM -> buildItem(r, items, index, grant);
            }
        }

        player.sendMessage(message(player, "magic.redeem.header"));
        player.sendMessage(message(player, "magic.redeem.context",
                "pathway", bundle.eventPathway() == null ? "?" : bundle.eventPathway(),
                "result", plugin.getLocaleManager().getMessage(player,
                        bundle.won() ? "magic.redeem.result_victory" : "magic.redeem.result_participation"),
                "ago", describeAge(bundle.earnedAtEpochMs())));

        if (!beyonder) {
            player.sendMessage(message(player, "magic.redeem.not_beyonder"));
        }

        r.summary().forEach(player::sendMessage);
        giveItems(r, items);
        auditRedeemed(bundle, progress, AuditOutcome.COMMITTED, "redeemed", null,
                row -> row.put("beyonder", beyonder).put("pathway", pathway)
                        .put("sequence", r.sequence() >= 0 ? r.sequence() : null)
                        .put("needed", needed));
        player.sendMessage(message(player, "magic.redeem.footer"));

        if (ordered.stream().anyMatch(RewardGrant::epic)) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
    }

    private void applyActing(Redemption r, int index, RewardGrant grant, String via) {
        Player player = r.player();
        if (r.needed() <= 0) {
            // Sequence 0, or an outer pathway that advances by sacrifice rather than acting.
            r.progress().settle(index);
            auditActing(r, grant, via, 0, 0, AuditOutcome.DENIED, "not_applicable");
            r.summary().add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        int points = (int) Math.round(r.needed() * grant.amount() / 100.0);
        if (points <= 0) {
            r.progress().settle(index);
            auditActing(r, grant, via, points, 0, AuditOutcome.DENIED, "zero_points");
            return;
        }

        // Settled first: if the call throws after changing state, the grant must not be re-queued.
        r.progress().settle(index);
        int granted = r.api().grantActing(player, r.pathway(), r.capabilities().rewardSource(), points);
        if (granted <= 0) {
            auditActing(r, grant, via, points, granted, AuditOutcome.DENIED, "source_capped");
            // Say so rather than dropping it silently - a reward that vanishes reads as a bug.
            r.summary().add(message(player, "magic.redeem.acting_capped"));
            return;
        }
        auditActing(r, grant, via, points, granted, AuditOutcome.COMMITTED, null);

        // Report what actually landed, not what was rolled. COI scales a grant by the player's
        // sequence on the way in, so the rolled percentage overstates it at low sequences and
        // understates it at high ones.
        r.summary().add(message(player, "magic.redeem.acting_granted",
                "amount", number(granted),
                "percent", percent(granted * 100.0 / r.needed()),
                "sequence", r.api().getLowestSequence(player)));
    }

    private void applyCooldown(Redemption r, int index, RewardGrant grant) {
        Player player = r.player();
        String methodId = r.api().getActingMethodId(player, r.pathway());
        if (methodId == null) {
            r.progress().settle(index);
            auditBuff(r, grant, AuditOutcome.DENIED, "no_acting_method", row -> row.put("buff", "cooldown_credit"));
            r.summary().add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        long total = r.api().getActingMethodCooldownSeconds(methodId);
        long seconds = Math.round(total * grant.amount() / 100.0);
        if (seconds <= 0) {
            r.progress().settle(index);
            auditBuff(r, grant, AuditOutcome.DENIED, "zero_seconds",
                    row -> row.put("buff", "cooldown_credit").put("method_id", methodId));
            return;
        }

        r.progress().settle(index);
        long credited = r.api().creditActingCooldown(player, methodId, seconds);
        if (credited <= 0) {
            auditBuff(r, grant, AuditOutcome.DENIED, "cooldown_ready",
                    row -> row.put("buff", "cooldown_credit").put("method_id", methodId)
                            .put("seconds_requested", seconds).put("seconds_credited", credited));
            // Their method was already off cooldown, so there was nothing to shave. Paying the
            // acting equivalent instead keeps the largest slice of the loot table from being a
            // coin-flip no-op for anyone who logged off at a natural stopping point.
            r.summary().add(message(player, "magic.redeem.cooldown_ready"));
            applyActing(r, index, withAmount(grant, config.cooldownSubstitutePercent()), "cooldown_ready");
            return;
        }

        long remaining = r.api().getActingCooldownRemaining(player.getUniqueId(), methodId);
        auditBuff(r, grant, AuditOutcome.COMMITTED, null,
                row -> row.put("buff", "cooldown_credit").put("method_id", methodId)
                        .put("seconds_requested", seconds).put("seconds_credited", credited)
                        .put("seconds_remaining", remaining));
        r.summary().add(message(player, "magic.redeem.cooldown_credited",
                "credited", formatDuration((int) credited),
                "remaining", formatDuration((int) Math.max(0, remaining))));
    }

    /**
     * Both multiplier setters take a <b>duration</b>; Circle of Imagination adds "now" itself. Passing
     * an absolute timestamp here would set an expiry decades out and make the buff permanent.
     */
    private void applySpeed(Redemption r, int index, RewardGrant grant) {
        Player player = r.player();
        double current = r.api().getActingSpeedMultiplier(player);
        if (current >= grant.amount()) {
            r.progress().settle(index);
            auditMultiplier(r, grant, "acting_speed", current, true);
            // They are already running a stronger buff; overwriting would be a downgrade.
            r.summary().add(message(player, "magic.redeem.buff_kept"));
            return;
        }

        r.progress().settle(index);
        r.api().setActingSpeedMultiplier(player, grant.amount(), grant.intArg() * 1000L);
        auditMultiplier(r, grant, "acting_speed", current, false);
        r.summary().add(message(player, "magic.redeem.speed_applied",
                "percent", Math.round(grant.amount() * 100),
                "duration", formatDuration(grant.intArg())));
    }

    private void applyItemMultiplier(Redemption r, int index, RewardGrant grant) {
        Player player = r.player();
        double current = r.api().getActingItemMultiplier(player);
        if (current >= grant.amount()) {
            r.progress().settle(index);
            auditMultiplier(r, grant, "acting_item_multiplier", current, true);
            r.summary().add(message(player, "magic.redeem.buff_kept"));
            return;
        }

        r.progress().settle(index);
        r.api().setActingItemMultiplier(player, grant.amount(), grant.intArg() * 1000L);
        auditMultiplier(r, grant, "acting_item_multiplier", current, false);
        r.summary().add(message(player, "magic.redeem.item_mult_applied",
                "multiplier", multiplier(grant.amount()),
                "duration", formatDuration(grant.intArg())));
    }

    private PathwayData primaryPathway(CircleOfImaginationAPI api, Player player) {
        if (!api.isBeyonder(player)) {
            return null;
        }

        List<PathwayData> pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst();
    }

    /**
     * Turns a grant the player cannot receive yet into something they can keep until they can.
     *
     * <p>Sized from the grant so a headline winner grant does not collapse into the same consolation
     * bottle as a minimal participation one.
     */
    private void substitute(Redemption r, List<PendingItem> items, int index, RewardGrant grant) {
        double share = grant.kind() == RewardKind.ACTING_PERCENT || grant.kind() == RewardKind.COOLDOWN_CREDIT
                ? Math.max(1.0, grant.amount())
                : 1.0;

        int acting = Math.max(1, (int) Math.round(config.fallbackBottleActing() * share / 2.0));
        ItemStack bottle = r.api().createActingBottle(acting);
        if (bottle != null) {
            items.add(new PendingItem(bottle, grant, index, true, acting));
        } else {
            unavailable(r, index, grant, true);
        }
    }

    private void buildItem(Redemption r, List<PendingItem> items, int index, RewardGrant grant) {
        RewardItemKind kind = grant.item();
        if (kind == null) {
            unavailable(r, index, grant, false);
            return;
        }

        CircleOfImaginationAPI api = r.api();
        String tier = grant.strArg() == null ? "small" : grant.strArg();
        int sequence = tokenCeiling(grant);
        int bottleActing = kind == RewardItemKind.ACTING_BOTTLE ? bottleActing(r.needed(), grant) : 0;

        ItemStack stack = switch (kind) {
            // The configured value is a percentage like every other amount in the file; resolve it
            // against the player's real bar here. Treating it as a raw point count would make one
            // bottle worth 10% of a Sequence-9 bar and 0.25% of a Sequence-3 one.
            case ACTING_BOTTLE -> api.createActingBottle(bottleActing);
            case SPIRITUALITY_POTION -> api.createSpiritualityPotion(tier, Math.max(1, grant.intArg()));
            case ACTING_MULTIPLIER -> api.createActingMultiplier(tier, grant.amount(), Math.max(1, grant.intArg()));
            case SPIRITUALITY_REGEN -> api.createSpiritualityRegenBooster(tier, grant.amount(), Math.max(1, grant.intArg()));
            case ANTI_CONTROL_CHARM -> api.createAntiControlCharm();
            case RITUAL_BOOK -> api.createRitualBook(Math.max(1, grant.intArg()));
            case INGREDIENT_TOKEN -> api.createIngredientToken();
            case RECIPE_TOKEN -> api.createRecipeToken(sequence);
            case POTION_TOKEN -> api.createPotionToken(sequence);
            case UNIVERSAL_RECIPE_TOKEN -> api.createUniversalRecipeToken(sequence);
            case UNIVERSAL_POTION_TOKEN -> api.createUniversalPotionToken(sequence);
            case PATHWAY_TRANSFER_TOKEN -> api.createPathwayTransferToken();
        };

        if (stack == null) {
            unavailable(r, index, grant, false);
            return;
        }

        if (grant.count() > 1) {
            stack.setAmount(Math.min(stack.getMaxStackSize(), grant.count()));
        }

        items.add(new PendingItem(stack, grant, index, false, bottleActing));
    }

    /**
     * Circle of Imagination could not build the item. Re-queuing would fail the same way on every
     * login, so the grant is settled and the loss is recorded exactly.
     */
    private void unavailable(Redemption r, int index, RewardGrant grant, boolean substituted) {
        r.progress().settle(index);
        guarded(() -> plugin.getAudit().emit(itemRow(r.bundle(), grant, substituted, AuditOutcome.FAILED)
                .risk(AuditRisk.HIGH)
                .reason("item_unavailable")
                .put("result", "not_delivered")
                .put("remainder", RewardAuditFormat.grant(grant))));
    }

    /**
     * @return the acting a rewarded bottle should hold, as a share of the player's real next
     * sequence, falling back to the configured flat amount when there is no bar to measure
     */
    private int bottleActing(int needed, RewardGrant grant) {
        if (needed <= 0) {
            return Math.max(1, config.fallbackBottleActing());
        }
        return Math.max(1, (int) Math.round(needed * grant.amount() / 100.0));
    }

    private void giveItems(Redemption r, List<PendingItem> items) {
        if (items.isEmpty()) {
            return;
        }

        Player player = r.player();

        // addItem consumes the array in place, so keep an untouched copy to reason about later.
        ItemStack[] offered = new ItemStack[items.size()];
        for (int i = 0; i < items.size(); i++) {
            offered[i] = items.get(i).stack().clone();
        }

        // Settled before the hand-over, not after. addItem places stack by stack, so if it throws,
        // any of them may already be in the inventory and there is no telling which. Re-queuing
        // them would duplicate whatever landed; settling them loses whatever did not. Loss is the
        // side taken, and the delivery_unknown rows are what an operator reconciles from. Only
        // leftovers that addItem reports after returning normally are re-opened below.
        for (PendingItem item : items) {
            r.progress().settle(item.index());
        }

        Map<Integer, ItemStack> leftovers;
        try {
            leftovers = player.getInventory().addItem(offered);
        } catch (RuntimeException exception) {
            String error = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            for (PendingItem item : items) {
                auditUnknownDelivery(r, item, error);
            }
            plugin.log("Inventory hand-over for {} ({}) failed partway; {} item grant(s) treated as delivered, not retried",
                    player.getName(), r.bundle().eventId(), items.size());
            throw exception;
        }

        List<RewardGrant> unplaced = new ArrayList<>();
        List<ItemStack> partial = new ArrayList<>();
        List<Integer> partialIndexes = new ArrayList<>();
        // By index: RewardGrant is a record, so two identical grants are equal, and matching by
        // value could drop the same leftover stack twice.
        List<Integer> unplacedIndexes = new ArrayList<>();
        List<Integer> placedIndexes = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            PendingItem item = items.get(i);
            ItemStack rejected = leftovers.get(i);
            if (rejected == null) {
                r.progress().settle(item.index());
                placedIndexes.add(i);
                auditItem(r, item, item.stack(), item.stack().getAmount(), "placed", null);
            } else if (rejected.getAmount() >= item.stack().getAmount()) {
                // Nothing of it went in, so it is owed again until it is dropped or re-queued.
                r.progress().reopen(item.index());
                unplaced.add(item.grant());
                unplacedIndexes.add(i);
            } else {
                // Part of the stack went in. Re-queuing the grant would hand over the whole
                // thing again, so the remainder goes on the floor instead.
                int placedAmount = item.stack().getAmount() - rejected.getAmount();
                r.progress().partial(item.index(), withCount(item.grant(), rejected.getAmount()));
                auditItem(r, item, item.stack(), placedAmount, "placed", null);
                partial.add(rejected);
                partialIndexes.add(i);
            }
        }

        if (!leftovers.isEmpty()) {
            Location location = player.getLocation();

            // Whatever could only be partially placed is dropped regardless of the overflow policy.
            for (int p = 0; p < partial.size(); p++) {
                dropAndAudit(r, items.get(partialIndexes.get(p)), partial.get(p), location);
            }

            if (config.requeueOverflow() && !unplaced.isEmpty()) {
                requeueOverflow(r, items, unplacedIndexes, unplaced);
            } else if (!unplacedIndexes.isEmpty()) {
                for (int index : unplacedIndexes) {
                    dropAndAudit(r, items.get(index), leftovers.get(index), location);
                }
            }
        }

        for (int i : placedIndexes) {
            PendingItem item = items.get(i);
            player.sendMessage(message(player,
                    item.grant().epic() ? "magic.redeem.item_given_epic" : "magic.redeem.item_given",
                    "amount", item.stack().getAmount(), "item", describeItem(item.stack())));
        }

        if (leftovers.isEmpty()) {
            return;
        }

        if (config.requeueOverflow() && !unplaced.isEmpty()) {
            player.sendMessage(message(player, "magic.redeem.item_requeued", "count", unplaced.size()));
        } else if (!unplacedIndexes.isEmpty()) {
            player.sendMessage(message(player, "magic.redeem.item_dropped", "count", unplacedIndexes.size()));
        }

        if (!partial.isEmpty()) {
            player.sendMessage(message(player, "magic.redeem.item_dropped", "count", partial.size()));
        }
    }

    /**
     * Re-queues under a distinct id so the claim guard does not reject the retry, and carries the
     * grants across - an overflow bundle with no grants would silently destroy them.
     */
    private void requeueOverflow(Redemption r, List<PendingItem> items, List<Integer> unplacedIndexes,
                                 List<RewardGrant> unplaced) {
        RewardBundle bundle = r.bundle();
        RewardBundle overflow = new RewardBundle(
                RewardBundle.SCHEMA, bundle.eventId() + ":overflow", bundle.arena(),
                bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                bundle.earnedAtEpochMs(), List.copyOf(unplaced));
        for (int index : unplacedIndexes) {
            r.progress().settle(items.get(index).index());
            r.progress().requeued++;
        }
        // Goes back with the rest, in the same step that drops the parked bundle.
        r.giveBack().add(new Requeue(overflow, AuditOutcome.CANCELLED, "overflow_requeued",
                "inventory_full", null, bundle.eventId(), 0));
    }

    /**
     * Drops a stack at the player and records it. A drop counts as delivered when the world returned
     * an entity; otherwise the exact stack is recorded as lost. Whether the entity is still valid is
     * not read, so a drop that another plugin removes at once still shows as dropped.
     */
    private void dropAndAudit(Redemption r, PendingItem item, ItemStack stack, Location location) {
        Item dropped = r.player().getWorld().dropItem(location, stack);
        r.progress().settle(item.index());
        boolean delivered = dropped != null;
        if (delivered) {
            r.progress().dropped++;
            auditItem(r, item, stack, stack.getAmount(), "dropped", location);
        } else {
            auditItem(r, item, stack, stack.getAmount(), "drop_failed", location);
        }
    }

    private String describeItem(ItemStack stack) {
        return stack.getItemMeta() != null && stack.getItemMeta().hasDisplayName()
                ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(stack.getItemMeta().displayName())
                : stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String describeAge(long earnedAt) {
        long minutes = Math.max(0, (System.currentTimeMillis() - earnedAt) / 60_000);
        if (minutes < 60) {
            return minutes + "m ago";
        }
        if (minutes < 1440) {
            return (minutes / 60) + "h ago";
        }
        return (minutes / 1440) + "d ago";
    }

    private Component message(Player player, String key, Object... args) {
        return plugin.getLocaleManager().formatMessage(player, key, args);
    }

    /**
     * Audit-only read of the sequence off the pathway data the apply already fetched, so the row costs
     * no CoI lookup of its own. A missing or mismatched CoI must never abort a redemption.
     */
    private static int auditSequence(PathwayData pathway) {
        try {
            return pathway.lowestSequenceLevel();
        } catch (RuntimeException | LinkageError ignored) {
            return -1;
        }
    }

    /**
     * Runs audit-only work. Nothing in here may throw into a redemption: an exception while building
     * a row would otherwise be treated as a failed apply and change what the player receives.
     */
    private static void guarded(Runnable audit) {
        try {
            audit.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort.
        }
    }

    private AuditRow grantRow(String operation, RewardBundle bundle, RewardGrant grant, AuditOutcome outcome) {
        return AuditRow.of(operation, outcome)
                .correlation(BedwarsAuditEmitter.bundleCorrelation(bundle.eventId(), bundle.playerId()))
                .business(bundle.eventId())
                .subject(bundle.playerId())
                .put("actor", "system")
                .put("event_id", bundle.eventId())
                .put("grant_kind", grant.kind().name())
                .put("grant_tier", grant.tier() == null ? null : grant.tier().name())
                .put("epic", grant.epic());
    }

    private AuditRow itemRow(RewardBundle bundle, RewardGrant grant, boolean substituted, AuditOutcome outcome) {
        AuditRow row = grantRow("reward.item_granted", bundle, grant, outcome)
                .put("substituted", substituted)
                .put("grant_count", grant.count());
        if (grant.item() != null) {
            row.put("item_kind", grant.item().name());
        }
        if (grant.strArg() != null) {
            row.put("item_tier", grant.strArg());
        }
        if (grant.isExchangeToken()) {
            row.put("token_max_sequence", tokenCeiling(grant));
        }
        return row;
    }

    private void auditItem(Redemption r, PendingItem item, ItemStack stack, int amount, String result,
                           Location location) {
        guarded(() -> emitItem(r, item, stack, amount, result, location));
    }

    private void emitItem(Redemption r, PendingItem item, ItemStack stack, int amount, String result,
                          Location location) {
        boolean failed = "drop_failed".equals(result);
        AuditRow row = itemRow(r.bundle(), item.grant(), item.substituted(),
                failed ? AuditOutcome.FAILED : AuditOutcome.COMMITTED)
                .put("result", result);
        if (item.bottleActing() > 0) {
            row.put("bottle_acting", item.bottleActing());
        }
        BedwarsAuditEmitter.putItem(row.metadata(), stack);
        // After putItem: the amount that actually landed, not the stack's nominal size.
        row.put("amount", amount);
        if (location != null) {
            BedwarsAuditEmitter.putLocation(row.metadata(), location);
        }
        if (failed) {
            row.risk(AuditRisk.HIGH).reason("drop_failed")
                    .put("remainder", RewardAuditFormat.grant(withCount(item.grant(), amount)));
        }
        plugin.getAudit().emit(row);
    }

    /**
     * An item offered to an inventory hand-over that threw. It is settled and will not be retried,
     * so {@code remainder} is what the player is owed if it did not actually land.
     */
    private void auditUnknownDelivery(Redemption r, PendingItem item, String error) {
        guarded(() -> {
            AuditRow row = itemRow(r.bundle(), item.grant(), item.substituted(), AuditOutcome.FAILED)
                    .risk(AuditRisk.HIGH)
                    .reason("inventory_add_failed")
                    .put("result", "delivery_unknown")
                    .put("error", error);
            if (item.bottleActing() > 0) {
                row.put("bottle_acting", item.bottleActing());
            }
            BedwarsAuditEmitter.putItem(row.metadata(), item.stack());
            row.put("amount", item.stack().getAmount())
                    .put("remainder", RewardAuditFormat.grant(withCount(item.grant(), item.stack().getAmount())));
            plugin.getAudit().emit(row);
        });
    }

    private void auditActing(Redemption r, RewardGrant grant, String via, int points, int granted,
                             AuditOutcome outcome, String reason) {
        guarded(() -> plugin.getAudit().emit(grantRow("reward.acting_granted", r.bundle(), grant, outcome)
                .reason(reason)
                .put("via", via)
                .put("pathway", r.pathway())
                .put("sequence", r.sequence() >= 0 ? r.sequence() : null)
                .put("needed", r.needed())
                .put("percent_requested", grant.amount())
                .put("points_requested", points)
                .put("granted", granted)
                .put("source", r.capabilities().rewardSource().id())
                .put("capped", granted <= 0 && points > 0)));
    }

    private void auditBuff(Redemption r, RewardGrant grant, AuditOutcome outcome, String reason,
                           java.util.function.Consumer<AuditRow> extra) {
        guarded(() -> {
            AuditRow row = grantRow("reward.buff_applied", r.bundle(), grant, outcome)
                    .reason(reason)
                    .put("pathway", r.pathway())
                    .put("percent_requested", grant.amount());
            extra.accept(row);
            plugin.getAudit().emit(row);
        });
    }

    private void auditMultiplier(Redemption r, RewardGrant grant, String buff, double current, boolean kept) {
        auditBuff(r, grant, kept ? AuditOutcome.DENIED : AuditOutcome.COMMITTED, kept ? "kept_existing" : null,
                row -> row.put("buff", buff)
                        .put("multiplier", grant.amount())
                        .put("previous_multiplier", current)
                        .put("duration_seconds", grant.intArg())
                        .put("kept_existing", kept));
    }

    private void auditRedeemed(RewardBundle bundle, Progress progress, AuditOutcome outcome, String result,
                               String error) {
        auditRedeemed(bundle, progress, outcome, result, error, null);
    }

    private void auditRedeemed(RewardBundle bundle, Progress progress, AuditOutcome outcome, String result,
                               String error, java.util.function.Consumer<AuditRow> extra) {
        progress.reported = true;
        guarded(() -> emitRedeemed(bundle, progress, outcome, result, error, extra));
    }

    private void emitRedeemed(RewardBundle bundle, Progress progress, AuditOutcome outcome, String result,
                              String error, java.util.function.Consumer<AuditRow> extra) {
        AuditRow row = RewardAuditFormat.describe(AuditRow.of("reward.bundle_redeemed", outcome), bundle)
                .put("result", result)
                .put("error", error)
                .put("settled", progress.settledCount())
                .put("dropped", progress.dropped)
                .put("overflow_requeued", progress.requeued)
                .put("retry", bundle.eventId().contains(":"));
        if (extra != null) {
            extra.accept(row);
        }
        plugin.getAudit().emit(row);
    }

    /** Runs wherever Redis answered; plain values only. */
    private void auditRequeue(Requeue entry, boolean pushed) {
        guarded(() -> emitRequeue(entry, pushed));
    }

    private void emitRequeue(Requeue entry, boolean pushed) {
        AuditOutcome outcome = pushed ? entry.outcome() : AuditOutcome.FAILED;
        String result = pushed ? entry.result() : "requeue_failed";
        AuditRow row = RewardAuditFormat.describe(AuditRow.of("reward.bundle_redeemed", outcome), entry.bundle())
                .reason(entry.reason())
                .put("result", result)
                .put("error", entry.error())
                .put("original_event_id", entry.originalEventId())
                .put("settled", entry.settled())
                .put("pushed", pushed)
                .put("retry", entry.bundle().eventId().contains(":"));
        if (!pushed || outcome == AuditOutcome.FAILED) {
            row.risk(AuditRisk.HIGH).put("remainder", RewardAuditFormat.grants(entry.bundle().grants()));
        }
        plugin.getAudit().emit(row);
    }

    /** Runs on the drain's async thread; plain values only. */
    private void auditAlreadyClaimed(RewardBundle bundle) {
        guarded(() -> plugin.getAudit().emit(RewardAuditFormat.describe(
                        AuditRow.of("reward.bundle_redeemed", AuditOutcome.DENIED), bundle)
                .risk(AuditRisk.HIGH)
                .reason("already_claimed")
                .put("result", "discarded_duplicate")
                .put("retry", bundle.eventId().contains(":"))));
    }

    /**
     * A popped bundle held because its claim got no answer. Runs on the drain's async thread; plain
     * values only. A later row for the same event id settles it; without one, {@code remainder} is owed.
     */
    private void auditUnresolved(RewardBundle bundle) {
        guarded(() -> plugin.getAudit().emit(RewardAuditFormat.describe(
                        AuditRow.of("reward.bundle_redeemed", AuditOutcome.OBSERVED), bundle)
                .risk(AuditRisk.HIGH)
                .reason("redis_unavailable")
                .put("result", "claim_unresolved")
                .put("remainder", RewardAuditFormat.grants(bundle.grants()))
                .put("retry", bundle.eventId().contains(":"))));
    }

    /** The bundle to apply, or none, and whether to try again later. */
    private record Taken(RewardBundle bundle, boolean retry) {
    }

    /**
     * Everything one bundle's apply needs, so the per-grant steps do not each take eight arguments.
     */
    private record Redemption(CircleOfImaginationAPI api, CoiCapabilities capabilities, Player player,
                              String pathway, int needed, int sequence, RewardBundle bundle,
                              Progress progress, List<Component> summary, List<Requeue> giveBack) {
    }

    /**
     * An item waiting to be handed over, remembering which grant it settles.
     */
    private record PendingItem(ItemStack stack, RewardGrant grant, int index, boolean substituted,
                               int bottleActing) {
    }

    /**
     * A bundle on its way back to the queue.
     */
    private record Requeue(RewardBundle bundle, AuditOutcome outcome, String result,
                           String reason, String error, String originalEventId, int settled) {

        /**
         * Hands the bundle back under a fresh id and keeps the original claim. Releasing the claim
         * after the push would leave a moment where a concurrent drain pops the pushed copy, finds
         * the claim still held and discards it as a duplicate.
         */
        static Requeue untouched(RewardBundle bundle, String reason) {
            RewardBundle returned = new RewardBundle(
                    RewardBundle.SCHEMA, returnedId(bundle.eventId()), bundle.arena(),
                    bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                    bundle.earnedAtEpochMs(), bundle.grants());
            return new Requeue(returned, AuditOutcome.CANCELLED, "requeued", reason, null,
                    bundle.eventId(), 0);
        }

        /**
         * {@code <id>:return1}, then {@code :return2} and so on: counted rather than appended again,
         * so a player held at every login does not grow the id without bound.
         */
        private static String returnedId(String eventId) {
            int at = eventId.lastIndexOf(RETURN_SUFFIX);
            if (at >= 0) {
                String count = eventId.substring(at + RETURN_SUFFIX.length());
                if (!count.isEmpty() && count.length() < 10 && count.chars().allMatch(Character::isDigit)) {
                    return eventId.substring(0, at) + RETURN_SUFFIX + (Integer.parseInt(count) + 1);
                }
            }
            return eventId + RETURN_SUFFIX + 1;
        }
    }

    /**
     * Which grants of one bundle have landed. A grant is settled the moment its effect is applied
     * (or definitively cannot be), or just before an effect that could land without saying so, so
     * the remainder after a failure never includes anything that may already have been given.
     */
    private static final class Progress {
        final List<RewardGrant> ordered;
        private final boolean[] settled;
        private final RewardGrant[] partialRemainder;
        boolean handedBack;
        boolean reported;
        int dropped;
        int requeued;

        Progress(RewardBundle bundle) {
            List<RewardGrant> sorted = new ArrayList<>(bundle.grants());
            sorted.sort(APPLY_ORDER);
            this.ordered = sorted;
            this.settled = new boolean[sorted.size()];
            this.partialRemainder = new RewardGrant[sorted.size()];
        }

        void settle(int index) {
            settled[index] = true;
            partialRemainder[index] = null;
        }

        /** Settled ahead of an effect that then turned out not to land; the whole grant is owed. */
        void reopen(int index) {
            settled[index] = false;
            partialRemainder[index] = null;
        }

        /** Part of the grant landed; until the rest is handled, only {@code rest} is still owed. */
        void partial(int index, RewardGrant rest) {
            settled[index] = false;
            partialRemainder[index] = rest;
        }

        int settledCount() {
            int count = 0;
            for (boolean value : settled) {
                if (value) {
                    count++;
                }
            }
            return count;
        }

        List<RewardGrant> remainder() {
            List<RewardGrant> rest = new ArrayList<>();
            for (int i = 0; i < ordered.size(); i++) {
                if (!settled[i]) {
                    rest.add(partialRemainder[i] != null ? partialRemainder[i] : ordered.get(i));
                }
            }
            return rest;
        }
    }
}
