package dev.ua.ikeepcalm.bedwars.cmd.impls;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.protocol.Heartbeat;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.smp.RecruitmentManager;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * {@code /mb event ...} — the cross-server event controls, available in both roles.
 *
 * <p>{@code join} is the only branch ordinary players reach, and the only reason the parent command
 * is not permission-gated as a whole: a chat button dies with the connection that rendered it, so
 * somebody who reconnects mid-drive needs a way in that survives a relog. Everything else here needs
 * {@code mythicbedwars.admin}, checked per branch.
 */
public class EventCommand {

    public static final List<String> SUBCOMMANDS =
            List.of("status", "next", "join", "preview", "start", "cancel", "send");

    /**
     * The subcommands a player without admin may use.
     */
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("join", "next");

    private final MythicBedwars plugin;

    public EventCommand(MythicBedwars plugin) {
        this.plugin = plugin;
    }

    /**
     * @param args the full argument array, with {@code "event"} still at index 0
     */
    public void dispatch(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendHelp(sender);
            return;
        }

        String subcommand = args[1].toLowerCase();

        // join and next carry their own permission; everything else is administrative.
        if (!PLAYER_SUBCOMMANDS.contains(subcommand) && !sender.hasPermission("mythicbedwars.admin")) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.no_permission"));
            return;
        }

        switch (subcommand) {
            case "status" -> handleStatus(sender);
            case "next" -> handleNext(sender);
            case "join" -> handleJoin(sender);
            case "preview" -> handlePreview(sender);
            case "start" -> handleStart(sender);
            case "cancel" -> handleCancel(sender);
            case "send" -> handleSend(sender, args);
            default -> sendHelp(sender);
        }
    }

    public void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("/mb event join - Sign up for the event being advertised",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event next - When the next event is due",
                NamedTextColor.YELLOW));

        if (!sender.hasPermission("mythicbedwars.admin")) {
            return;
        }

        sender.sendMessage(Component.text("/mb event status - Cross-server link diagnostics", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event preview - Show the announcement without starting anything",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event start - Offer an event now, ignoring the quiet period (SMP only)",
                NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event cancel - Call off the event in flight", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/mb event send <player> <smp|minigame|server> - Move a player across the proxy",
                NamedTextColor.YELLOW));
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> visible = sender.hasPermission("mythicbedwars.admin")
                ? SUBCOMMANDS
                : PLAYER_SUBCOMMANDS;

        if (args.length == 2) {
            return visible.stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .toList();
        }

        if (!sender.hasPermission("mythicbedwars.admin")) {
            return List.of();
        }

        if (args.length == 3 && "send".equalsIgnoreCase(args[1])) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                    .toList();
        }

        if (args.length == 4 && "send".equalsIgnoreCase(args[1])) {
            return Stream.of("smp", "minigame")
                    .filter(s -> s.startsWith(args[3].toLowerCase()))
                    .toList();
        }

        return List.of();
    }

    /**
     * When the next attempt is due, and whether the server currently has the players for it.
     *
     * <p>Player-facing on purpose. The schedule already knew both answers; until this existed the
     * only way to ask was an admin command, so events appeared out of nowhere and a server sitting
     * one player below the threshold had no way of finding that out.
     */
    public void handleNext(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            // Console gets the admin one-liner instead; it has no locale and nothing to plan around.
            handleStatus(sender);
            return;
        }

        if (!player.hasPermission("mythicbedwars.event.join")) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.commands.no_permission"));
            return;
        }

        if (plugin.getScheduleAnnouncer() == null) {
            player.sendMessage(plugin.getLocaleManager()
                    .formatMessage(player, "magic.event.countdown.not_here"));
            return;
        }

        plugin.getScheduleAnnouncer().describeFor(player).forEach(player::sendMessage);
    }

    /**
     * The relog-proof way in: chat click callbacks die with the connection that rendered them, so a
     * player who reconnects mid-drive still needs a way to sign up.
     */
    public void handleJoin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.player_only"));
            return;
        }

        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.signup.closed"));
            return;
        }

        if (!player.hasPermission("mythicbedwars.event.join")) {
            sender.sendMessage(plugin.getLocaleManager().formatMessage("magic.commands.no_permission"));
            return;
        }

        recruitment.join(player);
    }

    private void handlePreview(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            admin(sender, "Only the SMP server advertises events.", NamedTextColor.RED);
            return;
        }

        recruitment.previewAnnouncement(sender);
    }

    private void handleStart(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment == null) {
            admin(sender, "Only the SMP server can start an event.", NamedTextColor.RED);
            return;
        }

        admin(sender, "Asking for a host (ignoring the quiet period)...", NamedTextColor.GRAY);
        recruitment.propose(true, problem -> {
            String eventId = recruitment.currentEventId().orElse(null);
            plugin.getAudit().emitAdmin(sender, "event.start",
                    problem == null ? AuditOutcome.COMMITTED : AuditOutcome.DENIED, problem,
                    row -> row.risk(AuditRisk.HIGH).put("ignore_cooldown", true)
                            .put("event_id", problem == null ? eventId : null)
                            .correlation(problem == null ? BedwarsAuditEmitter.eventCorrelation(eventId) : null)
                            .business(problem == null ? eventId : null));
            if (problem == null) {
                admin(sender, "Offered. Nothing is announced to players until a host accepts.",
                        NamedTextColor.GREEN);
            } else {
                admin(sender, problem, NamedTextColor.RED);
            }
        });
    }

    private void handleCancel(CommandSender sender) {
        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment != null) {
            // Read before cancelling: cancel() works on exactly this id, and clears it when done.
            String eventId = recruitment.currentEventId().orElse(null);
            var stateBefore = recruitment.currentState();
            // Read now: the failure row is written from the background task, which must not touch the sender.
            UUID actorId = sender instanceof Entity entity ? entity.getUniqueId() : null;
            String consoleName = actorId == null ? sender.getName() : null;
            recruitment.cancel(CancelReason.ADMIN, message -> {
                plugin.getAudit().emitAdmin(actorId, consoleName, "event.cancel",
                        eventId == null ? AuditOutcome.DENIED : AuditOutcome.COMMITTED,
                        eventId == null ? "no_event_in_flight" : null,
                        row -> row.risk(AuditRisk.HIGH).put("role", "smp").put("event_id", eventId)
                                .put("state_before", stateBefore == null ? null : stateBefore.name())
                                .correlation(eventId == null ? null : BedwarsAuditEmitter.eventCorrelation(eventId))
                                .business(eventId));
                admin(sender, message, NamedTextColor.YELLOW);
            }, failure -> plugin.getAudit().emitAdmin(actorId, consoleName, "event.cancel",
                    AuditOutcome.FAILED, "cancel_failed",
                    row -> row.risk(AuditRisk.HIGH).put("role", "smp").put("event_id", eventId)
                            .put("state_before", stateBefore == null ? null : stateBefore.name())
                            .put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage())
                            .correlation(BedwarsAuditEmitter.eventCorrelation(eventId))
                            .business(eventId)));
            return;
        }

        int released = plugin.cancelHostedEvents(CancelReason.ADMIN);
        plugin.getAudit().emitAdmin(sender, "event.cancel",
                released == 0 ? AuditOutcome.DENIED : AuditOutcome.COMMITTED,
                released == 0 ? "no_hosted_event" : null,
                row -> row.risk(AuditRisk.HIGH).put("role", "minigame")
                        .put("released_arenas", released));
        if (released == 0) {
            admin(sender, "No event is being hosted here.", NamedTextColor.YELLOW);
        } else {
            admin(sender, "Released " + released + " reserved arena(s).", NamedTextColor.YELLOW);
        }
    }

    /**
     * Diagnostic output, tagged so it is not mistaken for an ordinary chat message.
     */
    private void admin(CommandSender sender, String message, NamedTextColor colour) {
        sender.sendMessage(Component.text("[MythicBedwars] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(message, colour)));
    }

    /**
     * Admin smoke test for the proxy link, ahead of the automated flows that will use it.
     */
    private void handleSend(CommandSender sender, String[] args) {
        if (args.length < 4) {
            admin(sender, "Usage: /mb event send <player> <smp|minigame|server>", NamedTextColor.RED);
            return;
        }

        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            plugin.getAudit().emitAdmin(sender, "event.send", AuditOutcome.DENIED, "target_offline",
                    row -> row.put("target_name", args[2]).put("destination_arg", args[3]));
            admin(sender, "Player '" + args[2] + "' is not online here.", NamedTextColor.RED);
            return;
        }

        String destination = resolveServerName(args[3]);
        if (destination == null || destination.isBlank()) {
            plugin.getAudit().emitAdmin(sender, "event.send", AuditOutcome.DENIED, "unknown_destination",
                    row -> row.target(target.getUniqueId()).put("target_name", target.getName())
                            .put("destination_arg", args[3]));
            admin(sender, "No Velocity server name is configured for '" + args[3] + "'.", NamedTextColor.RED);
            return;
        }

        boolean sent = plugin.getTransferService().transfer(target, destination);
        plugin.getAudit().emitAdmin(sender, "event.send", sent ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                sent ? null : "transfer_failed",
                row -> row.risk(AuditRisk.HIGH).target(target.getUniqueId()).put("target_name", target.getName())
                        .put("destination", destination).put("result", sent ? "transfer_requested" : "transfer_failed"));
        if (sent) {
            target.sendMessage(plugin.getLocaleManager().formatMessage("magic.event.transferring", "server", destination));
            admin(sender, "Sending " + target.getName() + " to " + destination + "...", NamedTextColor.GREEN);
        } else {
            admin(sender, "Could not send " + target.getName() + " to " + destination + ".", NamedTextColor.RED);
        }
    }

    /**
     * Accepts the two role shorthands as well as a literal Velocity server name, so an admin does
     * not have to remember how the proxy names things.
     */
    private String resolveServerName(String raw) {
        return switch (raw.toLowerCase()) {
            case "smp" -> plugin.getConfigManager().getSmpVelocityServer();
            case "minigame", "bedwars" -> plugin.getConfigManager().getMinigameVelocityServer();
            default -> raw;
        };
    }

    private void handleStatus(CommandSender sender) {
        NetworkService network = plugin.getNetworkService();

        sender.sendMessage(Component.text("=== MythicBedwars Network ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Role: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.getNetworkRole().name(), NamedTextColor.AQUA)));

        if (network == null) {
            sender.sendMessage(Component.text("Networking is disabled (network.enabled: false).", NamedTextColor.YELLOW));
            return;
        }

        sender.sendMessage(Component.text("Server ID: ", NamedTextColor.GRAY)
                .append(Component.text(network.serverId(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Namespace: ", NamedTextColor.GRAY)
                .append(Component.text(network.keys().namespace(), NamedTextColor.WHITE)));

        RecruitmentManager recruitment = plugin.getRecruitmentManager();
        if (recruitment != null) {
            sender.sendMessage(Component.text("Schedule: ", NamedTextColor.GRAY)
                    .append(Component.text(recruitment.describeSchedule(), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Event: ", NamedTextColor.GRAY)
                    .append(recruitment.currentEventId()
                            .map(id -> Component.text(id + " [" + recruitment.currentState() + "]"
                                                      + recruitment.arena().map(a -> " on " + a).orElse(""),
                                    NamedTextColor.WHITE))
                            .orElse(Component.text("none in flight", NamedTextColor.DARK_GRAY))));
        }

        if (plugin.isMinigameRole()) {
            var held = plugin.getReservedArenaNames();
            sender.sendMessage(Component.text("Reserved arenas: ", NamedTextColor.GRAY)
                    .append(held.isEmpty()
                            ? Component.text("none", NamedTextColor.DARK_GRAY)
                            : Component.text(String.join(", ", held), NamedTextColor.WHITE)));
        }

        boolean available = network.isAvailable();
        sender.sendMessage(Component.text("Redis: ", NamedTextColor.GRAY)
                .append(Component.text(available ? "connected" : "unavailable",
                        available ? NamedTextColor.GREEN : NamedTextColor.RED)));

        if (!available) {
            sender.sendMessage(Component.text("Peers cannot be listed while Redis is down.", NamedTextColor.YELLOW));
            return;
        }

        // The registry does Redis I/O; keep it off the main thread and report back when it lands.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            network.registry().invalidate();
            List<Heartbeat> peers = network.registry().alive();
            long now = System.currentTimeMillis();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (peers.isEmpty()) {
                    sender.sendMessage(Component.text("No live servers found - not even this one, which means "
                                                      + "the first heartbeat has not been written yet.", NamedTextColor.YELLOW));
                    return;
                }

                sender.sendMessage(Component.text("Live servers (" + peers.size() + "):", NamedTextColor.GOLD));
                for (Heartbeat peer : peers) {
                    boolean self = peer.serverId().equals(network.serverId());
                    sender.sendMessage(Component.text("  " + peer.serverId(), self ? NamedTextColor.AQUA : NamedTextColor.WHITE)
                            .append(Component.text(" [" + peer.role() + "]", NamedTextColor.GRAY))
                            .append(Component.text(" via " + peer.velocityServer(), NamedTextColor.DARK_GRAY))
                            .append(Component.text(" - " + peer.onlinePlayers() + " online", NamedTextColor.GRAY))
                            .append(Component.text(", v" + peer.pluginVersion(), NamedTextColor.DARK_GRAY))
                            .append(Component.text(", seen " + ((now - peer.ts()) / 1000) + "s ago", NamedTextColor.DARK_GRAY)));
                }
            });
        });
    }
}
