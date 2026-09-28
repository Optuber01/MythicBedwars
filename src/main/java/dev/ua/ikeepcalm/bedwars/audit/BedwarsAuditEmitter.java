package dev.ua.ikeepcalm.bedwars.audit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditPrivacy;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditProducer;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Best-effort bridge to the optional shared Mysterria audit ledger.
 *
 * <p>Every call is a no-op when the client failed to initialise, and every failure inside the client
 * is swallowed: audit delivery must never change what a player receives or what a command does.
 * Emitting is safe from any thread as long as the metadata holds plain values; the helpers that read
 * Bukkit state ({@link #putItem}, {@link #putLocation}) must run on the main thread.
 */
public final class BedwarsAuditEmitter implements AutoCloseable {

    public static final String PRODUCER_ID = "mythicbedwars";
    private static final int MAX_TEXT = 256;
    private static final int MAX_LONG_TEXT = 1_024;
    private static final int MAX_KEYS = 48;
    private static final NamespacedKey ITEM_UUID = NamespacedKey.fromString("circleofimagination:item_uuid");
    private static final NamespacedKey ITEM_PARENT = NamespacedKey.fromString("circleofimagination:item_parent");

    /** Null when the audit client failed to initialise; every call is then a no-op. */
    private final AuditProducer producer;

    public BedwarsAuditEmitter(JavaPlugin plugin) {
        this.producer = createProducer(plugin);
    }

    private static AuditProducer createProducer(JavaPlugin plugin) {
        try {
            return AuditProducer.create(plugin.getDataFolder().toPath().toAbsolutePath().getParent()
                            .resolve("mysterria-audit-spool"),
                    PRODUCER_ID, plugin.getPluginMeta().getVersion());
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("Audit client unavailable; MythicBedwars audit events are disabled: " + failure);
            return null;
        }
    }

    /**
     * The correlation shared by every row about one player's reward for one match, on both servers.
     * Suffixes such as {@code :overflow} are stripped so a re-queued remainder stays in the same chain.
     */
    public static UUID bundleCorrelation(String eventId, UUID playerId) {
        String base = eventId == null ? "" : eventId;
        int colon = base.indexOf(':');
        if (colon >= 0) {
            base = base.substring(0, colon);
        }
        return UUID.nameUUIDFromBytes(("mythicbedwars:bundle:" + base + ":" + playerId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The correlation shared by every row about one cross-server event. */
    public static UUID eventCorrelation(String eventId) {
        return eventId == null ? UUID.randomUUID()
                : UUID.nameUUIDFromBytes(("mythicbedwars:event:" + eventId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Emits one row. The row's operation is appended to the {@code mythicbedwars.} prefix.
     */
    public void emit(AuditRow row) {
        if (producer == null || row == null || row.operation() == null || row.operation().isBlank()) {
            return;
        }
        try {
            producer.emit(PRODUCER_ID + "." + row.operation(), row.outcome(), row.risk(),
                    AuditPrivacy.STAFF_RESTRICTED, row.correlationId(), bounded(row.businessId(), 128),
                    row.actorId(), row.subjectId(), row.targetId(),
                    row.reason() == null ? null : bounded(row.reason(), MAX_TEXT),
                    boundedMetadata(row.metadata()));
        } catch (RuntimeException | LinkageError failure) {
            // Audit delivery is best effort and must never gate gameplay or persistence.
            recordFailure();
        }
    }

    private void recordFailure() {
        try {
            producer.recordFailure();
        } catch (RuntimeException | LinkageError ignored) {
            // Failure accounting is itself best effort.
        }
    }

    /**
     * Adds item context: material, amount, and the Circle of Imagination item UUIDs when stamped.
     * Main thread only. Never throws.
     */
    public static void putItem(Map<String, Object> metadata, ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            metadata.put("material", stack.getType().name());
            metadata.put("amount", stack.getAmount());
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                return;
            }
            String itemUuid = readString(meta, ITEM_UUID);
            if (itemUuid != null) {
                metadata.put("item_uuid", itemUuid);
            }
            String parent = readString(meta, ITEM_PARENT);
            if (parent != null) {
                metadata.put("parent_item_uuid", parent);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Item context is optional.
        }
    }

    private static String readString(ItemMeta meta, NamespacedKey key) {
        if (key == null || !meta.getPersistentDataContainer().has(key, PersistentDataType.STRING)) {
            return null;
        }
        String value = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return value == null || value.isBlank() ? null : value;
    }

    /** Adds {@code world}/{@code x}/{@code y}/{@code z}. Main thread only. Never throws. */
    public static void putLocation(Map<String, Object> metadata, Location location) {
        if (location == null) {
            return;
        }
        try {
            if (location.getWorld() != null) {
                metadata.put("world", location.getWorld().getName());
            }
            metadata.put("x", location.getBlockX());
            metadata.put("y", location.getBlockY());
            metadata.put("z", location.getBlockZ());
        } catch (RuntimeException | LinkageError ignored) {
            // Location context is optional.
        }
    }

    private static Map<String, Object> boundedMetadata(Map<String, ?> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, ?> entry : metadata.entrySet()) {
                if (result.size() >= MAX_KEYS) {
                    break;
                }
                String key = entry.getKey();
                Object value = entry.getValue();
                if (key == null || key.isBlank() || value == null) {
                    continue;
                }
                result.put(bounded(key, 64), boundedValue(key, value));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object boundedValue(String key, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        int limit = key.endsWith("_list") || key.equals("grants") || key.equals("remainder")
                ? MAX_LONG_TEXT : MAX_TEXT;
        return bounded(value instanceof String text ? text : String.valueOf(value), limit);
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, limit));
    }

    @Override
    public void close() {
        if (producer == null) {
            return;
        }
        try {
            producer.close();
        } catch (RuntimeException | LinkageError ignored) {
            // Shutdown must continue even if the audit client cannot flush.
        }
    }

    /**
     * One audit row, built fluently. Metadata must only hold plain values (strings, numbers,
     * booleans) once the row leaves the main thread; {@link #put} stores UUIDs as strings.
     */
    public static final class AuditRow {
        private final String operation;
        private final AuditOutcome outcome;
        private AuditRisk risk = AuditRisk.NORMAL;
        private UUID correlationId;
        private String businessId;
        private UUID actorId;
        private UUID subjectId;
        private UUID targetId;
        private String reason;
        private final Map<String, Object> metadata = new LinkedHashMap<>();

        private AuditRow(String operation, AuditOutcome outcome) {
            this.operation = operation;
            this.outcome = outcome;
        }

        public static AuditRow of(String operation, AuditOutcome outcome) {
            return new AuditRow(operation, outcome);
        }

        public AuditRow risk(AuditRisk value) {
            this.risk = value;
            return this;
        }

        public AuditRow correlation(UUID value) {
            this.correlationId = value;
            return this;
        }

        public AuditRow business(String value) {
            this.businessId = value;
            return this;
        }

        public AuditRow actor(UUID value) {
            this.actorId = value;
            return this;
        }

        public AuditRow subject(UUID value) {
            this.subjectId = value;
            return this;
        }

        public AuditRow target(UUID value) {
            this.targetId = value;
            return this;
        }

        /** Sets the envelope reason and mirrors it into {@code reason} metadata. */
        public AuditRow reason(String value) {
            this.reason = value;
            if (value != null) {
                metadata.put("reason", value);
            }
            return this;
        }

        public AuditRow put(String key, Object value) {
            if (key != null && value != null) {
                metadata.put(key, value instanceof UUID uuid ? uuid.toString() : value);
            }
            return this;
        }

        public Map<String, Object> metadata() {
            return metadata;
        }

        String operation() {
            return operation;
        }

        AuditOutcome outcome() {
            return outcome;
        }

        AuditRisk risk() {
            return risk;
        }

        UUID correlationId() {
            return correlationId == null ? UUID.randomUUID() : correlationId;
        }

        String businessId() {
            return businessId;
        }

        UUID actorId() {
            return actorId;
        }

        UUID subjectId() {
            return subjectId;
        }

        UUID targetId() {
            return targetId;
        }

        String reason() {
            return reason;
        }
    }
}
