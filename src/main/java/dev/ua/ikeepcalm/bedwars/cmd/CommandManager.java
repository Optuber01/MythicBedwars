package dev.ua.ikeepcalm.bedwars.cmd;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.cmd.impls.EventCommand;
import dev.ua.ikeepcalm.bedwars.cmd.impls.MinigameSubcommands;
import dev.ua.ikeepcalm.bedwars.domain.reward.RewardConfig;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Router for {@code /mythicbedwars}.
 *
 * <p>Registered in both network roles, but only the role-neutral subcommands live here. Everything
 * that needs MBedwars is held behind {@link #minigame}, which stays {@code null} on an SMP instance
 * so {@link MinigameSubcommands} is never loaded there — see {@link Subcommands} for why the name
 * lookup cannot live on that class.
 *
 * <p><b>Permissions are per subcommand, not on the command.</b> Declaring
 * {@code mythicbedwars.admin} in {@code plugin.yml} would have Bukkit reject an ordinary player
 * before this executor ever ran, which would make {@code /mb event join} — the relog-proof way to
 * sign up when a chat button has expired — unusable by exactly the people it exists for.
 */
public class CommandManager implements CommandExecutor, TabCompleter {

    private final MythicBedwars plugin;

    private final EventCommand event;

    private MinigameSubcommands minigame;

    public CommandManager(MythicBedwars plugin) {
        this.plugin = plugin;
        this.event = new EventCommand(plugin);
    }

    /**
     * @return the shared event branch, so the player-facing {@code /bedwars} can delegate to the
     * same instance rather than standing up a second one
     */
    public EventCommand eventCommand() {
        return event;
    }

    /**
     * Enables the MBedwars-backed subcommands. Called only from the minigame role's bootstrap.
     */
    public void installMinigameSubcommands(MinigameSubcommands minigame) {
        this.minigame = minigame;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            if (doesNotRequireAdmin(sender)) {
                return true;
            }
            sendHelpMessage(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        // The one subcommand ordinary players are meant to reach. EventCommand checks the finer
        // grained permission for each of its own branches.
        if ("event".equals(subcommand)) {
            event.dispatch(sender, args);
            return true;
        }

        if (doesNotRequireAdmin(sender)) {
            return true;
        }

        switch (subcommand) {
            case "toggle" -> handleToggle(sender);
            case "reload" -> handleReload(sender);
            default -> {
                if (!Subcommands.isMinigameOnly(subcommand)) {
                    sendHelpMessage(sender);
                } else if (minigame == null) {
                    sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.minigame_only"));
                } else {
                    minigame.dispatch(sender, args);
                }
            }
        }

        return true;
    }

    private boolean doesNotRequireAdmin(CommandSender sender) {
        if (sender.hasPermission(Subcommands.ADMIN_PERMISSION)) {
            return false;
        }

        sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.no_permission"));
        return true;
    }

    private void handleToggle(CommandSender sender) {
        boolean newState = plugin.getConfigManager().toggleGlobalEnabled();
        plugin.getAudit().emitAdmin(sender, "toggle", AuditOutcome.COMMITTED, null,
                row -> row.put("global_enabled", newState).put("role", plugin.getNetworkRole().name()));
        sender.sendMessage(plugin.getLocaleManager().formatMessage(
                newState ? "magic.commands.global_enabled" : "magic.commands.global_disabled"));
    }

    private void handleReload(CommandSender sender) {
        String rewardsBefore = plugin.getRewardConfig() == null ? null : plugin.getRewardConfig().contentHash();
        String configBefore = plugin.getConfigHash();
        String stage = "config";
        List<String> rearmed;
        try {
            plugin.getConfigManager().loadConfig();
            stage = "locales";
            plugin.getLocaleManager().loadLocales();

            // Reward tuning is the thing an operator is most likely to be iterating on, and load() is
            // idempotent — reporting "reloaded" while still paying out the old loot table is worse than
            // not offering a reload at all.
            stage = "rewards";
            if (plugin.getRewardConfig() != null) {
                plugin.getRewardConfig().load();
            }

            // A repeating task's period is fixed when it is scheduled, so re-reading the config is not
            // enough on its own — anything driven by an interval has to be replaced.
            stage = "tasks";
            rearmed = plugin.reloadScheduledTasks();
        } catch (RuntimeException | LinkageError failure) {
            // Earlier stages have already been applied, so say which one stopped.
            String failedStage = stage;
            plugin.getAudit().emitAdmin(sender, "reload", AuditOutcome.FAILED, "reload_failed",
                    row -> row.risk(AuditRisk.HIGH).put("role", plugin.getNetworkRole().name())
                            .put("stage", failedStage)
                            .put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            throw failure;
        }
        // Hashing reads rewards.yml, so it runs off the main thread and the row is emitted from there.
        // The actor and role are read here because the background task must not touch the sender or
        // plugin state.
        RewardConfig rewards = plugin.getRewardConfig();
        UUID actorId = sender instanceof Entity entity ? entity.getUniqueId() : null;
        String consoleName = actorId == null ? sender.getName() : null;
        String role = plugin.getNetworkRole().name();
        plugin.offMainThread(() -> {
            String rewardsAfter = rewards == null ? null : rewards.refreshHash();
            String configAfter = plugin.refreshConfigHash();
            plugin.getAudit().emitAdmin(actorId, consoleName, "reload", AuditOutcome.COMMITTED, null,
                    row -> row.risk(AuditRisk.HIGH).put("role", role)
                            .put("rewards_sha256_before", rewardsBefore)
                            .put("rewards_sha256_after", rewardsAfter)
                            .put("rewards_changed", rewardsBefore != null && !rewardsBefore.equals(rewardsAfter))
                            .put("config_sha256_before", configBefore)
                            .put("config_sha256_after", configAfter)
                            .put("config_changed", configBefore != null && !configBefore.equals(configAfter))
                            .put("tasks_rearmed", String.join(",", rearmed)));
        });

        sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.config_reloaded"));

        if (!rearmed.isEmpty()) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage(
                    "magic.commands.tasks_rearmed", "tasks", String.join(", ", rearmed)));
        }

        // Worth saying out loud: these look like ordinary settings, and an operator who changes one
        // and sees no effect will reasonably assume the reload failed.
        sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.reload_restart_only"));
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("=== MythicBedwars Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mb toggle - Toggle global functionality", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb reload - Reload config, language and reward files", NamedTextColor.YELLOW));
        event.sendHelp(sender);

        if (minigame != null) {
            minigame.sendHelp(sender);
        } else {
            sender.sendMessage(Component.text("Running in SMP role - Bedwars subcommands are unavailable here.",
                    NamedTextColor.GRAY));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        boolean admin = sender.hasPermission(Subcommands.ADMIN_PERMISSION);

        if (args.length == 1) {
            List<String> available = new ArrayList<>();

            if (admin) {
                available.addAll(Subcommands.SHARED);
                if (minigame != null) {
                    available.addAll(Subcommands.MINIGAME);
                }
            } else if (sender.hasPermission(Subcommands.JOIN_PERMISSION)) {
                available.add("event");
            }

            String prefix = args[0].toLowerCase(Locale.ROOT);
            return available.stream().filter(s -> s.startsWith(prefix)).toList();
        }

        if ("event".equalsIgnoreCase(args[0])) {
            return event.tabComplete(sender, args);
        }

        return admin && minigame != null ? minigame.tabComplete(args) : List.of();
    }
}
