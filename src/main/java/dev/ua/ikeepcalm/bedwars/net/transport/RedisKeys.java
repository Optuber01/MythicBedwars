package dev.ua.ikeepcalm.bedwars.net.transport;

import dev.ua.ikeepcalm.bedwars.config.NetworkRole;

import java.util.Locale;

/**
 * Every Redis key and channel this plugin touches, in one place.
 *
 * <p>All of them sit under a configurable namespace (default {@code mythicbedwars}), deliberately
 * distinct from Circle of Imagination's own {@code coi} prefix so both plugins can share one Redis
 * instance without colliding.
 */
public final class RedisKeys {

    private final String namespace;

    public RedisKeys(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Control channel, keyed by the role that <em>consumes</em> it, so the direction of any message
     * is obvious from where it was published.
     */
    public String channel(NetworkRole consumer) {
        return key("ch", consumer.name().toLowerCase(Locale.ROOT));
    }

    /** Liveness key for one instance. Short TTL; absence means "gone". */
    public String heartbeat(String serverId) {
        return key("hb", serverId);
    }

    /**
     * Set of every server id that has ever heartbeated.
     *
     * <p>Exists so liveness can be read with one SMEMBERS plus one MGET instead of a SCAN over a
     * Redis this plugin shares with others. Members whose heartbeat key has expired are pruned on
     * read, so the set converges on the live set without needing a clean shutdown.
     */
    public String heartbeatIndex() {
        // Deliberately not key("hb", "index"): a server whose id happened to be "index" would then
        // share this key, and the MGET over heartbeats would hit a SET and fail with WRONGTYPE -
        // reporting no live peers at all.
        return key("hbindex");
    }

    /**
     * @return the server id embedded in a heartbeat key, or {@code null} if it is not one
     */
    public String serverIdFromHeartbeat(String heartbeatKey) {
        String prefix = key("hb", "");
        if (heartbeatKey == null || !heartbeatKey.startsWith(prefix)) {
            return null;
        }

        String id = heartbeatKey.substring(prefix.length());
        return id.isEmpty() || id.equals("index") ? null : id;
    }

    /** SCAN pattern matching every instance's heartbeat. */
    public String heartbeatPattern() {
        return key("hb", "*");
    }

    /** Holds the id of the one event allowed to be in flight network-wide. */
    public String activeEvent() {
        return key("evt", "active");
    }

    /** Durable state for an event. Pub/sub can drop a message; this hash cannot. */
    public String event(String eventId) {
        return key("evt", eventId);
    }

    /** Claimed by whichever minigame server wins the race to host. */
    public String eventHost(String eventId) {
        return key("evt", eventId, "host");
    }

    /** Authoritative set of signed-up player UUIDs. */
    public String eventRoster(String eventId) {
        return key("evt", eventId, "roster");
    }

    /** Subset of the roster that actually reached the arena. */
    public String eventArrived(String eventId) {
        return key("evt", eventId, "arrived");
    }

    /** uuid → outcome, so a return survives a dropped message or a lost connection. */
    /**
     * Per-player pointer at an outcome waiting to be delivered, as {@code <eventId>:<outcome>}.
     *
     * <p>The per-event hash is the authoritative record, but it cannot be found from a player alone —
     * and a survival server that restarted has no idea which event to look under. This key is what
     * makes the outcome discoverable at login, which is the whole point of recording it durably.
     */
    public String playerReturn(java.util.UUID playerId) {
        return key("ret", playerId.toString());
    }

    public String eventPendingReturn(String eventId) {
        return key("evt", eventId, "pending-return");
    }

    /**
     * Bundles waiting for a player to come back and collect them.
     */
    public String rewardsPending(java.util.UUID playerId) {
        return key("rewards", "pending", playerId.toString());
    }

    /**
     * The bundle popped for a player, kept until it is applied, so it survives a restart.
     */
    public String rewardsParked(java.util.UUID playerId) {
        return key("rewards", "parked", playerId.toString());
    }

    /**
     * Every parked bundle, for the startup check.
     */
    public String rewardsParkedPattern() {
        return key("rewards", "parked", "*");
    }

    /**
     * Who is draining this player's rewards right now, on any server.
     */
    public String rewardsDrain(java.util.UUID playerId) {
        return key("rewards", "drain", playerId.toString());
    }

    /**
     * Who has already been paid for an event; the emit-side guard against paying twice.
     */
    public String rewardsGranted(String eventId) {
        return key("rewards", "granted", eventId);
    }

    /**
     * Marks a bundle as applied; the redeem-side guard.
     */
    public String rewardsClaimed(java.util.UUID playerId, String eventId) {
        return key("rewards", "claimed", playerId.toString(), eventId);
    }

    /**
     * Per-day payout counter, for the anti-farming limit.
     */
    public String rewardsDailyCount(java.util.UUID playerId, String day) {
        return key("rewards", "count", playerId.toString(), day);
    }

    /**
     * Payloads that could not be read, kept for inspection rather than dropped.
     */
    public String rewardsDeadLetter() {
        return key("rewards", "dlq");
    }

    /** Short mutex around the decide-and-propose critical section. */
    public String proposeLock() {
        return key("lock", "propose");
    }

    /** Present while a fresh event may not be proposed yet. */
    public String cooldown() {
        return key("cooldown");
    }

    /**
     * Present for the rest of the current scheduled-event window, once it has been used.
     *
     * <p>Held in Redis rather than in memory so the schedule survives a restart and cannot be
     * doubled by a second survival node: claiming it is how a server wins the right to run the
     * event for this window, and its TTL is the interval itself.
     */
    public String scheduleWindow() {
        return key("schedule", "window");
    }

    /**
     * @return the namespace prefix itself, for diagnostics
     */
    public String namespace() {
        return namespace;
    }

    private String key(String... parts) {
        StringBuilder sb = new StringBuilder(namespace);
        for (String part : parts) {
            sb.append(':').append(part);
        }
        return sb.toString();
    }
}
