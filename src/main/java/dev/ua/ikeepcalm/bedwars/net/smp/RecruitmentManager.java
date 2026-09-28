package dev.ua.ikeepcalm.bedwars.net.smp;

import dev.ua.ikeepcalm.bedwars.MythicBedwars;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventRecord;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.protocol.Envelope;
import dev.ua.ikeepcalm.bedwars.net.protocol.payload.Payloads;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.EventState;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.MessageType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.Consumer;

/**
 * The survival-server half: decide when to run an event, ask for a host, and hold the result.
 *
 * <p>The rule that matters most here is that <b>nothing is ever announced to players before a host
 * has accepted</b>. Advertising a match the network cannot actually run is worse than not
 * advertising one at all.
 */
public class RecruitmentManager implements dev.ua.ikeepcalm.bedwars.net.EventParticipant {

    /** Guards the decide-and-propose critical section against a second SMP node. */
    private static final int PROPOSE_LOCK_TTL_SECONDS = 10;

    /**
     * Below this much of the signup window left, do not announce at all.
     *
     * <p>A slow accept round trip or a little clock skew between backends would otherwise produce the
     * full multi-line broadcast followed, a second later, by "cancelled - 0 players signed up". That
     * is exactly the chat spam the never-announce-before-accept rule exists to prevent, arriving
     * through the back door.
     */
    private static final long MIN_ANNOUNCE_WINDOW_MILLIS = 15_000L;

    private final MythicBedwars plugin;
    private final NetworkService network;
    private final EventStore store;
    private final RecruitmentAnnouncer announcer;
    private final SignupRegistry signups;
    /** Durable record of when the last event went out, so the interval survives a restart. */
    private final ScheduleJournal journal;

    /**
     * Reminder thresholds already fired for the current drive, so each fires once.
     */
    private final Set<Integer> firedReminders = new HashSet<>();

    private volatile BukkitTask signupTask;
    /**
     * One-shot guard so a capacity-reached close and a deadline close cannot both fire.
     */
    private final java.util.concurrent.atomic.AtomicBoolean signupsClosing =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile BukkitTask proposeTimeoutTask;
    private volatile int cap;
    private volatile BukkitTask scheduleTask;

    /**
     * Roster size as last observed, for reminder text without an extra Redis round trip.
     */
    private volatile int lastKnownCount;
    private volatile String currentEventId;
    private volatile EventState currentState;
    private volatile String hostServerId;
    private volatile String hostServerName;
    private volatile String arenaName;

    public RecruitmentManager(MythicBedwars plugin, NetworkService network, EventStore store) {
        this.plugin = plugin;
        this.network = network;
        this.store = store;
        this.announcer = new RecruitmentAnnouncer(plugin);
        this.signups = new SignupRegistry(network.client(), network.keys(),
                plugin.getConfigManager().getEventTtlSeconds());
        this.journal = new ScheduleJournal(plugin);
        this.journal.load();
    }

    public void registerHandlers() {
        network.bus().on(MessageType.EVENT_ACCEPT, this::onAccept);
        network.bus().on(MessageType.EVENT_REJECT, this::onReject);
        network.bus().on(MessageType.ARENA_READY, this::onArenaReady);
        network.bus().on(MessageType.PLAYER_ARRIVED, this::onPlayerArrived);
        network.bus().on(MessageType.EVENT_STARTED, this::onEventStarted);
        network.bus().on(MessageType.EVENT_FINISHED, this::onEventFinished);
        network.bus().on(MessageType.EVENT_CANCELLED, this::onCancelled);
        network.bus().on(MessageType.PLAYER_RETURN, this::onPlayerReturn);
    }

    @Override
    public java.util.Set<String> localEventIds() {
        String eventId = currentEventId;
        return eventId == null ? Set.of() : Set.of(eventId);
    }

    @Override
    public void abandonLocally(String eventId, CancelReason reason) {
        if (!isCurrent(eventId)) {
            return;
        }

        if (currentState == EventState.ANNOUNCED) {
            announcer.broadcast(reason.localeKey());
        }

        clearLocal();
    }

    /**
     * Starts the schedule: at most one event per configured window, offered whenever the server has
     * the players for it. Without it every event needs an admin to type a command, which is how a
     * feature built to revive a dead server ends up never running.
     *
     * <p>Deliberately <b>not</b> driven by how idle anybody looks. Guessing at who wants something
     * to do from their AFK timer got both halves of the question wrong: a player at their keyboard
     * doing quiet work counted as bored, an unattended body counted as an interested participant,
     * and the server's own population — the one thing that actually decides whether a match is worth
     * running — did not come into it at all.
     */
    public void startSchedule() {
        // Idempotent, so /mb reload can re-arm it at a new interval, switch it on, or switch it off.
        BukkitTask existing = scheduleTask;
        if (existing != null) {
            existing.cancel();
            scheduleTask = null;
        }

        if (!plugin.getConfigManager().isEventScheduleEnabled()) {
            return;
        }

        long period = Math.max(30L, plugin.getConfigManager().getEventScheduleCheckSeconds()) * 20L;
        scheduleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::considerScheduledEvent, period, period);

        plugin.log("Scheduling one event every {} whenever at least {} players are online.",
                scheduleInterval(),
                plugin.getConfigManager().getEventScheduleMinPlayers());
    }

    private void considerScheduledEvent() {
        if (currentEventId != null || !network.isAvailable()) {
            return;
        }

        // The journal first, and locally: it is authoritative about when the last offer went out,
        // costs nothing to ask, and keeps a two-minute tick from touching Redis 30 times an hour.
        if (!journal.isDue(windowSeconds() * 1000L)) {
            return;
        }

        int online = countEligiblePlayers();
        if (online < plugin.getConfigManager().getEventScheduleMinPlayers()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Claiming the window is what keeps a second survival node from offering the same event
            // in the same instant; its TTL matches the interval, so it also survives this server.
            String token = UUID.randomUUID().toString();
            if (!network.client().setIfAbsent(network.keys().scheduleWindow(), token, windowSeconds())) {
                return;
            }

            // Cooldown is honoured here, unlike the admin command: this fires on a timer, and a
            // server that ran a match minutes ago should not immediately run another.
            // tryPropose records the offer in the journal itself, so a manual start counts too.
            String problem = tryPropose(false);
            if (problem == null) {
                plugin.log("Scheduled event offered with {} player(s) online; next window in {}.",
                        online, scheduleInterval());
                return;
            }

            // Nothing was offered, so do not spend the window on it - the next check should be free
            // to try again as soon as whatever blocked it clears.
            network.client().deleteIfEquals(network.keys().scheduleWindow(), token);
            plugin.log("Scheduled event skipped: {}", problem);
        });
    }

    /**
     * @return the interval as something readable, so a whole number of hours does not print as "6.0"
     */
    private String scheduleInterval() {
        double hours = plugin.getConfigManager().getEventScheduleIntervalHours();
        return hours == Math.rint(hours) ? (long) hours + "h" : hours + "h";
    }

    private int windowSeconds() {
        double hours = plugin.getConfigManager().getEventScheduleIntervalHours();
        return (int) Math.max(60L, Math.round(hours * 3600.0));
    }

    /**
     * @return how many players are online who might actually turn up. Anyone opted out of event
     * broadcasts is not counted, since they will never be asked.
     */
    private int countEligiblePlayers() {
        int eligible = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("mythicbedwars.event.exempt")) {
                eligible++;
            }
        }

        return eligible;
    }

    /**
     * Everything a player-facing countdown needs to say, taken in one consistent snapshot.
     *
     * <p>A record rather than four getters because the answers have to agree with each other: a
     * countdown that reads the remaining time, then the population a tick later, can report "due
     * now, and enough players" for a window that has just been spent.
     *
     * @param enabled        whether the schedule runs at all
     * @param eventInFlight  whether one is already being recruited, which makes the countdown moot
     * @param millisUntilDue how long until the next attempt, {@code 0} when one is due now
     * @param onlineEligible players online who would actually be asked
     * @param requiredPlayers how many of them the schedule needs
     */
    public record ScheduleOutlook(boolean enabled, boolean eventInFlight, long millisUntilDue,
                                  int onlineEligible, int requiredPlayers) {

        /**
         * @return whether the server is currently short of the players the schedule needs
         */
        public boolean shortfall() {
            return onlineEligible < requiredPlayers;
        }

        /**
         * @return how many more players are needed, never negative
         */
        public int missing() {
            return Math.max(0, requiredPlayers - onlineEligible);
        }

        /**
         * @return whole minutes until the next attempt, rounded up so a countdown never shows "0m"
         * for a window that has not arrived
         */
        public long minutesUntilDue() {
            return (millisUntilDue + 59_999L) / 60_000L;
        }
    }

    /**
     * @return the current state of the schedule, for {@code /mb event next} and the countdown
     * broadcast
     */
    public ScheduleOutlook outlook() {
        return new ScheduleOutlook(
                plugin.getConfigManager().isEventScheduleEnabled(),
                currentEventId != null,
                journal.millisUntilDue(windowSeconds() * 1000L),
                countEligiblePlayers(),
                plugin.getConfigManager().getEventScheduleMinPlayers());
    }

    /**
     * @return when the last offer went out, which is what identifies the window a countdown is
     * counting down; changes exactly when a new window opens
     */
    public long windowStamp() {
        return journal.lastOfferedAt();
    }

    /**
     * @return a one-line description of the schedule, for {@code /mb event status}
     */
    public String describeSchedule() {
        if (!plugin.getConfigManager().isEventScheduleEnabled()) {
            return "disabled";
        }

        String due = ScheduleJournal.describeDuration(journal.millisUntilDue(windowSeconds() * 1000L));

        return "every " + scheduleInterval() + " with "
               + plugin.getConfigManager().getEventScheduleMinPlayers() + "+ players online ("
               + countEligiblePlayers() + " now); last offered " + journal.describeLastOffered()
               + ", next " + (due == null ? "due now" : "in " + due);
    }

    public Optional<String> currentEventId() {
        return Optional.ofNullable(currentEventId);
    }

    public EventState currentState() {
        return currentState;
    }

    public Optional<String> arena() {
        return Optional.ofNullable(arenaName);
    }

    public Optional<String> host() {
        return Optional.ofNullable(hostServerId);
    }

    /**
     * Attempts to start an event.
     *
     * <p>Everything up to and including the publish happens off the main thread, because each
     * precondition is a Redis round trip. {@code feedback} is invoked back on the main thread so the
     * caller can safely message a command sender.
     *
     * @param ignoreCooldown for the admin command, which should not be blocked by the quiet period
     */
    public void propose(boolean ignoreCooldown, Consumer<String> feedback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String outcome = tryPropose(ignoreCooldown);
            Bukkit.getScheduler().runTask(plugin, () -> feedback.accept(outcome));
        });
    }

    /**
     * @return {@code null} on success, otherwise a human-readable reason it did not happen
     */
    private String tryPropose(boolean ignoreCooldown) {
        if (!plugin.getConfigManager().isEventEnabled()) {
            return "Events are disabled in the config.";
        }

        if (!network.isAvailable()) {
            return "Redis is unavailable - cannot reach the Bedwars server.";
        }

        if (currentEventId != null) {
            return "An event is already in flight (" + currentEventId + ", " + currentState + ").";
        }

        if (!ignoreCooldown && store.isOnCooldown()) {
            return "An event ran recently; still on cooldown.";
        }

        // Pre-flight: never advertise a game nobody can host. This is the single most important
        // rule in the design - the announcement comes only after an accept, and this stops us even
        // asking when there is visibly nobody to ask.
        network.registry().invalidate();
        int wanted = Math.max(plugin.getConfigManager().getEventMinPlayers(),
                Math.min(plugin.getConfigManager().getEventMaxPlayers(), Bukkit.getOnlinePlayers().size()));
        if (network.registry().bestMinigameHost(wanted).isEmpty()) {
            return "No Bedwars server is online to host.";
        }

        String lockToken = UUID.randomUUID().toString();
        if (!network.client().setIfAbsent(network.keys().proposeLock(), lockToken, PROPOSE_LOCK_TTL_SECONDS)) {
            return "Another server is proposing an event right now.";
        }

        try {
            if (store.activeEventId().isPresent()) {
                return "An event is already active on the network.";
            }

            String eventId = UUID.randomUUID().toString();
            if (!store.claimActiveSlot(eventId)) {
                return "Could not claim the network event slot.";
            }

            int minPlayers = plugin.getConfigManager().getEventMinPlayers();
            int maxPlayers = plugin.getConfigManager().getEventMaxPlayers();

            EventRecord record = new EventRecord(
                    eventId, EventState.PROPOSED,
                    network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                    null, null, null,
                    minPlayers, maxPlayers,
                    System.currentTimeMillis(), 0L, null);
            store.write(record);

            currentEventId = eventId;
            currentState = EventState.PROPOSED;

            boolean published = network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_PROPOSE, eventId,
                    new Payloads.Propose(network.serverId(), plugin.getConfigManager().getThisVelocityServer(),
                            minPlayers, maxPlayers, expectedTurnout()));

            if (!published) {
                // Redis went down between claiming the slot and publishing. Roll back, or we would
                // sit believing an event is in flight that was never actually offered to anybody.
                store.purge(eventId);
                Bukkit.getScheduler().runTask(plugin, this::clearLocal);
                return "Could not reach the Bedwars server; nothing was announced.";
            }

            // Every offer starts the interval running, however it was triggered. An admin who runs
            // /mb event start has just held the event for this window, and the schedule should not
            // add another one twenty minutes later - the quiet period an admin can override is the
            // cooldown, not the calendar.
            journal.recordOffered(eventId);
            network.client().setWithTtl(network.keys().scheduleWindow(), eventId, windowSeconds());

            // A local deadline for the answer. Relying on the reaper for this does not work: it
            // publishes to its own role's channel, which never comes back to the publisher, so the
            // local state would stay stuck and block every future event until a restart.
            long timeoutTicks = Math.max(20L, plugin.getConfigManager().getEventProposeTimeoutSeconds() * 20L);
            Bukkit.getScheduler().runTask(plugin, () -> {
                cancelProposeTimeout();
                proposeTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                        () -> onProposeTimedOut(eventId), timeoutTicks);
            });

            plugin.log("Proposed event {} (min {}, max {}).", eventId, minPlayers, maxPlayers);
            return null;
        } finally {
            network.client().deleteIfEquals(network.keys().proposeLock(), lockToken);
        }
    }

    /**
     * Nobody answered in time. Abandon it silently: not one player has been told anything yet, and
     * telling them now that a match they never heard about is off would be worse than saying nothing.
     */
    private void onProposeTimedOut(String eventId) {
        proposeTimeoutTask = null;

        if (!isCurrent(eventId) || currentState != EventState.PROPOSED) {
            return;
        }

        plugin.log("No Bedwars server answered event {} within {}s; abandoning it.",
                eventId, plugin.getConfigManager().getEventProposeTimeoutSeconds());
        clearAndRelease(eventId);
    }

    private void cancelProposeTimeout() {
        BukkitTask task = proposeTimeoutTask;
        if (task != null) {
            task.cancel();
            proposeTimeoutTask = null;
        }
    }

    /**
     * @return the turnout worth sizing an arena for, so the host does not pick a four-slot map for a
     * server with twenty people on it
     */
    private int expectedTurnout() {
        int online = Bukkit.getOnlinePlayers().size();
        return Math.max(plugin.getConfigManager().getEventMinPlayers(),
                Math.min(plugin.getConfigManager().getEventMaxPlayers(), online));
    }

    private void onAccept(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        // A re-sent accept must not re-broadcast the whole opening announcement, reset the reminder
        // thresholds, and leave the previous ticker running.
        if (currentState != EventState.PROPOSED) {
            plugin.log("Ignoring duplicate accept for event {}.", envelope.eventId());
            return;
        }

        cancelProposeTimeout();

        Payloads.Accept accept = network.bus().payload(envelope, Payloads.Accept.class);
        if (accept == null) {
            return;
        }

        hostServerId = accept.minigameServerId();
        hostServerName = accept.minigameServerName();
        arenaName = accept.arenaName();
        currentState = EventState.ACCEPTED;

        plugin.log("Event {} accepted by {} on arena {} (capacity {}).",
                envelope.eventId(), accept.minigameServerId(), accept.arenaName(), accept.arenaCapacity());

        // Persist the transition before anybody can click JOIN: the signup script refuses unless the
        // stored state already reads ANNOUNCED, so announcing first would bounce the fastest clicker.
        // This write also covers us if the host's own write was the thing that failed - it is
        // idempotent, and it stops a later reconcile dragging us back to PROPOSED.
        String eventId = envelope.eventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.read(eventId)
                    .map(record -> record.accepted(accept.minigameServerId(), accept.minigameServerName(),
                                    accept.arenaName(), accept.arenaCapacity(), accept.signupDeadline())
                            .withState(EventState.ANNOUNCED))
                    .ifPresent(store::write);

            Bukkit.getScheduler().runTask(plugin, () -> beginRecruiting(eventId, accept));
        });
    }

    /**
     * Opens the drive: announce it, then tick down to the deadline.
     */
    private void beginRecruiting(String eventId, Payloads.Accept accept) {
        if (!isCurrent(eventId)) {
            return;
        }

        int resolvedCap = Math.min(plugin.getConfigManager().getEventMaxPlayers(), accept.arenaCapacity());
        if (resolvedCap < plugin.getConfigManager().getEventMinPlayers()) {
            // A misconfigured arena reporting a tiny capacity would otherwise have us tell every
            // clicker "all slots are taken" for the whole window before self-cancelling.
            plugin.log("Event {}: host offered room for only {}; not worth announcing.",
                    eventId, resolvedCap);
            clearAndRelease(eventId);
            return;
        }

        long remaining = accept.signupDeadline() - System.currentTimeMillis();
        if (remaining < MIN_ANNOUNCE_WINDOW_MILLIS) {
            plugin.log("Event {}: only {}ms of signup window left; abandoning before announcing.",
                    eventId, remaining);
            clearAndRelease(eventId);
            return;
        }

        stopSignupTask();
        signupsClosing.set(false);
        currentState = EventState.ANNOUNCED;
        cap = resolvedCap;
        firedReminders.clear();

        long secondsLeft = remaining / 1000;
        // Skip thresholds that are already behind us, or every one of them fires on consecutive
        // ticks the moment the window opens.
        for (int threshold : plugin.getConfigManager().getEventSignupReminders()) {
            if (threshold >= secondsLeft) {
                firedReminders.add(threshold);
            }
        }

        announcer.announceOpen(accept.arenaName(), 0, cap, secondsLeft, this::join);

        signupTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickSignupWindow(eventId, accept.signupDeadline()), 20L, 20L);
    }

    private void tickSignupWindow(String eventId, long deadline) {
        if (!isCurrent(eventId) || currentState != EventState.ANNOUNCED) {
            stopSignupTask();
            return;
        }

        long secondsLeft = (deadline - System.currentTimeMillis()) / 1000;
        if (secondsLeft <= 0) {
            stopSignupTask();
            closeSignups(eventId);
            return;
        }

        for (int threshold : plugin.getConfigManager().getEventSignupReminders()) {
            if (secondsLeft <= threshold && firedReminders.add(threshold)) {
                int soFar = lastKnownCount;
                announcer.announceReminder(soFar, cap, secondsLeft, this::join);
                break;
            }
        }
    }

    /**
     * Signup window is over: either hand the roster to the host, or call the whole thing off.
     */
    private void closeSignups(String eventId) {
        if (!signupsClosing.compareAndSet(false, true)) {
            return;
        }

        String host = hostServerId;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!isCurrent(eventId)) {
                return;
            }

            // Flip the stored state FIRST. The signup script admits a click only while the record
            // reads ANNOUNCED, so reading the roster before closing the door leaves a window where a
            // late clicker joins the Redis roster but not the roster we are about to send - and then
            // gets transferred across the proxy only to be refused on arrival.
            store.read(eventId).map(record -> record.withState(EventState.SIGNUP_CLOSED))
                    .ifPresent(store::write);

            Set<UUID> roster = signups.roster(eventId);
            int minPlayers = plugin.getConfigManager().getEventMinPlayers();

            if (roster.size() < minPlayers) {
                plugin.log("Event {} cancelled: only {}/{} signed up.", eventId, roster.size(), minPlayers);

                store.read(eventId).map(record -> record.cancelled(CancelReason.TOO_FEW_SIGNUPS))
                        .ifPresent(store::write);
                network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                        new Payloads.Cancelled(CancelReason.TOO_FEW_SIGNUPS, network.serverId()));
                store.retire(eventId);

                // Half the usual quiet period: too few signups is worth retrying sooner than a
                // match that actually ran.
                store.startCooldown(plugin.getConfigManager().getEventCooldownMinutes() * 30);

                int finalCount = roster.size();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    announcer.broadcast("magic.event.cancelled.not_enough",
                            "count", finalCount, "min", minPlayers);
                    clearLocal();
                });
                return;
            }

            List<String> ids = roster.stream().map(UUID::toString).toList();
            if (host == null) {
                // Envelope treats a null target as a broadcast, which would hand this roster to every
                // Bedwars server on the network rather than to the one holding an arena for it.
                plugin.log("Event {}: no host recorded; cannot close the roster.", eventId);
                Bukkit.getScheduler().runTask(plugin, this::clearLocal);
                return;
            }

            network.bus().send(NetworkRole.MINIGAME, MessageType.ROSTER_CLOSED, eventId, host,
                    new Payloads.RosterClosed(ids, ids.size()));

            plugin.log("Event {} roster closed with {} player(s).", eventId, ids.size());
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Only if nothing has moved us on already: ARENA_READY can be handled before this
                // task runs, and clobbering TRANSFERRING would re-open the double-transfer hole.
                if (currentState == EventState.ANNOUNCED) {
                    currentState = EventState.SIGNUP_CLOSED;
                }
            });
        });
    }

    /**
     * Records a newly added signup (the roster write has already succeeded). Never throws.
     */
    private void auditSignup(String eventId, UUID playerId, String playerName, int position) {
        try {
            plugin.getAudit().emit(dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter.AuditRow
                    .of("event.signup", dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome.COMMITTED)
                    .correlation(dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter.eventCorrelation(eventId))
                    .business(eventId)
                    .actor(playerId)
                    .subject(playerId)
                    .put("event_id", eventId)
                    .put("player_name", playerName)
                    .put("position", position)
                    .put("cap", cap));
        } catch (RuntimeException | LinkageError ignored) {
            // Audit is best effort.
        }
    }

    /**
     * Adds a player to the roster, from either the chat prompt or {@code /mb event join}.
     */
    public void join(Player player) {
        String eventId = currentEventId;
        if (eventId == null || currentState != EventState.ANNOUNCED) {
            player.sendMessage(plugin.getLocaleManager().formatMessage(player, "magic.event.signup.closed"));
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            SignupRegistry.Result result = signups.signUp(eventId, playerId, cap);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }

                switch (result.outcome()) {
                    case ADDED -> {
                        auditSignup(eventId, playerId, player.getName(), result.position());
                        lastKnownCount = result.position();
                        player.sendMessage(plugin.getLocaleManager().formatMessage(
                                "magic.event.signup.confirmed", "count", result.position(), "max", cap));
                        announcer.announceSignup(player.getName(), result.position(), cap);

                        if (result.position() >= cap) {
                            plugin.log("Event {} reached capacity; closing signups early.", eventId);
                            stopSignupTask();
                            closeSignups(eventId);
                        }
                    }
                    case ALREADY_SIGNED_UP -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.already"));
                    case FULL -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.full"));
                    case CLOSED -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage(player, "magic.event.signup.closed"));
                    case ERROR -> player.sendMessage(
                            plugin.getLocaleManager().formatMessage("magic.event.signup.error"));
                }
            });
        });
    }

    /**
     * Shows the announcement to one viewer without starting a drive.
     */
    public void previewAnnouncement(org.bukkit.command.CommandSender viewer) {
        announcer.preview(viewer, "<arena>",
                plugin.getConfigManager().getEventMaxPlayers(),
                plugin.getConfigManager().getEventSignupSeconds());
    }

    private void stopSignupTask() {
        BukkitTask task = signupTask;
        if (task != null) {
            task.cancel();
            signupTask = null;
        }
    }

    /**
     * The host is holding an arena. Move everybody who signed up.
     */
    private void onArenaReady(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.ArenaReady ready = network.bus().payload(envelope, Payloads.ArenaReady.class);
        if (ready == null) {
            return;
        }

        // Idempotent: a redelivered ARENA_READY must not transfer the whole roster a second time.
        if (currentState == EventState.TRANSFERRING || currentState == EventState.RUNNING) {
            return;
        }

        currentState = EventState.TRANSFERRING;
        String eventId = envelope.eventId();
        String target = ready.minigameServerName();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Set<UUID> roster = signups.roster(eventId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                for (UUID playerId : roster) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(plugin.getLocaleManager().formatMessage(player,
                                "magic.event.signup.transferring", "server", target));
                    }
                }

                // Never keep sending past the host's arrival window: a player who lands after it
                // closed is refused on arrival, having been moved across the proxy for nothing.
                int stagger = Math.max(1, plugin.getConfigManager().getTransferStaggerTicks());
                long budgetTicks = Math.max(0, (ready.transferDeadline() - System.currentTimeMillis()) / 50L);
                // No window left means nobody can arrive in time, not that everybody should go.
                int seats = budgetTicks <= 0 ? 0 : (int) Math.max(1, budgetTicks / stagger);

                List<UUID> travelling = roster.stream().limit(seats).toList();
                roster.stream().skip(seats).forEach(late -> {
                    plugin.log("No time left in the arrival window for {}; not transferring them.", late);
                    markNoShow(eventId, late);
                });

                plugin.getTransferService().transferStaggered(travelling, target,
                        missed -> markNoShow(eventId, missed));

                plugin.log("Transferring {} of {} player(s) to {} for event {}.",
                        travelling.size(), roster.size(), target, eventId);
            });
        });
    }

    /**
     * Handles somebody who signed up and then was not there when their turn to travel came.
     *
     * <p>Taking them off the roster matters for more than tidiness: the host starts as soon as
     * everyone on the roster has arrived, so a lingering no-show makes every match wait out its full
     * arrival window, and enough of them can have it cancelled for too few arrivals while the players
     * who did turn up stand in the lobby.
     */
    private void markNoShow(String eventId, UUID playerId) {
        plugin.log("Signed-up player {} was not online to transfer.", playerId);

        String host = hostServerId;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            signups.remove(eventId, playerId);

            if (host != null) {
                network.bus().send(NetworkRole.MINIGAME, MessageType.PLAYER_RETURN, eventId, host,
                        new Payloads.PlayerReturn(playerId.toString(),
                                dev.ua.ikeepcalm.bedwars.net.protocol.source.ReturnOutcome.NO_SHOW));
            }
        });
    }

    /**
     * A player is on their way back, with an outcome. Consumes the durable pending-return entry so
     * the record does not sit around, and greets them if they are already here.
     */
    private void onPlayerReturn(Envelope envelope) {
        Payloads.PlayerReturn returning = network.bus().payload(envelope, Payloads.PlayerReturn.class);
        if (returning == null || returning.uuid() == null) {
            return;
        }

        UUID playerId;
        try {
            playerId = UUID.fromString(returning.uuid());
        } catch (IllegalArgumentException malformed) {
            return;
        }

        plugin.getReturnGreeter().greetWhenReady(playerId, envelope.eventId(), returning.outcome());
    }

    private void onPlayerArrived(Envelope envelope) {
        Payloads.PlayerArrived arrived = network.bus().payload(envelope, Payloads.PlayerArrived.class);
        if (arrived != null && isCurrent(envelope.eventId())) {
            lastKnownCount = arrived.arrived();
        }
    }

    private void onEventStarted(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.EventStarted started = network.bus().payload(envelope, Payloads.EventStarted.class);
        currentState = EventState.RUNNING;

        journal.recordStarted(envelope.eventId());

        if (started != null) {
            announcer.broadcast("magic.event.started", "count", started.playerCount());
            plugin.log("Event {} is under way on {} with {} player(s).",
                    envelope.eventId(), started.arenaName(), started.playerCount());
        }
    }

    private void onEventFinished(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.EventFinished finished = network.bus().payload(envelope, Payloads.EventFinished.class);
        currentState = EventState.FINISHED;

        if (finished != null) {
            plugin.log("Event {} finished on {} ({} winners).",
                    envelope.eventId(), finished.arenaName(), finished.winners().size());
        }

        String eventId = envelope.eventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.startCooldown(plugin.getConfigManager().getEventCooldownMinutes() * 60);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void onReject(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Reject reject = network.bus().payload(envelope, Payloads.Reject.class);
        String reason = reject == null ? "unspecified" : reject.reason();

        plugin.log("Event {} rejected by {}: {}", envelope.eventId(),
                reject == null ? "?" : reject.minigameServerId(), reason);

        // Do NOT tear the event down here. With more than one Bedwars server, a server that rejects
        // before another has claimed the host slot would otherwise destroy an event the second one is
        // about to accept - leaving that server holding a reservation nothing will ever release. The
        // propose timeout is the correct authority on "nobody is going to take this".
        String eventId = envelope.eventId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean claimed = network.client().get(network.keys().eventHost(eventId)).isPresent();
            List<?> hosts = network.registry().aliveWithRole(NetworkRole.MINIGAME);

            // Safe to give up early only when this was the only candidate and nobody claimed it.
            if (!claimed && hosts.size() <= 1) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isCurrent(eventId) && currentState == EventState.PROPOSED) {
                        plugin.log("Event {} had no other candidate host; abandoning it now.", eventId);
                        clearAndRelease(eventId);
                    }
                });
            }
        });
    }

    private void onCancelled(Envelope envelope) {
        if (!isCurrent(envelope.eventId())) {
            return;
        }

        Payloads.Cancelled cancelled = network.bus().payload(envelope, Payloads.Cancelled.class);
        // Gson resolves an unknown enum constant to null rather than throwing, so a peer running a
        // build with one extra reason would NPE here - and clearLocal() below would never run,
        // wedging every future event until a restart.
        CancelReason reason = cancelled == null || cancelled.reason() == null
                ? CancelReason.ADMIN
                : cancelled.reason();

        plugin.log("Event {} cancelled ({}).", envelope.eventId(), reason);

        if (currentState == EventState.ANNOUNCED) {
            announcer.broadcast(reason.localeKey());
        }

        clearLocal();
    }

    /**
     * Cancels the in-flight event from this side.
     */
    public void cancel(CancelReason reason, Consumer<String> feedback) {
        String eventId = currentEventId;
        if (eventId == null) {
            feedback.accept("No event is in flight.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.read(eventId).map(record -> record.cancelled(reason)).ifPresent(store::write);
            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                    new Payloads.Cancelled(reason, network.serverId()));
            store.retire(eventId);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (currentState == EventState.ANNOUNCED) {
                    announcer.broadcast(reason.localeKey());
                }
                clearLocal();
                feedback.accept("Cancelled event " + eventId + ".");
            });
        });
    }

    private void clearAndRelease(String eventId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            store.purge(eventId);
            Bukkit.getScheduler().runTask(plugin, this::clearLocal);
        });
    }

    private void clearLocal() {
        stopSignupTask();
        cancelProposeTimeout();
        signupsClosing.set(false);
        firedReminders.clear();
        lastKnownCount = 0;
        cap = 0;
        currentEventId = null;
        currentState = null;
        hostServerId = null;
        hostServerName = null;
        arenaName = null;
    }

    private boolean isCurrent(String eventId) {
        return eventId != null && eventId.equals(currentEventId);
    }

    /**
     * Picks up an event this server proposed before a restart, or drops it if it is past saving.
     */
    public void recoverOnBoot() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Optional<String> active = store.activeEventId();
            if (active.isEmpty()) {
                return;
            }

            Optional<EventRecord> stored = store.read(active.get());
            if (stored.isEmpty()) {
                // Slot claimed but the record is gone: nothing can be recovered from it.
                store.releaseActiveSlot(active.get());
                return;
            }

            EventRecord record = stored.get();
            if (!network.serverId().equals(record.smpServerId()) || record.state().isTerminal()) {
                return;
            }

            // A drive whose window is still open can simply be picked up again: the roster lives in
            // Redis, not in memory, so nothing was lost with the restart. Throwing away a roster that
            // is sitting there intact would be gratuitous.
            if (record.state() == EventState.ANNOUNCED && record.minigameServerId() != null) {
                long remaining = record.signupDeadline() - System.currentTimeMillis();

                if (remaining > MIN_ANNOUNCE_WINDOW_MILLIS) {
                    Bukkit.getScheduler().runTask(plugin, () -> resumeRecruiting(record));
                    return;
                }

                if (remaining > -MIN_ANNOUNCE_WINDOW_MILLIS) {
                    // The window has only just lapsed; close it properly rather than discarding it.
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        adoptRecord(record);
                        closeSignups(record.eventId());
                    });
                    return;
                }
            }

            plugin.log("Abandoning event {} left over from a previous run.", record.eventId());
            store.write(record.cancelled(CancelReason.PROPOSER_GONE));
            network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, record.eventId(),
                    new Payloads.Cancelled(CancelReason.PROPOSER_GONE, network.serverId()));
            store.retire(record.eventId());
        });
    }

    /**
     * Re-adopts a drive that was still open when this server restarted, and runs out its window.
     */
    private void resumeRecruiting(EventRecord record) {
        adoptRecord(record);

        long secondsLeft = Math.max(0, (record.signupDeadline() - System.currentTimeMillis()) / 1000);
        plugin.log("Resuming event {} with {}s of signups left.", record.eventId(), secondsLeft);

        for (int threshold : plugin.getConfigManager().getEventSignupReminders()) {
            if (threshold >= secondsLeft) {
                firedReminders.add(threshold);
            }
        }

        announcer.announceReminder(0, cap, secondsLeft, this::join);

        String eventId = record.eventId();
        signupTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickSignupWindow(eventId, record.signupDeadline()), 20L, 20L);
    }

    private void adoptRecord(EventRecord record) {
        currentEventId = record.eventId();
        currentState = record.state();
        hostServerId = record.minigameServerId();
        hostServerName = record.minigameServerName();
        arenaName = record.arenaName();
        cap = Math.max(plugin.getConfigManager().getEventMinPlayers(),
                Math.min(plugin.getConfigManager().getEventMaxPlayers(), record.maxPlayers()));
        signupsClosing.set(false);
    }

    /**
     * Gives up whatever is in flight so the network is not left waiting on a server that has gone.
     */
    public void shutdown() {
        stopSignupTask();
        cancelProposeTimeout();

        BukkitTask scheduled = scheduleTask;
        if (scheduled != null) {
            scheduled.cancel();
            scheduleTask = null;
        }

        String eventId = currentEventId;
        if (eventId == null) {
            return;
        }

        // Synchronous: this is shutdown, and leaving the record behind would have the host hold its
        // arena until the reaper notices our heartbeat is gone.
        store.read(eventId).map(record -> record.cancelled(CancelReason.PROPOSER_GONE))
                .ifPresent(store::write);
        network.bus().broadcast(NetworkRole.MINIGAME, MessageType.EVENT_CANCELLED, eventId,
                new Payloads.Cancelled(CancelReason.PROPOSER_GONE, network.serverId()));
        store.retire(eventId);
    }

    public Optional<String> hostServerName() {
        return Optional.ofNullable(hostServerName);
    }
}
