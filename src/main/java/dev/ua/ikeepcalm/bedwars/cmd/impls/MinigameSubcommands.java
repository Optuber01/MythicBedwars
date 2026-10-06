package dev.ua.ikeepcalm.bedwars.cmd.impls;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The {@code /mb} subcommands that need MBedwars.
 *
 * <p>Split out of {@link dev.ua.ikeepcalm.bedwars.cmd.CommandManager} so this class — and
 * everything it reaches, including {@link VotingDebugCommand} and the arena/statistics managers — is
 * only ever loaded on a {@link dev.ua.ikeepcalm.bedwars.config.NetworkRole#MINIGAME} instance.
 */
public class MinigameSubcommands {

    private final MythicBedwars plugin;

    public MinigameSubcommands(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * @param args the full argument array, with the subcommand still at index 0
     */
    public void dispatch(CommandSender sender, String[] args) {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (args[0].toLowerCase()) {
            case "stats" -> handleStats(sender);
            case "arena" -> handleArenaCommand(sender, rest);
            case "balance" -> handleBalanceCommand(sender, rest);
            case "pathways" -> handlePathwaysCommand(sender, rest);
            case "voting" -> new VotingDebugCommand(plugin).execute(sender, args);
            default -> sendHelp(sender);
        }
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/mb stats - View pathway statistics", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb arena <arena> <enable/disable> - Toggle per arena", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb balance [report|info] - Toggle/view pathway balancing", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb pathways [enable|disable] <pathway> - Manage pathways", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting [status|force|test|clear] - Voting diagnostics", NamedTextColor.YELLOW));
    }

    public List<String> tabComplete(String[] args) {
        if ("voting".equalsIgnoreCase(args[0])) {
            return new VotingDebugCommand(plugin).tabComplete(args);
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "arena" -> {
                    return arenaNames().stream()
                            .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "balance" -> {
                    return Stream.of("report", "info")
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "pathways" -> {
                    return Stream.of("enable", "disable")
                            .filter(s -> s.startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
            }
        }

        if (args.length == 3) {
            if ("arena".equals(args[0])) {
                return Stream.of("enable", "disable")
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if ("pathways".equals(args[0])) {
                return plugin.getAvailablePathways().stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        return List.of();
    }

    /**
     * Kept here rather than on the plugin class so the arena lookup - and with it every
     * {@code de.marcely.bedwars} reference - stays inside a minigame-only class.
     */
    private List<String> arenaNames() {
        return BedwarsAPI.getGameAPI().getArenas().stream()
                .map(Arena::getName)
                .collect(Collectors.toList());
    }

    private void handleStats(CommandSender sender) {
        var stats = plugin.getStatisticsManager().getPathwayStatistics();
        // Printed to the sender, not the console - the docs used to claim otherwise.
        sender.sendMessage(Component.text("=== MythicBedwars Statistics ===", NamedTextColor.GOLD));

        stats.forEach((pathway, data) -> {
            double winRate = data.totalGames > 0 ? (double) data.wins / data.totalGames * 100 : 0;
            sender.sendMessage(Component.text(String.format("%s: %d wins, %d losses (%.1f%% win rate)",
                    pathway, data.wins, data.losses, winRate), NamedTextColor.YELLOW));
        });
    }

    private void handleArenaCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /mythicbedwars arena <arena> <enable/disable>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[0];
        boolean enable = "enable".equalsIgnoreCase(args[1]);

        plugin.getConfigManager().setArenaEnabled(arenaName, enable);
        plugin.getAudit().emitAdmin(sender, enable ? "arena.enable" : "arena.disable", AuditOutcome.COMMITTED, null,
                row -> row.put("arena", arenaName).put("action_arg", args[1]).put("enabled_after", enable));
        sender.sendMessage(plugin.getLocaleManager().formatMessage(
                enable ? "magic.commands.arena_enabled" : "magic.commands.arena_disabled",
                "arena", arenaName));
    }

    private void handleBalanceCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            boolean current = plugin.getConfigManager().isPathwayBalancingEnabled();
            plugin.getConfigManager().getConfig().set("pathways.auto-balance", !current);
            plugin.saveConfig();
            plugin.getAudit().emitAdmin(sender, "balance.toggle", AuditOutcome.COMMITTED, null,
                    row -> row.put("auto_balance_before", current).put("auto_balance_after", !current));

            sender.sendMessage(plugin.getLocaleManager().formatMessage(
                    current ? "magic.commands.balance_disabled" : "magic.commands.balance_enabled"));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "report" -> {
                plugin.getPathwayBalancer().printBalanceReport();
                sender.sendMessage(plugin.getLocaleManager().formatMessage(
                        "magic.commands.balance_report_printed"));
            }
            case "info" -> {
                boolean enabled = plugin.getConfigManager().isPathwayBalancingEnabled();
                double threshold = plugin.getConfigManager().getBalanceThreshold();
                int minGames = plugin.getConfigManager().getMinGamesForBalance();

                sender.sendMessage(Component.text("=== Pathway Balancing Info ===", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("Status: " + (enabled ? "Enabled" : "Disabled"),
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
                sender.sendMessage(Component.text("Balance Threshold: ±" + (threshold * 100) + "%", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Min Games for Balance: " + minGames, NamedTextColor.YELLOW));
            }
            default -> sender.sendMessage(Component.text("Usage: /mb balance [report|info]", NamedTextColor.RED));
        }
    }

    private void handlePathwaysCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            var disabledPathways = plugin.getConfigManager().getDisabledPathways();
            sender.sendMessage(Component.text("=== Pathway Settings ===", NamedTextColor.GOLD));

            if (disabledPathways.isEmpty()) {
                sender.sendMessage(Component.text("All pathways are enabled", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Disabled pathways: " + String.join(", ", disabledPathways), NamedTextColor.RED));
            }
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /mb pathways <enable|disable> <pathway>", NamedTextColor.RED));
            return;
        }

        String action = args[0].toLowerCase();
        String pathway = args[1];

        List<String> disabledPathways = new ArrayList<>(plugin.getConfigManager().getDisabledPathways());

        switch (action) {
            case "enable" -> {
                if (disabledPathways.remove(pathway)) {
                    plugin.getConfigManager().getConfig().set("pathways.disabled", disabledPathways);
                    plugin.saveConfig();
                    plugin.getAudit().emitAdmin(sender, "pathways.enable", AuditOutcome.COMMITTED, null,
                            row -> row.put("pathway", pathway));
                    sender.sendMessage(plugin.getLocaleManager().formatMessage(
                            "magic.commands.pathway_enabled", "pathway", pathway));
                } else {
                    plugin.getAudit().emitAdmin(sender, "pathways.enable", AuditOutcome.DENIED, "already_enabled",
                            row -> row.put("pathway", pathway));
                    sender.sendMessage(plugin.getLocaleManager().formatMessage(
                            "magic.commands.pathway_already_enabled", "pathway", pathway));
                }
            }
            case "disable" -> {
                if (!disabledPathways.contains(pathway)) {
                    disabledPathways.add(pathway);
                    plugin.getConfigManager().getConfig().set("pathways.disabled", disabledPathways);
                    plugin.saveConfig();
                    plugin.getAudit().emitAdmin(sender, "pathways.disable", AuditOutcome.COMMITTED, null,
                            row -> row.put("pathway", pathway));
                    sender.sendMessage(plugin.getLocaleManager().formatMessage(
                            "magic.commands.pathway_disabled", "pathway", pathway));
                } else {
                    plugin.getAudit().emitAdmin(sender, "pathways.disable", AuditOutcome.DENIED, "already_disabled",
                            row -> row.put("pathway", pathway));
                    sender.sendMessage(plugin.getLocaleManager().formatMessage(
                            "magic.commands.pathway_already_disabled", "pathway", pathway));
                }
            }
            default ->
                    sender.sendMessage(Component.text("Usage: /mb pathways <enable|disable> <pathway>", NamedTextColor.RED));
        }
    }
}
