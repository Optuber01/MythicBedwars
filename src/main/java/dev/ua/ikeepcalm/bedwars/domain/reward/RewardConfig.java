package dev.ua.ikeepcalm.bedwars.domain.reward;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardEntry;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardItemKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardKind;
import dev.ua.ikeepcalm.bedwars.domain.reward.model.RewardModel.RewardTier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Loads {@code rewards.yml} and parses the tier tree once, rather than re-reading it per match.
 *
 * <p>Unparseable entries are dropped with a warning instead of throwing: a typo in one loot line
 * should cost that line, not the whole reward system.
 */
public class RewardConfig {

    private final MythicBedwars plugin;
    private final Map<RewardTier, TierConfig> tiers = new EnumMap<>(RewardTier.class);

    private FileConfiguration config;
    private Map<Integer, Double> tokenSequenceWeights = Map.of(9, 1.0);

    public RewardConfig(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * One tier's payout: what is always given, and what is rolled on top.
     */
    public record TierConfig(boolean enabled, int rolls, List<RewardEntry> guaranteed, List<RewardEntry> pool) {

        static TierConfig empty() {
            return new TierConfig(false, 0, List.of(), List.of());
        }
    }

    private static Number num(Map<?, ?> raw, String key, Number fallback) {
        Object value = raw.get(key);
        if (value instanceof Number number) {
            return number;
        }
        // A quoted "1.0" in YAML is almost certainly a typo rather than an intent to mean zero.
        if (value != null) {
            try {
                return Double.valueOf(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }

    private TierConfig parseTier(RewardTier tier) {
        String path = "rewards.tiers." + tier.name().toLowerCase(Locale.ROOT).replace('_', '-');
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            return TierConfig.empty();
        }

        return new TierConfig(
                section.getBoolean("enabled", true),
                section.getInt("rolls", 0),
                parseEntries(section, "guaranteed"),
                parseEntries(section, "pool"));
    }

    private List<RewardEntry> parseEntries(ConfigurationSection section, String key) {
        List<RewardEntry> entries = new ArrayList<>();

        for (Map<?, ?> raw : section.getMapList(key)) {
            RewardEntry entry = parseEntry(raw);
            if (entry != null) {
                entries.add(entry);
            }
        }

        return List.copyOf(entries);
    }

    private static boolean bool(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static String str(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Larger than any real rewards.yml; past it the file is not read just to be hashed. */
    private static final long MAX_HASHED_BYTES = 4L * 1024 * 1024;

    private volatile String contentHash;

    /**
     * @return the SHA-256 of rewards.yml as last hashed by {@link #refreshHash}, or {@code null} before that
     */
    public String contentHash() {
        return contentHash;
    }

    /**
     * Hashes rewards.yml as it is now and remembers the result. Reads the file, so call off the main
     * thread.
     *
     * @return the SHA-256, {@code "oversized"} or {@code "unreadable"}
     */
    public String refreshHash() {
        String hash = hashOf(new File(plugin.getDataFolder(), "rewards.yml"));
        contentHash = hash;
        return hash;
    }

    /**
     * Reads the whole file, so call off the main thread.
     *
     * @return the SHA-256 of {@code file}, {@code "oversized"} or {@code "unreadable"}
     */
    public static String hashOf(File file) {
        try {
            if (file.length() > MAX_HASHED_BYTES) {
                return "oversized";
            }
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(file.toPath()));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            return "unreadable";
        }
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "rewards.yml");
        if (!file.exists()) {
            plugin.saveResource("rewards.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);

        // Before the defaults layer goes on, while isSet() still means "actually on disk".
        backfill(file);

        // Bundled defaults back the on-disk copy, so a config written before a new key existed still
        // resolves it rather than silently behaving as if the feature were switched off.
        java.io.InputStream bundled = plugin.getResource("rewards.yml");
        if (bundled != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(bundled, java.nio.charset.StandardCharsets.UTF_8)));
        }

        tiers.clear();
        for (RewardTier tier : RewardTier.values()) {
            tiers.put(tier, parseTier(tier));
        }

        // Parsed once: rolled per token, and the backing configuration is not thread-safe.
        tokenSequenceWeights = Map.copyOf(parseTokenSequenceWeights());

        plugin.log("Loaded reward tiers: {}", tiers.entrySet().stream()
                .filter(e -> e.getValue().enabled())
                .map(e -> e.getKey().name().toLowerCase(Locale.ROOT))
                .toList());
    }

    /**
     * The AFK threshold this file was written for no longer means what it used to.
     *
     * <p>{@code min-participation-ratio} used to be measured against wall-clock match length with
     * movement as the only signal, so {@code 0.25} was a plausible number. It is now measured
     * against the seconds a player was actually in play, and rotation, client input and recent
     * actions all count — which makes the old value a denial of everybody who defended a base
     * instead of pushing. An admin who never touched it should not have to discover that; one who
     * chose their own number keeps it.
     */
    private void backfill(File file) {
        List<String> changes = new ArrayList<>();

        String ratio = "rewards.eligibility.min-participation-ratio";
        if (config.isSet(ratio) && Math.abs(config.getDouble(ratio) - 0.25) < 1e-9) {
            config.set(ratio, 0.10);
            config.setComments(ratio, List.of(
                    "Share of the time a player was *in play* (not of the whole match) that they must",
                    "have looked active for. Movement, rotation, any client input in the last 10s and",
                    "anything they did in the last minute all count, so only an unattended body is",
                    "below this."));
            changes.add(ratio + ": 0.25 -> 0.1");
        }

        String floor = "rewards.eligibility.participation-scale-floor";
        if (!config.isSet(floor)) {
            config.set(floor, 0.5);
            config.setComments(floor, List.of(
                    "Above the threshold the participation grant scales by the ratio, but never below",
                    "this floor - a quiet match is worth less, not worth nothing."));
            changes.add("added " + floor + ": 0.5");
        }

        if (changes.isEmpty()) {
            return;
        }

        try {
            config.save(file);
            changes.forEach(change -> plugin.getLogger().info("rewards.yml: " + change));
        } catch (java.io.IOException exception) {
            // The values are already live in memory; only the file is stale. Not worth failing the
            // load over, but the admin should know why the file does not match what is running.
            plugin.getLogger().warning("Could not update rewards.yml: " + exception.getMessage());
        }
    }

    private RewardEntry parseEntry(Map<?, ?> raw) {
        RewardKind kind = parseEnum(RewardKind.class, str(raw, "kind"));
        if (kind == null) {
            plugin.log("Ignoring reward entry with unknown kind: {}", raw);
            return null;
        }

        RewardItemKind item = parseEnum(RewardItemKind.class, str(raw, "item"));
        if (kind == RewardKind.ITEM && item == null) {
            plugin.log("Ignoring ITEM reward entry with unknown item: {}", raw);
            return null;
        }

        return new RewardEntry(
                kind,
                num(raw, "weight", 1).intValue(),
                item,
                num(raw, "min-percent", 0).doubleValue(),
                num(raw, "max-percent", 0).doubleValue(),
                num(raw, "min-value", 0).doubleValue(),
                num(raw, "max-value", 0).doubleValue(),
                num(raw, "duration-seconds", 0).intValue(),
                num(raw, "amount", 1).intValue(),
                Math.max(1, num(raw, "count", 1).intValue()),
                bool(raw, "epic"),
                str(raw, "tier"));
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public TierConfig tier(RewardTier tier) {
        return tiers.getOrDefault(tier, TierConfig.empty());
    }

    public boolean isEnabled() {
        return config != null && config.getBoolean("rewards.enabled", true);
    }

    public int queueTtlSeconds() {
        return config.getInt("rewards.queue-ttl-days", 30) * 86_400;
    }

    public int maxQueuedBundles() {
        return config.getInt("rewards.max-queued-bundles", 10);
    }

    public String actingSourceName() {
        return config.getString("rewards.acting-source", "EVENT");
    }

    /**
     * The weighted distribution an exchange token's power ceiling is drawn from.
     *
     * <p>Keys are Circle of Imagination sequence numbers, where <b>lower is stronger</b>. The shipped
     * weights lean hard on the weak end so a token is usually a modest prize, while leaving a thin
     * tail into Sequence 4-1 — a token that could never be more than the weakest tier reads as an
     * insult, and the occasional real one is what makes the line worth wanting.
     *
     * @return sequence to relative weight, never empty (falls back to weakest-only)
     */
    public Map<Integer, Double> tokenSequenceWeights() {
        return tokenSequenceWeights;
    }

    private Map<Integer, Double> parseTokenSequenceWeights() {
        Map<Integer, Double> weights = new java.util.LinkedHashMap<>();

        ConfigurationSection section = config.getConfigurationSection("rewards.token-sequences");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int sequence;
                try {
                    sequence = Integer.parseInt(key.trim());
                } catch (NumberFormatException e) {
                    plugin.log("Ignoring non-numeric token sequence key: {}", key);
                    continue;
                }

                // 1 is the strongest a token may reach. 0 would mean "anything at all", and it is
                // also the value a legacy bundle decodes to, so it is deliberately not accepted.
                if (sequence < 1 || sequence > 9) {
                    plugin.log("Ignoring out-of-range token sequence {} (expected 1-9).", sequence);
                    continue;
                }

                double weight = section.getDouble(key, 0.0);
                if (weight > 0) {
                    weights.put(sequence, weight);
                }
            }
        }

        if (weights.isEmpty()) {
            // Weakest tier only: the historical behaviour, and safe rather than generous.
            weights.put(9, 1.0);
        }

        return weights;
    }

    /**
     * @return above this many bundles in a day, only the participation tier is paid
     */
    public int dailyDowngradeThreshold() {
        return config.getInt("rewards.max-bundles-per-day", 8);
    }

    /**
     * @return above this many bundles in a day, nothing is paid at all
     */
    public int dailyDropThreshold() {
        return config.getInt("rewards.max-bundles-per-day-hard",
                Math.max(1, dailyDowngradeThreshold()) * 2);
    }

    public boolean substituteForNonBeyonders() {
        return !"HOLD".equalsIgnoreCase(config.getString("rewards.non-beyonder-policy", "SUBSTITUTE"));
    }

    public int fallbackBottleActing() {
        return config.getInt("rewards.fallback-bottle-acting", 300);
    }

    public double cooldownSubstitutePercent() {
        return config.getDouble("rewards.cooldown-substitute-percent", 2.0);
    }

    public boolean requeueOverflow() {
        return !"DROP".equalsIgnoreCase(config.getString("rewards.item-overflow", "REQUEUE"));
    }

    public int minPlayTimeSeconds() {
        return config.getInt("rewards.eligibility.min-play-time-seconds", 120);
    }

    public int minActions() {
        return config.getInt("rewards.eligibility.min-actions", 1);
    }

    public int minPlayers() {
        return config.getInt("rewards.eligibility.min-players", 4);
    }

    /**
     * @return the share of their time in play a player must have looked active for. Low on purpose:
     * the sampler credits rotation, client input and recent actions as well as movement, so anything
     * above a floor like this is a body nobody is sitting behind rather than a quiet defender.
     */
    public double minParticipationRatio() {
        return config.getDouble("rewards.eligibility.min-participation-ratio", 0.10);
    }

    /**
     * @return the smallest multiplier the participation grant may be scaled to
     */
    public double participationScaleFloor() {
        return Math.min(1.0, Math.max(0.0, config.getDouble("rewards.eligibility.participation-scale-floor", 0.5)));
    }

    public boolean denyRageQuit() {
        return config.getBoolean("rewards.eligibility.deny-rage-quit", true);
    }

    public int redeemDelayTicks() {
        return config.getInt("rewards.redeem.delay-ticks", 60);
    }

    public int maxBundlesPerJoin() {
        return config.getInt("rewards.redeem.max-bundles-per-join", 5);
    }

    public double mvpMinScore() {
        return config.getDouble("rewards.tiers.mvp.min-score", 3.0);
    }

    public double mvpScore(String key, double fallback) {
        return config.getDouble("rewards.tiers.mvp.score." + key, fallback);
    }
}
