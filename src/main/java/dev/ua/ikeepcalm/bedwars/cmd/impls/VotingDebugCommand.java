package dev.ua.ikeepcalm.bedwars.cmd.impls;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.MagicMode;
import dev.ua.ikeepcalm.bedwars.domain.voting.model.VotingSession;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public class VotingDebugCommand {

    private final MythicBedwars plugin;

    public VotingDebugCommand(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "status" -> showVotingStatus(sender);
            case "force" -> handleForce(sender, args);
            case "test" -> handleTest(sender);
            case "clear" -> handleClear(sender, args);
            default -> sendUsage(sender);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("=== Voting Debug Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mb voting status - Show voting status for all arenas", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting force <arena> <team|individual|off> - Force the round's magic mode", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting test - Test voting in current arena", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb voting clear <arena> - Clear voting data", NamedTextColor.YELLOW));
    }

    private void showVotingStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== Voting Status ===", NamedTextColor.GOLD));

        boolean votingEnabled = plugin.getConfigManager().isVotingEnabled();
        sender.sendMessage(Component.text("Voting System: " + (votingEnabled ? "ENABLED" : "DISABLED"),
                votingEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));

        for (Arena arena : BedwarsAPI.getGameAPI().getArenas()) {
            String arenaName = arena.getName();
            boolean hasVoting = plugin.getVotingManager().hasActiveVoting(arenaName);
            MagicMode mode = plugin.getVotingManager().getMagicMode(arenaName);
            VotingSession session = plugin.getVotingManager().getVotingSession(arenaName);

            sender.sendMessage(Component.text("\n" + arenaName + ":", NamedTextColor.AQUA));
            sender.sendMessage(Component.text("  Status: " + arena.getStatus(), NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Active Voting: " + (hasVoting ? "YES" : "NO"),
                    hasVoting ? NamedTextColor.GREEN : NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Magic Mode: " + mode,
                    mode.isMagicEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));

            if (session != null) {
                sender.sendMessage(Component.text("  Team votes: " + session.countVotes(MagicMode.TEAM), NamedTextColor.GREEN));
                sender.sendMessage(Component.text("  Individual votes: " + session.countVotes(MagicMode.INDIVIDUAL), NamedTextColor.LIGHT_PURPLE));
                sender.sendMessage(Component.text("  Off votes: " + session.countVotes(MagicMode.OFF), NamedTextColor.RED));
            }
        }
    }

    private void handleForce(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /mb voting force <arena> <team|individual|off>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[2];

        // "enable"/"disable" still work: they were the whole vocabulary before there was a third
        // mode, and an admin's muscle memory should not start silently doing nothing.
        MagicMode mode = switch (args[3].toLowerCase()) {
            case "enable" -> MagicMode.TEAM;
            case "disable" -> MagicMode.OFF;
            default -> MagicMode.fromId(args[3], null);
        };

        if (mode == null) {
            plugin.getAudit().emitAdmin(sender, "voting.force", AuditOutcome.DENIED, "unknown_mode",
                    row -> row.put("arena", arenaName).put("mode_arg", args[3]));
            sender.sendMessage(Component.text("Unknown mode: " + args[3] + " (team, individual or off)",
                    NamedTextColor.RED));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByName(arenaName);
        if (arena == null) {
            plugin.getAudit().emitAdmin(sender, "voting.force", AuditOutcome.DENIED, "arena_not_found",
                    row -> row.put("arena", arenaName).put("mode", mode.name()));
            sender.sendMessage(Component.text("Arena not found: " + arenaName, NamedTextColor.RED));
            return;
        }

        String previous = String.valueOf(plugin.getVotingManager().getMagicMode(arenaName));
        plugin.getVotingManager().cleanupArena(arenaName);
        plugin.getVotingManager().setMagicMode(arenaName, mode);
        plugin.getAudit().emitAdmin(sender, "voting.force", AuditOutcome.COMMITTED, null,
                row -> row.put("arena", arenaName).put("mode", mode.name()).put("previous_mode", previous));

        sender.sendMessage(Component.text("Force set magic mode " + mode + " for arena " + arenaName,
                mode.isMagicEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private void handleTest(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command must be run by a player", NamedTextColor.RED));
            return;
        }

        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null) {
            sender.sendMessage(Component.text("You must be in an arena", NamedTextColor.RED));
            return;
        }

        plugin.getVotingManager().startVoting(arena);
        sender.sendMessage(Component.text("Started voting test for arena: " + arena.getName(), NamedTextColor.GREEN));
    }

    private void handleClear(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /mb voting clear <arena>", NamedTextColor.RED));
            return;
        }

        String arenaName = args[2];
        plugin.getVotingManager().cleanupArena(arenaName);
        plugin.getAudit().emitAdmin(sender, "voting.clear", AuditOutcome.COMMITTED, null,
                row -> row.put("arena", arenaName));
        sender.sendMessage(Component.text("Cleared voting data for arena: " + arenaName, NamedTextColor.GREEN));
    }

    public List<String> tabComplete(String[] args) {
        if (args.length == 2) {
            return List.of("status", "force", "test", "clear");
        }

        if (args.length == 3) {
            if ("force".equals(args[1]) || "clear".equals(args[1])) {
                return BedwarsAPI.getGameAPI().getArenas().stream()
                        .map(Arena::getName)
                        .toList();
            }
        }

        if (args.length == 4 && "force".equals(args[1])) {
            return List.of("team", "individual", "off");
        }

        return List.of();
    }
}