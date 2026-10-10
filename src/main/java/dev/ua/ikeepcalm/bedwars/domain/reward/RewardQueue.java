package dev.ua.ikeepcalm.bedwars.domain.reward;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The durable handoff between "you earned this on the Bedwars server" and "here it is on the SMP".
 *
 * <p>Guarded at both ends. Emitting checks a per-event set so a duplicate round-end cannot pay
 * twice; claiming uses a set-if-absent marker so a bundle that is popped and then lost to a crash
 * cannot be applied twice either.
 */
public class RewardQueue {

    /**
     * Marks the player as paid and pushes the bundle in one step, so a crash between the two cannot
     * either drop the reward or open the door to paying it again.
     *
     * <p>{@code KEYS[1]} granted-set, {@code KEYS[2]} pending list.
     * {@code ARGV[1]} uuid, {@code ARGV[2]} payload, {@code ARGV[3]} queue ttl, {@code ARGV[4]} cap.
     *
     * <p>Returns the new queue length, or 0 if this player was already paid for this event.
     */
    private static final String EMIT = """
            if redis.call('SADD', KEYS[1], ARGV[1]) == 0 then return 0 end
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            redis.call('RPUSH', KEYS[2], ARGV[2])
            redis.call('LTRIM', KEYS[2], -tonumber(ARGV[4]), -1)
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
            return redis.call('LLEN', KEYS[2])
            """;

    /**
     * Claims with an owner token, for the drain that holds the lease. {@code KEYS[1]} claim marker,
     * {@code KEYS[2]} drain lease, {@code ARGV[1]} token, {@code ARGV[2]} ttl, {@code ARGV[3]} drain.
     * Returns 1 when claimed now or by an earlier try with the same token, 0 when anyone else holds
     * it (including markers written by {@link #claim}), -2 when the lease is not this drain's.
     */
    private static final String CLAIM = """
            if redis.call('GET', KEYS[2]) ~= ARGV[3] then return -2 end
            local held = redis.call('GET', KEYS[1])
            if held == ARGV[1] then return 1 end
            if held then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', tonumber(ARGV[2]))
            return 1
            """;

    /**
     * Pops the next bundle and parks it, with its claim token, in one step: from then until it is
     * applied the bundle is in Redis, not only in memory. Only the drain holding the lease may park,
     * and the lease is extended. {@code KEYS[1]} pending list, {@code KEYS[2]} parked hash,
     * {@code KEYS[3]} drain lease, {@code ARGV[1]} drain, {@code ARGV[2]} token, {@code ARGV[3]} ttl,
     * {@code ARGV[4]} lease ttl, {@code ARGV[5]} now (ms), {@code ARGV[6]} grace (ms).
     *
     * <p>Returns 1 when a bundle is parked, now or earlier, 0 when nothing is pending, 2 when the
     * parked bundle is being applied and its owner has not beat within the grace period, so its
     * drain died (left alone), -2 when the lease is not this drain's or the parked bundle is being
     * applied by an owner that beat within the grace period and may still be alive.
     */
    private static final String PARK = """
            if redis.call('GET', KEYS[3]) ~= ARGV[1] then return -2 end
            redis.call('EXPIRE', KEYS[3], tonumber(ARGV[4]))
            local state = redis.call('HGET', KEYS[2], 'state')
            if state == 'applying' then
              local beat = tonumber(redis.call('HGET', KEYS[2], 'beat') or '0') or 0
              if tonumber(ARGV[5]) - beat > tonumber(ARGV[6]) then return 2 end
              return -2
            end
            if state then return 1 end
            local raw = redis.call('LPOP', KEYS[1])
            if not raw then return 0 end
            redis.call('HSET', KEYS[2], 'state', 'parked', 'token', ARGV[2], 'bundle', raw)
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
            return 1
            """;

    /**
     * Marks the parked bundle as being applied, for good: the grants are not idempotent, so from here
     * a restart must not apply it again, only report it. {@code KEYS[1]} parked hash, {@code KEYS[2]}
     * drain lease, {@code ARGV[1]} drain, {@code ARGV[2]} token, {@code ARGV[3]} now (ms), the first
     * heartbeat. Returns 1 when marked, -2 when the lease or the parked bundle is not this drain's.
     */
    private static final String APPLYING = """
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -2 end
            if redis.call('HGET', KEYS[1], 'token') ~= ARGV[2] then return -2 end
            redis.call('HSET', KEYS[1], 'state', 'applying', 'beat', ARGV[3])
            redis.call('PERSIST', KEYS[1])
            return 1
            """;

    /**
     * Drops the parked bundle and puts back what was handed back, in one step, so a crash cannot
     * leave both or neither. {@code KEYS[1]} parked hash, {@code KEYS[2]} drain lease,
     * {@code KEYS[3]} pending list, {@code ARGV[1]} drain, {@code ARGV[2]} ttl, the rest payloads
     * pushed to the front in the order given. Returns -2 when the lease is not this drain's.
     */
    private static final String UNPARK = """
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -2 end
            for i = 3, #ARGV do redis.call('LPUSH', KEYS[3], ARGV[i]) end
            if #ARGV > 2 then redis.call('EXPIRE', KEYS[3], tonumber(ARGV[2])) end
            return redis.call('DEL', KEYS[1])
            """;

    /**
     * Renews the lease and stamps the parked record with a heartbeat, for a drain that is still
     * alive. {@code KEYS[1]} parked hash, {@code KEYS[2]} drain lease, {@code ARGV[1]} drain,
     * {@code ARGV[2]} lease ttl, {@code ARGV[3]} now (ms). Returns 1 when renewed, -2 when the lease
     * is not this drain's.
     */
    private static final String RENEW = """
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -2 end
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[2]))
            if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('HSET', KEYS[1], 'beat', ARGV[3]) end
            return 1
            """;

    /** How long a drain holds a player; extended each time it parks and by the heartbeat. */
    private static final int LEASE_SECONDS = 120;

    /**
     * How long an {@code applying} record may go without a heartbeat before another server treats
     * its drain as dead. The lease lapsing alone is not enough: the heartbeat can stall briefly.
     */
    private static final long APPLYING_GRACE_MILLIS = 5L * 60 * 1000;

    /** What {@link #park} found. */
    enum ParkStatus {
        PARKED,
        /** Nothing is pending. */
        EMPTY,
        /** No answer from Redis, or another drain holds the player; try again later. */
        UNAVAILABLE
    }

    /** The outcome of a {@link #park}, with the bundle and the token its claim is tried with. */
    record Parked(ParkStatus status, RewardBundle bundle, String token) {
        static final Parked EMPTY = new Parked(ParkStatus.EMPTY, null, null);
        static final Parked UNAVAILABLE = new Parked(ParkStatus.UNAVAILABLE, null, null);
    }

    /** What a {@link #claimAttempt} learned. */
    enum ClaimAttempt {
        CLAIMED,
        /** Somebody else holds the claim; the bundle must be discarded. */
        DUPLICATE,
        /** No answer from Redis; whether the claim landed is unknown until retried with the same token. */
        UNAVAILABLE
    }

    private final MythicBedwars plugin;
    private final RedisClient client;
    private final RedisKeys keys;
    private final RewardConfig config;
    private final Gson gson = new Gson();

    public RewardQueue(MythicBedwars plugin, RedisClient client, RedisKeys keys, RewardConfig config) {
        this.plugin = plugin;
        this.client = client;
        this.keys = keys;
        this.config = config;
    }

    /**
     * Queues a bundle for later collection. Does Redis I/O — call off the main thread.
     *
     * @return whether it was newly queued; {@code false} means this player was already paid
     */
    public boolean emit(RewardBundle bundle) {
        long result = client.evalLong(EMIT,
                List.of(keys.rewardsGranted(bundle.eventId()), keys.rewardsPending(bundle.playerId())),
                List.of(bundle.playerId().toString(),
                        gson.toJson(bundle),
                        Integer.toString(config.queueTtlSeconds()),
                        Integer.toString(config.maxQueuedBundles())),
                -1L);

        if (result < 0) {
            // Redis is down. Nothing was written, so the emit guard is untouched and a later retry
            // (or the reaper) can still pay them.
            plugin.log("Could not queue rewards for {} - Redis unavailable.", bundle.playerName());
            return false;
        }

        return result > 0;
    }

    /**
     * Takes the lease on a player's rewards, so one drain at a time works on them across every server.
     * It lapses on its own if the holder dies. Does Redis I/O.
     *
     * @param drain who is taking it; every later call for this drain passes the same value
     * @return {@code false} if another drain holds it, or Redis did not answer
     */
    boolean lease(UUID playerId, String drain) {
        return client.setIfAbsent(keys.rewardsDrain(playerId), drain, LEASE_SECONDS);
    }

    /**
     * Keeps the lease and the parked record alive while this drain still works on the player. Does
     * Redis I/O.
     *
     * @return {@code false} if the lease is no longer this drain's or Redis did not answer
     */
    boolean renew(UUID playerId, String drain) {
        return client.evalLong(RENEW, List.of(keys.rewardsParked(playerId), keys.rewardsDrain(playerId)),
                List.of(drain, Integer.toString(LEASE_SECONDS), Long.toString(System.currentTimeMillis())), -1L) == 1L;
    }

    /** Gives the lease up, if it is still this drain's. */
    void release(UUID playerId, String drain) {
        client.deleteIfEquals(keys.rewardsDrain(playerId), drain);
    }

    /**
     * Takes the next bundle for a player, if any, parking it in Redis until {@link #unpark}. A bundle
     * already parked, such as one left by a restart, comes back first with the token it was claimed
     * with. One that was being applied when its drain died is moved to the dead letters and reported,
     * never handed out again. Does Redis I/O.
     *
     * @param drain the drain holding the lease
     * @param token the claim token for a bundle parked now
     */
    Parked park(UUID playerId, String drain, String token) {
        String parkedKey = keys.rewardsParked(playerId);
        long result = client.evalLong(PARK,
                List.of(keys.rewardsPending(playerId), parkedKey, keys.rewardsDrain(playerId)),
                List.of(drain, token, Integer.toString(config.queueTtlSeconds()), Integer.toString(LEASE_SECONDS),
                        Long.toString(System.currentTimeMillis()), Long.toString(APPLYING_GRACE_MILLIS)),
                -1L);
        if (result < 0) {
            return Parked.UNAVAILABLE;
        }
        if (result == 0) {
            return Parked.EMPTY;
        }

        Map<String, String> stored = client.hgetAll(parkedKey);
        String raw = stored.get("bundle");
        if (raw == null) {
            // Something is parked, so an empty read means the read failed, not that nothing is owed.
            return Parked.UNAVAILABLE;
        }

        RewardBundle bundle = read(raw);
        if (result == 2 || bundle == null) {
            // Only drop the parked copy once the dead letter is written; it is the only other copy.
            if (!quarantine(raw, result == 2 ? "stuck while applying" : "unsupported schema or unreadable")) {
                return Parked.UNAVAILABLE;
            }
            if (result == 2) {
                warnStuck(bundle, playerId, "It was moved to the dead letters (" + keys.rewardsDeadLetter()
                        + ") for staff to check.");
            }
            return unpark(playerId, drain, List.of()) ? Parked.EMPTY : Parked.UNAVAILABLE;
        }
        return new Parked(ParkStatus.PARKED, bundle, stored.get("token"));
    }

    private void warnStuck(RewardBundle bundle, Object who, String action) {
        plugin.getLogger().warning("Reward bundle " + (bundle == null ? "?" : bundle.eventId()) + " for "
                + (bundle == null ? who : bundle.playerName()) + " was being applied when the server "
                + "stopped, so it may be partly or not at all delivered. It will NOT be paid again. " + action);
    }

    private RewardBundle read(String raw) {
        try {
            RewardBundle bundle = gson.fromJson(raw, RewardBundle.class);
            return bundle == null || bundle.schema() != RewardBundle.SCHEMA ? null : bundle;
        } catch (JsonSyntaxException exception) {
            return null;
        }
    }

    /**
     * Marks the parked bundle as being applied, durably, before anything is given.
     *
     * @return {@code false} if it could not be marked, in which case nothing may be applied
     */
    boolean markApplying(UUID playerId, String drain, String token) {
        return client.evalLong(APPLYING, List.of(keys.rewardsParked(playerId), keys.rewardsDrain(playerId)),
                List.of(drain, token, Long.toString(System.currentTimeMillis())), -1L) == 1L;
    }

    /**
     * Drops the parked bundle once it is dealt with, and puts back what was handed back, in the same
     * step.
     *
     * @param giveBack bundles to return to the front of the queue, in queue order
     * @return {@code false} if Redis did not answer or the lease was lost, leaving it parked
     */
    boolean unpark(UUID playerId, String drain, List<RewardBundle> giveBack) {
        List<String> args = new ArrayList<>(List.of(drain, Integer.toString(config.queueTtlSeconds())));
        // Each push goes to the head of the list, hence the reversal.
        for (RewardBundle bundle : giveBack.reversed()) {
            args.add(gson.toJson(bundle));
        }
        return client.evalLong(UNPARK,
                List.of(keys.rewardsParked(playerId), keys.rewardsDrain(playerId), keys.rewardsPending(playerId)),
                args, -1L) >= 0;
    }

    /**
     * Reports bundles left being applied by a server that stopped, so staff hear about them even if
     * the player never comes back. Does Redis I/O; call once at startup.
     */
    public void reportStuck() {
        for (String key : client.scan(keys.rewardsParkedPattern(), 1000)) {
            Map<String, String> stored = client.hgetAll(key);
            if (!"applying".equals(stored.get("state"))) {
                continue;
            }
            warnStuck(read(String.valueOf(stored.get("bundle"))), key,
                    "Staff: check the player, then delete " + key + ".");
        }
    }

    /**
     * Puts a bundle back at the front, preserving order. Used when the player logs off mid-apply or
     * their inventory could not take the items.
     */
    public void returnToQueue(UUID playerId, RewardBundle bundle) {
        client.lpush(keys.rewardsPending(playerId), gson.toJson(bundle), config.queueTtlSeconds());
    }

    /**
     * Claims the right to apply this bundle.
     *
     * @return {@code false} if somebody already applied it, in which case it must be discarded
     */
    public boolean claim(UUID playerId, String eventId) {
        return client.setIfAbsent(keys.rewardsClaimed(playerId, eventId), "1", config.queueTtlSeconds());
    }

    /**
     * Like {@link #claim}, but an outage is not mistaken for a duplicate. Retrying with the same
     * {@code token} after a lost reply finds its own marker and still counts as claimed. Only the
     * drain holding the lease can claim. Does Redis I/O.
     */
    ClaimAttempt claimAttempt(UUID playerId, String drain, String eventId, String token) {
        long result = client.evalLong(CLAIM,
                List.of(keys.rewardsClaimed(playerId, eventId), keys.rewardsDrain(playerId)),
                List.of(token, Integer.toString(config.queueTtlSeconds()), drain), -1L);
        if (result == 1L) {
            return ClaimAttempt.CLAIMED;
        }
        return result == 0L ? ClaimAttempt.DUPLICATE : ClaimAttempt.UNAVAILABLE;
    }

    /**
     * Releases a claim so the bundle can be retried, after a failure that was not the player's fault.
     */
    public void releaseClaim(UUID playerId, String eventId) {
        client.delete(keys.rewardsClaimed(playerId, eventId));
    }

    /**
     * Reads today's tally without touching it. Does Redis I/O.
     *
     * @return how many bundles this player has already been paid today
     */
    public long bundlesToday(UUID playerId, String day) {
        return client.get(keys.rewardsDailyCount(playerId, day))
                .map(raw -> {
                    try {
                        return Long.parseLong(raw.trim());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /**
     * Counts one bundle against today's tally. Called only once a bundle is genuinely owed, so a
     * duplicate round-end cannot push somebody towards the cap on rewards they were never paid.
     *
     * <p>Does Redis I/O.
     */
    public void recordBundleToday(UUID playerId, String day) {
        client.evalLong("""
                local n = redis.call('INCR', KEYS[1])
                redis.call('EXPIRE', KEYS[1], 172800)
                return n
                """, List.of(keys.rewardsDailyCount(playerId, day)), List.of(), 0L);
    }

    /**
     * Parks a payload nobody can read, rather than dropping it silently or crashing the login.
     *
     * @return {@code false} if it could not be written, in which case the caller must keep its copy
     */
    private boolean quarantine(String payload, String reason) {
        plugin.log("Quarantining unreadable reward payload ({}).", String.valueOf(reason));
        return client.rpushCapped(keys.rewardsDeadLetter(), payload, 200);
    }
}
