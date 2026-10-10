package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardGrant;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardKind;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import dev.ua.ikeepcalm.coi.api.model.PathwayData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
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
            List<RewardBundle> giveBack = List.of();
            try {
                giveBack = applyClaimed(playerId, claimed);
            } finally {
                List<RewardBundle> back = giveBack;
                plugin.offMainThread(() -> settle(playerId, drain, claimed, back, done));
            }
        });
    }

    /**
     * Drops the applied bundle from Redis and carries on while the player is still owed more. Stops
     * when something was handed back: it would only be taken again.
     */
    private void settle(UUID playerId, String drain, RewardBundle bundle, List<RewardBundle> giveBack, int done) {
        boolean more = giveBack.isEmpty();
        try {
            if (!queue.unpark(playerId, drain, giveBack)) {
                more = false;
                plugin.log("Could not settle reward bundle {} for {}; it stays parked and is reported at the next start.",
                        bundle.eventId(), bundle.playerName());
            }
        } catch (RuntimeException exception) {
            more = false;
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
                return new Taken(null, true);
            }

            if (result == RewardQueue.ClaimAttempt.DUPLICATE) {
                // Already applied by somebody else; dropping it was the cleanup.
                if (!queue.unpark(playerId, drain, List.of())) {
                    return new Taken(null, true);
                }
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
    private List<RewardBundle> applyClaimed(UUID playerId, RewardBundle bundle) {
        // Collected rather than pushed inline: returning a bundle is a Redis round trip, and this
        // block runs on the main thread.
        List<RewardBundle> giveBack = new ArrayList<>();

        // Resolved now, not taken from the join: isOnline() looks the UUID up, so after a relog
        // inside the redeem delay the joined Player is a dead session that still reports online,
        // and items handed to its inventory would vanish with it.
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            // Put it back untouched rather than losing it to a badly timed logout.
            giveBack.add(returned(bundle));
        } else {
            Progress progress = new Progress(bundle);
            try {
                apply(player, bundle, progress, giveBack);
            } catch (RuntimeException exception) {
                // The bundle is already claimed, so the grants that did not land have to be put back.
                plugin.log("Failed to apply rewards for {} ({}): {}",
                        player.getName(), bundle.eventId(), String.valueOf(exception.getMessage()));
                handleFailure(bundle, progress, giveBack);
            }
        }

        return giveBack;
    }

    /**
     * Re-queues only what did not land. The original claim is kept, so a stray copy of the original
     * bundle can never be applied again; the remainder travels under its own id.
     */
    private void handleFailure(RewardBundle bundle, Progress progress, List<RewardBundle> giveBack) {
        if (progress.handedBack) {
            // Already on its way back whole (held for a non-Beyonder); queuing it twice would dupe it.
            return;
        }

        List<RewardGrant> remainder = progress.remainder();
        if (remainder.isEmpty()) {
            // Everything landed; the failure came after the last grant settled.
            return;
        }

        giveBack.add(new RewardBundle(
                RewardBundle.SCHEMA, bundle.eventId() + RETRY_SUFFIX, bundle.arena(),
                bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                bundle.earnedAtEpochMs(), List.copyOf(remainder)));
    }

    /**
     * Hands a bundle back under a fresh id and keeps the original claim. Releasing the claim after
     * the push would leave a moment where a concurrent drain pops the pushed copy, finds the claim
     * still held and discards it as a duplicate.
     */
    private static RewardBundle returned(RewardBundle bundle) {
        return new RewardBundle(
                RewardBundle.SCHEMA, returnedId(bundle.eventId()), bundle.arena(),
                bundle.playerId(), bundle.playerName(), bundle.eventPathway(), bundle.won(),
                bundle.earnedAtEpochMs(), bundle.grants());
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

    private void apply(Player player, RewardBundle bundle, Progress progress, List<RewardBundle> giveBack) {
        CircleOfImaginationAPI api = plugin.getCircleOfImaginationAPI();
        CoiCapabilities capabilities = plugin.getCoiCapabilities();

        String pathway = primaryPathway(api, player);
        boolean beyonder = pathway != null;

        // HOLD means exactly that: park the whole bundle until they have somewhere to put it,
        // rather than applying the item half and quietly binning the rest.
        if (!beyonder && !config.substituteForNonBeyonders()) {
            progress.handedBack = true;
            giveBack.add(returned(bundle));
            player.sendMessage(message(player, "magic.redeem.held_until_awakened"));
            return;
        }

        int needed = beyonder ? api.getActingRequiredForNextSequence(player, pathway) : 0;
        Redemption r = new Redemption(api, capabilities, player, pathway, needed, bundle, progress,
                new ArrayList<>(), giveBack);

        List<PendingItem> items = new ArrayList<>();
        List<RewardGrant> ordered = progress.ordered;

        for (int index = 0; index < ordered.size(); index++) {
            RewardGrant grant = ordered.get(index);
            switch (grant.kind()) {
                case ACTING_PERCENT -> {
                    if (beyonder) {
                        applyActing(r, index, grant);
                    } else {
                        substitute(r, items, index, grant);
                    }
                }
                case COOLDOWN_CREDIT -> {
                    if (beyonder && capabilities.cooldownCredit()) {
                        applyCooldown(r, index, grant);
                    } else if (beyonder) {
                        // Older COI cannot credit cooldowns; pay the equivalent in acting instead.
                        applyActing(r, index, withAmount(grant, config.cooldownSubstitutePercent()));
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
        player.sendMessage(message(player, "magic.redeem.footer"));

        if (ordered.stream().anyMatch(RewardGrant::epic)) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.1f);
        }
    }

    private void applyActing(Redemption r, int index, RewardGrant grant) {
        Player player = r.player();
        if (r.needed() <= 0) {
            // Sequence 0, or an outer pathway that advances by sacrifice rather than acting.
            r.progress().settle(index);
            r.summary().add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        int points = (int) Math.round(r.needed() * grant.amount() / 100.0);
        if (points <= 0) {
            r.progress().settle(index);
            return;
        }

        // Settled first: if the call throws after changing state, the grant must not be re-queued.
        r.progress().settle(index);
        int granted = r.api().grantActing(player, r.pathway(), r.capabilities().rewardSource(), points);
        if (granted <= 0) {
            // Say so rather than dropping it silently - a reward that vanishes reads as a bug.
            r.summary().add(message(player, "magic.redeem.acting_capped"));
            return;
        }

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
            r.summary().add(message(player, "magic.redeem.acting_not_applicable"));
            return;
        }

        long total = r.api().getActingMethodCooldownSeconds(methodId);
        long seconds = Math.round(total * grant.amount() / 100.0);
        if (seconds <= 0) {
            r.progress().settle(index);
            return;
        }

        r.progress().settle(index);
        long credited = r.api().creditActingCooldown(player, methodId, seconds);
        if (credited <= 0) {
            // Their method was already off cooldown, so there was nothing to shave. Paying the
            // acting equivalent instead keeps the largest slice of the loot table from being a
            // coin-flip no-op for anyone who logged off at a natural stopping point.
            r.summary().add(message(player, "magic.redeem.cooldown_ready"));
            applyActing(r, index, withAmount(grant, config.cooldownSubstitutePercent()));
            return;
        }

        long remaining = r.api().getActingCooldownRemaining(player.getUniqueId(), methodId);
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
        if (r.api().getActingSpeedMultiplier(player) >= grant.amount()) {
            r.progress().settle(index);
            // They are already running a stronger buff; overwriting would be a downgrade.
            r.summary().add(message(player, "magic.redeem.buff_kept"));
            return;
        }

        r.progress().settle(index);
        r.api().setActingSpeedMultiplier(player, grant.amount(), grant.intArg() * 1000L);
        r.summary().add(message(player, "magic.redeem.speed_applied",
                "percent", Math.round(grant.amount() * 100),
                "duration", formatDuration(grant.intArg())));
    }

    private void applyItemMultiplier(Redemption r, int index, RewardGrant grant) {
        Player player = r.player();
        if (r.api().getActingItemMultiplier(player) >= grant.amount()) {
            r.progress().settle(index);
            r.summary().add(message(player, "magic.redeem.buff_kept"));
            return;
        }

        r.progress().settle(index);
        r.api().setActingItemMultiplier(player, grant.amount(), grant.intArg() * 1000L);
        r.summary().add(message(player, "magic.redeem.item_mult_applied",
                "multiplier", multiplier(grant.amount()),
                "duration", formatDuration(grant.intArg())));
    }

    private String primaryPathway(CircleOfImaginationAPI api, Player player) {
        if (!api.isBeyonder(player)) {
            return null;
        }

        List<PathwayData> pathways = api.getPathwayData(player);
        return pathways.isEmpty() ? null : pathways.getFirst().name();
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

        int acting = (int) Math.round(config.fallbackBottleActing() * share / 2.0);
        ItemStack bottle = r.api().createActingBottle(Math.max(1, acting));
        if (bottle != null) {
            items.add(new PendingItem(bottle, grant, index));
        } else {
            unavailable(r, index);
        }
    }

    private void buildItem(Redemption r, List<PendingItem> items, int index, RewardGrant grant) {
        RewardItemKind kind = grant.item();
        if (kind == null) {
            unavailable(r, index);
            return;
        }

        CircleOfImaginationAPI api = r.api();
        String tier = grant.strArg() == null ? "small" : grant.strArg();
        int sequence = tokenCeiling(grant);

        ItemStack stack = switch (kind) {
            // The configured value is a percentage like every other amount in the file; resolve it
            // against the player's real bar here. Treating it as a raw point count would make one
            // bottle worth 10% of a Sequence-9 bar and 0.25% of a Sequence-3 one.
            case ACTING_BOTTLE -> api.createActingBottle(bottleActing(r.needed(), grant));
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
            unavailable(r, index);
            return;
        }

        if (grant.count() > 1) {
            stack.setAmount(Math.min(stack.getMaxStackSize(), grant.count()));
        }

        items.add(new PendingItem(stack, grant, index));
    }

    /**
     * Circle of Imagination could not build the item. Re-queuing would fail the same way on every
     * login, so the grant is settled rather than carried into a retry.
     */
    private void unavailable(Redemption r, int index) {
        r.progress().settle(index);
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
        // side taken, and the log line is what an operator reconciles from. Only leftovers that
        // addItem reports after returning normally are re-opened below.
        for (PendingItem item : items) {
            r.progress().settle(item.index());
        }

        Map<Integer, ItemStack> leftovers;
        try {
            leftovers = player.getInventory().addItem(offered);
        } catch (RuntimeException exception) {
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
            } else if (rejected.getAmount() >= item.stack().getAmount()) {
                // Nothing of it went in, so it is owed again until it is dropped or re-queued.
                r.progress().reopen(item.index());
                unplaced.add(item.grant());
                unplacedIndexes.add(i);
            } else {
                // Part of the stack went in. Re-queuing the grant would hand over the whole
                // thing again, so the remainder goes on the floor instead.
                r.progress().partial(item.index(), withCount(item.grant(), rejected.getAmount()));
                partial.add(rejected);
                partialIndexes.add(i);
            }
        }

        if (!leftovers.isEmpty()) {
            Location location = player.getLocation();

            // Whatever could only be partially placed is dropped regardless of the overflow policy.
            for (int p = 0; p < partial.size(); p++) {
                drop(r, items.get(partialIndexes.get(p)), partial.get(p), location);
            }

            if (config.requeueOverflow() && !unplaced.isEmpty()) {
                requeueOverflow(r, items, unplacedIndexes, unplaced);
            } else if (!unplacedIndexes.isEmpty()) {
                for (int index : unplacedIndexes) {
                    drop(r, items.get(index), leftovers.get(index), location);
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
        }
        // Goes back with the rest, in the same step that drops the parked bundle.
        r.giveBack().add(overflow);
    }

    /** Drops a stack and settles its grant, so a later failure does not hand it over again. */
    private void drop(Redemption r, PendingItem item, ItemStack stack, Location location) {
        r.player().getWorld().dropItem(location, stack);
        r.progress().settle(item.index());
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

    /** The bundle to apply, or none, and whether to try again later. */
    private record Taken(RewardBundle bundle, boolean retry) {
    }

    /**
     * Everything one bundle's apply needs, so the per-grant steps do not each take eight arguments.
     */
    private record Redemption(CircleOfImaginationAPI api, CoiCapabilities capabilities, Player player,
                              String pathway, int needed, RewardBundle bundle, Progress progress,
                              List<Component> summary, List<RewardBundle> giveBack) {
    }

    /**
     * An item waiting to be handed over, remembering which grant it settles.
     */
    private record PendingItem(ItemStack stack, RewardGrant grant, int index) {
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
