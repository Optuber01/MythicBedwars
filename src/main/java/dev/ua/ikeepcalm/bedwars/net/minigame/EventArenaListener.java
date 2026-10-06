package dev.ua.ikeepcalm.bedwars.net.minigame;

import de.marcely.bedwars.api.BedwarsAPI;
import de.marcely.bedwars.api.arena.Arena;
import de.marcely.bedwars.api.arena.ArenaStatus;
import de.marcely.bedwars.api.arena.KickReason;
import de.marcely.bedwars.api.arena.Team;
import de.marcely.bedwars.api.event.arena.ArenaBedBreakEvent;
import de.marcely.bedwars.api.event.arena.ArenaStatusChangeEvent;
import de.marcely.bedwars.api.event.arena.RoundEndEvent;
import de.marcely.bedwars.api.event.player.PlayerKillPlayerEvent;
import de.marcely.bedwars.api.event.player.PlayerQuitArenaEvent;
import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.bedwars.domain.reward.RewardService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ties the Bedwars match itself to the event lifecycle: seating arrivals, tracking who did what,
 * paying out, and sending everybody home.
 *
 * <p>Registered only in the minigame role.
 */
public class EventArenaListener implements Listener {

    private final MythicBedwars plugin;
    private final EventOrchestrator orchestrator;
    private final EventReturnService returns;
    private final RewardService rewards;

    public EventArenaListener(MythicBedwars plugin, EventOrchestrator orchestrator,
                              EventReturnService returns, RewardService rewards) {
        this.plugin = plugin;
        this.orchestrator = orchestrator;
        this.returns = returns;
        this.rewards = rewards;
    }

    /**
     * A recruit landing on this server. Seat them in the arena that is being held for them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // A tick of grace: MBedwars and COI both do their own join handling, and adding somebody to
        // an arena from inside their login is asking for trouble.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                orchestrator.handleArrival(event.getPlayer());
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(PlayerKillPlayerEvent event) {
        Arena arena = event.getArena();
        if (!orchestrator.isEventArena(arena.getName()) || event.getKiller() == null) {
            return;
        }

        rewards.tracker().recordKill(arena.getName(), event.getKiller().getUniqueId(), event.isFatalDeath());
    }

    /**
     * @return everybody the arena still holds. Spectators live in a separate collection from players,
     * and somebody who died and chose to watch is in the second one only.
     */
    private static List<Player> everyoneIn(Arena arena) {
        List<Player> present = new ArrayList<>(arena.getPlayers());
        for (Player spectator : arena.getSpectators()) {
            if (!present.contains(spectator)) {
                present.add(spectator);
            }
        }
        return present;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedBreak(ArenaBedBreakEvent event) {
        Arena arena = event.getArena();
        if (!orchestrator.isEventArena(arena.getName()) || event.getPlayer() == null) {
            return;
        }

        rewards.tracker().recordBedBreak(arena.getName(), event.getPlayer().getUniqueId());
    }

    /**
     * Buying something counts as playing. Without this a defender who never lands a kill has zero
     * recorded actions and is denied their whole bundle, which is the opposite of the intent.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onShopPurchase(de.marcely.bedwars.api.event.player.PlayerBuyInShopEvent event) {
        Arena arena = event.getArena();
        if (arena == null || !orchestrator.isEventArena(arena.getName())) {
            return;
        }

        // The event is not Cancellable, so ignoreCancelled would do nothing. A purchase that failed
        // - they could not afford it - must not clear the "did anything at all" gate.
        if (!event.getProblems().isEmpty()) {
            return;
        }

        rewards.tracker().recordPurchase(arena.getName(), event.getPlayer().getUniqueId());
    }

    /**
     * Building and mining count as being present. This is the whole life of a base defender: wool
     * over the bed, obsidian over the wool, blocks back down after every raid — all of it done
     * standing still, where a movement-only idle check sees nothing at all.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        markActive(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        markActive(event.getPlayer());
    }

    /**
     * Fighting counts for both sides of the hit. A defender who is being shot at from a bridge is
     * plainly at their keyboard even if they never land the kill that would be recorded.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player damager) {
            markActive(damager);
        } else if (event.getDamager() instanceof Projectile projectile
                   && projectile.getShooter() instanceof Player shooter) {
            markActive(shooter);
        }

        if (event.getEntity() instanceof Player victim) {
            markActive(victim);
        }
    }

    /**
     * Credits one player with having done something, if they are in an event arena of ours. Cheap
     * enough for a hot handler: an arena lookup and, off the event path, a timestamp write.
     */
    private void markActive(Player player) {
        Arena arena = BedwarsAPI.getGameAPI().getArenaByPlayer(player);
        if (arena == null || !orchestrator.isEventArena(arena.getName())) {
            return;
        }

        rewards.tracker().recordActivity(arena.getName(), player.getUniqueId());
    }

    /**
     * Somebody left the arena. Eliminated players get offered a ride home; rage quitters get
     * recorded as such so they do not collect.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitArena(PlayerQuitArenaEvent event) {
        Arena arena = event.getArena();
        EventReservation reservation = orchestrator.getReservation(arena.getName());
        if (reservation == null) {
            return;
        }

        Player player = event.getPlayer();
        KickReason reason = event.getReason();
        boolean running = arena.getStatus() == ArenaStatus.RUNNING;

        rewards.tracker().markQuit(arena.getName(), player.getUniqueId(), reason, running);

        // Somebody who left before the match began stops counting towards the arrival gate, or the
        // countdown fires for players who are no longer there.
        if (!running) {
            orchestrator.forgetArrival(reservation, player.getUniqueId());
        }

        if (!player.isOnline()) {
            // Disconnected rather than eliminated; their outcome is settled at RoundEndEvent.
            return;
        }

        // Deliberately not KickReason#isRageQuit(), which also covers TELEPORT, SPECTATE and any
        // third-party PLUGIN kick. Treating a player who died and chose to spectate as a quitter
        // would deny their reward and, worse, skip their ride home entirely.
        if (reason == KickReason.LEAVE && running) {
            returns.recordOnly(player.getUniqueId(), reservation, ReturnOutcome.QUIT);
            return;
        }

        // Somebody who chose to spectate is offered a ride home only when spectating is switched
        // off for events; otherwise let them watch, and the round-end sweep will move them.
        boolean spectating = reason == KickReason.SPECTATE;
        if (spectating && plugin.getConfigManager().isEventSpectatorsAllowed()) {
            return;
        }

        if (reason == KickReason.GAME_LOSE || spectating) {
            returns.offerReturn(player, reservation, ReturnOutcome.LOSE,
                    plugin.getConfigManager().getEventAutoReturnSeconds());
        }
    }

    /**
     * Records who played with and against whom, so collusion between the same accounts is visible.
     * Main thread; never throws.
     */
    private void auditMatchFinished(EventReservation reservation, Arena arena, Team winningTeam, boolean tie,
                                    List<Player> winners, List<Player> losers,
                                    List<UUID> quitWinners, List<UUID> quitLosers) {
        try {
            plugin.getAudit().emit(BedwarsAuditEmitter.AuditRow.of("event.match_finished", AuditOutcome.OBSERVED)
                    .correlation(BedwarsAuditEmitter.eventCorrelation(reservation.eventId()))
                    .business(reservation.eventId())
                    .put("actor", "system")
                    .put("event_id", reservation.eventId())
                    .put("arena", arena.getName())
                    .put("winning_team", winningTeam == null ? null : winningTeam.name())
                    .put("tie", tie)
                    .put("winner_count", winners.size())
                    .put("loser_count", losers.size())
                    .put("winners_list", ids(winners.stream().map(Player::getUniqueId).toList()))
                    .put("losers_list", ids(losers.stream().map(Player::getUniqueId).toList()))
                    .put("quit_winners_list", ids(quitWinners))
                    .put("quit_losers_list", ids(quitLosers)));
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort.
        }
    }

    private static String ids(List<UUID> players) {
        return String.join(",", players.stream().map(UUID::toString).toList());
    }

    /**
     * The authoritative end of the match.
     *
     * <p>Everything that needs Beyonder context happens here, because the
     * {@code PlayerQuitArenaEvent} cascade that follows tears the sandbox loadouts down.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRoundEnd(RoundEndEvent event) {
        Arena arena = event.getArena();
        EventReservation reservation = orchestrator.getReservation(arena.getName());
        if (reservation == null) {
            return;
        }

        Team winningTeam = event.getWinnerTeam();
        boolean tie = event.isTie();

        List<Player> winners = new ArrayList<>(event.getWinners());
        List<Player> losers = new ArrayList<>(event.getLosers());

        List<UUID> quitWinners = event.getQuitWinners().stream().map(memory -> memory.getUniqueId()).toList();
        List<UUID> quitLosers = event.getQuitLosers().stream().map(memory -> memory.getUniqueId()).toList();

        // On a tie MBedwars reports every one of those four collections as empty - that is the
        // documented contract, not an oversight. Without this the whole roster would be left standing
        // on the Bedwars server with no result, no reward and no way home.
        if (tie) {
            losers = new ArrayList<>(everyoneIn(arena));
        }

        plugin.log("Event {} finished on {} - {} winner(s), {} loser(s){}.",
                reservation.eventId(), arena.getName(), winners.size(), losers.size(),
                tie ? " (tie)" : "");

        auditMatchFinished(reservation, arena, winningTeam, tie, winners, losers, quitWinners, quitLosers);
        rewards.payOut(reservation, arena, winners, losers, quitWinners, quitLosers, tie);
        orchestrator.publishFinished(reservation, arena, winningTeam, tie, winners, losers);

        // Winners and losers are returned separately. Handing the combined list a single outcome
        // would file every surviving loser as a winner - harmless while the outcome only drives the
        // greeting, and a real mispayment the moment anything downstream reads it.
        List<UUID> winnerIds = winners.stream().map(Player::getUniqueId).toList();
        List<UUID> loserIds = losers.stream().map(Player::getUniqueId).toList();

        // Anyone still here who is in neither list - typically somebody eliminated who chose to
        // spectate. getPlayers() alone would miss them, and they would be stranded.
        List<UUID> others = everyoneIn(arena).stream()
                .map(Player::getUniqueId)
                .filter(id -> !winnerIds.contains(id) && !loserIds.contains(id))
                .toList();

        ReturnOutcome winnerOutcome = tie ? ReturnOutcome.TIE : ReturnOutcome.WIN;
        ReturnOutcome loserOutcome = tie ? ReturnOutcome.TIE : ReturnOutcome.LOSE;

        // Let the celebration play before moving anybody.
        int delay = plugin.getConfigManager().getEventWinnerReturnDelaySeconds();
        EventReservation held = reservation;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            sendHome(winnerIds, held, winnerOutcome);
            sendHome(loserIds, held, loserOutcome);
            sendHome(others, held, loserOutcome);
        }, Math.max(1, delay) * 20L);

        // Nobody is left to move, but their results still have to reach the SMP.
        quitWinners.forEach(id -> returns.recordOnly(id, reservation, winnerOutcome));
        quitLosers.forEach(id -> returns.recordOnly(id, reservation, loserOutcome));
    }

    /**
     * Arena went idle: give it back and forget the match.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaStatusChange(ArenaStatusChangeEvent event) {
        if (event.getNewStatus() != ArenaStatus.STOPPED) {
            return;
        }

        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation == null) {
            return;
        }

        rewards.tracker().clear(event.getArena().getName());
        returns.clear(reservation.eventId());
        orchestrator.finish(reservation);
    }

    /**
     * Sends home only the players who were recruited from the survival server.
     *
     * <p>A local player who took a spare slot lives on this server. Transferring them to the survival
     * server would be baffling — but their result still has to be recorded, because they played the
     * match and their reward is queued against their real profile.
     */
    private void sendHome(List<UUID> playerIds, EventReservation reservation, ReturnOutcome outcome) {
        List<UUID> travelling = playerIds.stream()
                .filter(id -> reservation.roster().contains(id))
                .toList();

        playerIds.stream()
                .filter(id -> !reservation.roster().contains(id))
                .forEach(local -> returns.recordOnly(local, reservation, outcome));

        if (!travelling.isEmpty()) {
            returns.returnAll(travelling, reservation, outcome);
        }
    }

    /**
     * A clone being unloaded, which is the normal end of life for the arenas the selector prefers.
     *
     * <p>Teardown cannot hang off {@code STOPPED} alone: an arena removed without that transition
     * would leak its reservation permanently, locking future events out and leaving the arena closed
     * to local players for the life of the process.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaUnload(de.marcely.bedwars.api.event.arena.ArenaUnloadEvent event) {
        EventReservation reservation = orchestrator.getReservation(event.getArena().getName());
        if (reservation == null) {
            return;
        }

        rewards.tracker().clear(event.getArena().getName());
        returns.clear(reservation.eventId());
        orchestrator.finish(reservation);
    }
}
