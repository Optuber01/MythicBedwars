package dev.ua.ikeepcalm.bedwars.domain.reward;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardBundle;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisClient;
import dev.ua.ikeepcalm.bedwars.net.transport.RedisKeys;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;

import java.util.List;
import java.util.Optional;
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
     * What happened to an {@link #emit} call.
     */
    public enum EmitResult {
        /** Newly queued. */
        QUEUED,
        /** This player was already paid for this event; nothing was written. */
        DUPLICATE,
        /** Redis was unreachable; nothing was written. */
        UNAVAILABLE
    }

    /**
     * Queues a bundle for later collection. Does Redis I/O — call off the main thread.
     */
    public EmitResult emit(RewardBundle bundle) {
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
            return EmitResult.UNAVAILABLE;
        }

        return result > 0 ? EmitResult.QUEUED : EmitResult.DUPLICATE;
    }

    /**
     * Takes the next bundle for a player, if any. Does Redis I/O.
     */
    public Optional<RewardBundle> poll(UUID playerId) {
        Optional<String> raw = client.lpop(keys.rewardsPending(playerId));
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        try {
            RewardBundle bundle = gson.fromJson(raw.get(), RewardBundle.class);
            if (bundle == null || bundle.schema() != RewardBundle.SCHEMA) {
                quarantine(raw.get(), "unsupported schema");
                return Optional.empty();
            }
            return Optional.of(bundle);
        } catch (JsonSyntaxException exception) {
            quarantine(raw.get(), exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Puts a bundle back at the front, preserving order. Used when the player logs off mid-apply or
     * their inventory could not take the items.
     *
     * @return whether Redis accepted it; {@code false} means the bundle is not queued anywhere
     */
    public boolean returnToQueue(UUID playerId, RewardBundle bundle) {
        return client.lpush(keys.rewardsPending(playerId), gson.toJson(bundle), config.queueTtlSeconds());
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
     * Releases a claim so the bundle can be retried, after a failure that was not the player's fault.
     */
    public boolean releaseClaim(UUID playerId, String eventId) {
        return client.delete(keys.rewardsClaimed(playerId, eventId));
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
     */
    private void quarantine(String payload, String reason) {
        plugin.log("Quarantining unreadable reward payload ({}).", String.valueOf(reason));
        boolean parked = client.rpushCapped(keys.rewardsDeadLetter(), payload, 200);
        try {
            auditQuarantine(payload, reason, parked);
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort.
        }
    }

    /**
     * Records the unreadable payload by hash, never by content. Runs on the drain's async thread,
     * so it touches no Bukkit state.
     */
    private void auditQuarantine(String payload, String reason, boolean parked) {
        BedwarsAuditEmitter.AuditRow row = BedwarsAuditEmitter.AuditRow.of("reward.dead_letter",
                        parked ? AuditOutcome.OBSERVED : AuditOutcome.FAILED)
                .risk(AuditRisk.HIGH)
                .reason(String.valueOf(reason))
                .put("payload_sha256", sha256(payload))
                .put("payload_length", payload == null ? 0 : payload.length())
                .put("parked", parked);
        UUID playerId = playerIdOf(payload);
        if (playerId != null) {
            row.subject(playerId).put("player_id", playerId);
        }
        plugin.getAudit().emit(row);
    }

    private UUID playerIdOf(String payload) {
        try {
            com.google.gson.JsonElement tree = com.google.gson.JsonParser.parseString(payload);
            if (tree.isJsonObject() && tree.getAsJsonObject().has("playerId")) {
                return UUID.fromString(tree.getAsJsonObject().get("playerId").getAsString());
            }
        } catch (RuntimeException ignored) {
            // Unparseable is exactly why it is here.
        }
        return null;
    }

    private static String sha256(String payload) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}
