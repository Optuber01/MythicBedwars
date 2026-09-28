package dev.ua.ikeepcalm.bedwars;

import dev.ua.ikeepcalm.bedwars.audit.BedwarsAuditEmitter;
import dev.ua.ikeepcalm.bedwars.cmd.CommandManager;
import dev.ua.ikeepcalm.bedwars.cmd.impls.PlayerCommand;
import dev.ua.ikeepcalm.bedwars.cmd.impls.MinigameSubcommands;
import dev.ua.ikeepcalm.bedwars.cmd.impls.SpectatorCommand;
import dev.ua.ikeepcalm.bedwars.cmd.impls.UnavailableCommand;
import dev.ua.ikeepcalm.bedwars.config.ConfigLoader;
import dev.ua.ikeepcalm.bedwars.config.LocaleLoader;
import dev.ua.ikeepcalm.bedwars.config.NetworkRole;
import dev.ua.ikeepcalm.bedwars.domain.balancer.PathwayBalancer;
import dev.ua.ikeepcalm.bedwars.domain.core.PathwayManager;
import dev.ua.ikeepcalm.bedwars.domain.core.ArenaEnvironmentService;
import dev.ua.ikeepcalm.bedwars.domain.core.MaterialGrantService;
import dev.ua.ikeepcalm.bedwars.domain.core.ShopManager;
import dev.ua.ikeepcalm.bedwars.domain.core.StatisticsManager;
import dev.ua.ikeepcalm.bedwars.domain.reward.*;
import dev.ua.ikeepcalm.bedwars.domain.runnable.ActingProgressionTask;
import dev.ua.ikeepcalm.bedwars.domain.runnable.PathwayVerificationTask;
import dev.ua.ikeepcalm.bedwars.domain.spectator.SpectatorManager;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.DatabaseMigration;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.PathwayStats;
import dev.ua.ikeepcalm.bedwars.domain.stats.db.SQLiteDatabase;
import dev.ua.ikeepcalm.bedwars.domain.voting.service.VotingManager;
import dev.ua.ikeepcalm.bedwars.listener.*;
import dev.ua.ikeepcalm.bedwars.net.EventReaperTask;
import dev.ua.ikeepcalm.bedwars.net.EventSyncTask;
import dev.ua.ikeepcalm.bedwars.net.NetworkService;
import dev.ua.ikeepcalm.bedwars.net.event.EventStore;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventArenaGuard;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventArenaListener;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventOrchestrator;
import dev.ua.ikeepcalm.bedwars.net.minigame.EventReturnService;
import dev.ua.ikeepcalm.bedwars.net.protocol.source.CancelReason;
import dev.ua.ikeepcalm.bedwars.net.smp.RecruitmentManager;
import dev.ua.ikeepcalm.bedwars.net.smp.ScheduleAnnouncer;
import dev.ua.ikeepcalm.bedwars.net.smp.SmpEventListener;
import dev.ua.ikeepcalm.bedwars.net.velocity.ServerTransferService;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MythicBedwars extends JavaPlugin {

    private static MythicBedwars instance;

    private NetworkRole networkRole = NetworkRole.MINIGAME;

    private ConfigLoader configLoader;
    private LocaleLoader localeLoader;
    private CommandManager commandManager;

    private PathwayManager pathwayManager;
    private ShopManager shopManager;
    private MaterialGrantService materialGrantService;
    private ArenaEnvironmentService arenaEnvironmentService;
    private ProjectileCooldownListener projectileCooldownListener;
    private StatisticsManager statisticsManager;
    private PathwayBalancer pathwayBalancer;
    private SpectatorManager spectatorManager;
    private VotingManager votingManager;

    private SQLiteDatabase database;
    private BukkitTask periodicSaveTask;
    private EventReaperTask eventReaperTask;
    private EventSyncTask eventSyncTask;

    /** Held so the periodic network tasks can be rebuilt by {@code /mb reload}. */
    private EventStore eventStore;
    private dev.ua.ikeepcalm.bedwars.net.smp.ReturnGreeter returnGreeter;
    private int saveIntervalSeconds;

    private CircleOfImaginationAPI circleOfImaginationAPI;
    private CoiCapabilities coiCapabilities;
    private NetworkService networkService;
    private ServerTransferService transferService;
    private EventOrchestrator eventOrchestrator;
    private RecruitmentManager recruitmentManager;
    private ScheduleAnnouncer scheduleAnnouncer;
    private EventReturnService returnService;
    private RewardService rewardService;
    private RewardConfig rewardConfig;
    private BedwarsAuditEmitter auditEmitter;

    public static MythicBedwars getInstance() {
        return instance;
    }

    public CircleOfImaginationAPI getCircleOfImaginationAPI() {
        return this.circleOfImaginationAPI;
    }

    public CoiCapabilities getCoiCapabilities() {
        return this.coiCapabilities;
    }

    /**
     * @return the cross-server plumbing, or {@code null} when {@code network.enabled} is off
     */
    public NetworkService getNetworkService() {
        return this.networkService;
    }

    public ServerTransferService getTransferService() {
        return this.transferService;
    }

    public EventOrchestrator getEventOrchestrator() {
        return this.eventOrchestrator;
    }

    public EventReturnService getReturnService() {
        return this.returnService;
    }

    public RewardService getRewardService() {
        return this.rewardService;
    }

    /**
     * @return the audit emitter; never null once {@code onEnable} has started, and a no-op when the
     * shared audit client is unavailable
     */
    public BedwarsAuditEmitter getAudit() {
        return auditEmitter;
    }

    public RewardConfig getRewardConfig() {
        return this.rewardConfig;
    }

    /**
     * @return the arenas currently held for events, or an empty set in any role that is not hosting.
     * Role-neutral on purpose, so shared code (commands, diagnostics) never has to name a
     * minigame-only type.
     */
    public java.util.Set<String> getReservedArenaNames() {
        return eventOrchestrator == null ? java.util.Set.of() : eventOrchestrator.reservations().keySet();
    }

    /**
     * Releases every arena this server is holding for an event.
     *
     * @return how many were released
     */
    public int cancelHostedEvents(CancelReason reason) {
        if (eventOrchestrator == null) {
            return 0;
        }

        var held = eventOrchestrator.reservations();
        held.values().forEach(reservation -> eventOrchestrator.cancel(reservation.eventId(), reason));
        return held.size();
    }

    public RecruitmentManager getRecruitmentManager() {
        return this.recruitmentManager;
    }

    /**
     * @return the player-facing countdown, or {@code null} in the minigame role, where there is no
     * schedule to count down
     */
    public ScheduleAnnouncer getScheduleAnnouncer() {
        return this.scheduleAnnouncer;
    }

    private static String format(String template, Object... objects) {
        if (objects == null || objects.length == 0) {
            return template;
        }

        StringBuilder out = new StringBuilder(template.length() + 16);
        int cursor = 0;
        int next = 0;

        while (next < objects.length) {
            int at = template.indexOf("{}", cursor);
            if (at < 0) {
                break;
            }
            out.append(template, cursor, at);
            Object value = objects[next++];
            out.append(value == null ? "null" : value.toString());
            cursor = at + 2;
        }

        out.append(template, cursor, template.length());
        return out.toString();
    }

    /**
     * @return the survival-server greeter, or {@code null} in the minigame role
     */
    public dev.ua.ikeepcalm.bedwars.net.smp.ReturnGreeter getReturnGreeter() {
        return this.returnGreeter;
    }

    /**
     * @return whichever half of the event system this role runs, or {@code null} when events are
     * switched off. Role-neutral so the reaper and the sync pass never name a role-specific type.
     */
    public dev.ua.ikeepcalm.bedwars.net.EventParticipant getEventParticipant() {
        if (eventOrchestrator != null) {
            return eventOrchestrator;
        }
        return recruitmentManager;
    }

    /**
     * Runs Redis work off the main thread — except during shutdown, when it runs inline.
     *
     * <p>Bukkit clears {@code isEnabled} before invoking {@code onDisable}, and the scheduler
     * refuses tasks from a disabled plugin by throwing. A shutdown path that scheduled would abort
     * {@code onDisable} partway through and skip the final statistics save, so at that point a
     * blocking call is the correct trade.
     */
    public void offMainThread(Runnable task) {
        if (!isEnabled()) {
            task.run();
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, task);
    }

    /**
     * Whether an arena is currently reserved for a cross-server event.
     *
     * <p>Lives on the plugin class so {@code ArenaListener} and {@code VotingManager} can ask
     * without importing anything role-specific, and answers a safe {@code false} whenever the event
     * system is not running.
     */
    public boolean isEventArena(String arenaName) {
        return eventOrchestrator != null && eventOrchestrator.isEventArena(arenaName);
    }

    /**
     * @return whether cross-server messaging is usable right now
     */
    public boolean isNetworkAvailable() {
        return networkService != null && networkService.isAvailable();
    }

    @Override
    public void onEnable() {
        instance = this;

        // First, so every later subsystem (and an early disable) can rely on it being present.
        auditEmitter = new BedwarsAuditEmitter(this);

        ConfigurationSerialization.registerClass(PathwayStats.class);

        configLoader = new ConfigLoader(this);
        configLoader.loadConfig();

        localeLoader = new LocaleLoader(this, LocaleLoader.Locale.EN);
        localeLoader.loadLocales();

        // Cross-server messaging is opt-in, but the ROLE is not tied to it: a survival server with
        // networking switched off for maintenance still has no MBedwars, and forcing it to MINIGAME
        // there would disable the whole plugin with a misleading "MBedwars not found".
        this.networkRole = configLoader.getNetworkRole();
        if (configLoader.hasUnparseableNetworkRole()) {
            log("Could not read network.role '{}' - falling back to {}. Valid values are SMP and MINIGAME.",
                    configLoader.getRawNetworkRole(), networkRole);
        }

        circleOfImaginationAPI = loadCircleOfImagination();
        if (circleOfImaginationAPI == null) {
            log("CircleOfImagination not found! Disabling addon...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        rewardConfig = new RewardConfig(this);
        rewardConfig.load();

        coiCapabilities = CoiCapabilities.probe(circleOfImaginationAPI, rewardConfig.actingSourceName());
        if (coiCapabilities.isDegraded()) {
            log("Running against an older CircleOfImagination ({}) - event rewards will be substituted where unsupported.",
                    coiCapabilities.describe());
        }

        // Registered in both roles: the SMP sends recruits out, the minigame server sends them home.
        transferService = new ServerTransferService(this);
        transferService.register();

        commandManager = new CommandManager(this);
        bindCommand("mythicbedwars", commandManager, commandManager);

        PlayerCommand playerCommand = new PlayerCommand(this, commandManager.eventCommand());
        bindCommand("bedwars", playerCommand, playerCommand);

        boolean started = switch (networkRole) {
            case MINIGAME -> enableMinigameRole();
            case SMP -> enableSmpRole();
        };

        if (!started) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (configLoader.isNetworkEnabled()) {
            if (!validateNetworkIdentity()) {
                log("Cross-server events are disabled until network.yml identity settings are fixed.");
                return;
            }

            networkService = new NetworkService(this);
            startEventSubsystem();
        }
    }

    /**
     * Boots every Bedwars-facing subsystem. Everything constructed or registered here reaches
     * {@code de.marcely.bedwars}, so none of it may run in the {@link NetworkRole#SMP} role — in
     * particular {@link #registerEvents()}, because Bukkit resolves each handler's parameter types
     * eagerly at registration and would fail with {@code NoClassDefFoundError}.
     */
    private boolean enableMinigameRole() {
        if (!checkMBedwarsSupported()) {
            return false;
        }

        this.saveIntervalSeconds = configLoader.getAutoSaveInterval();

        pathwayManager = new PathwayManager();
        shopManager = new ShopManager(this);
        materialGrantService = new MaterialGrantService(this);
        arenaEnvironmentService = new ArenaEnvironmentService(this);

        database = new SQLiteDatabase(this);
        database.initialize();

        pathwayBalancer = new PathwayBalancer(this);
        spectatorManager = new SpectatorManager(this);
        votingManager = new VotingManager(this);

        commandManager.installMinigameSubcommands(new MinigameSubcommands(this));

        SpectatorCommand spectatorCommand = new SpectatorCommand(this);
        bindCommand("mbspec", spectatorCommand, spectatorCommand);

        registerEvents();
        shopManager.scheduleRegistration();

        statisticsManager = new StatisticsManager(this);
        loadStatistics().thenRun(this::startMinigameTasks);

        return true;
    }

    /**
     * Boots the survival-server half. MBedwars is not expected to be present, so this must not touch
     * any Bedwars-facing manager, listener, or task.
     */
    private boolean enableSmpRole() {
        UnavailableCommand unavailable = new UnavailableCommand(this, "magic.commands.minigame_only");
        bindCommand("mbspec", unavailable, unavailable);

        log("Booted in SMP role - Bedwars features are inactive on this server.");
        return true;
    }

    private boolean checkMBedwarsSupported() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MBedwars")) {
            log("MBedwars not found! Disabling addon...");
            return false;
        }

        final int supportedAPIVersion = 205;
        final String supportedVersionName = "5.5.5";

        try {
            Class<?> apiClass = Class.forName("de.marcely.bedwars.api.BedwarsAPI");
            int apiVersion = (int) apiClass.getMethod("getAPIVersion").invoke(null);

            if (apiVersion < supportedAPIVersion) throw new IllegalStateException();
        } catch (Exception e) {
            log("Sorry, your installed version of MBedwars is not supported. Please install at least v" + supportedVersionName);
            return false;
        }

        return true;
    }

    private void startMinigameTasks() {
        Bukkit.getScheduler().runTask(this, this::registerPlanStatistics);

        new ActingProgressionTask(this).runTaskTimer(this, 20L, 20L);
        new PathwayVerificationTask(this).runTaskTimer(this, 200L, 400L);

        scheduleStatisticsSave();

        log("MythicBedwars enabled!");
    }

    /**
     * Refuses to join the network without a usable identity.
     *
     * <p>Both of these fail silently and confusingly if left at their defaults. Two servers sharing
     * a {@code server-id} overwrite each other's heartbeat and each accepts messages addressed to
     * the other; a blank {@code velocity.this-server} makes every transfer request a no-op, so an
     * entire roster is counted as no-shows with nothing but per-player log lines to explain it.
     */
    private boolean validateNetworkIdentity() {
        boolean ok = true;

        String serverId = configLoader.getServerId();
        if (serverId == null || serverId.isBlank()) {
            log("network.server-id is not set. Give every backend its own unique id.");
            ok = false;
        }

        String thisServer = configLoader.getThisVelocityServer();
        if (thisServer == null || thisServer.isBlank()) {
            log("network.velocity.this-server is not set. It must match this backend's name in velocity.toml.");
            ok = false;
        }

        String target = networkRole == NetworkRole.SMP
                ? configLoader.getMinigameVelocityServer()
                : configLoader.getSmpVelocityServer();
        if (target == null || target.isBlank()) {
            log("The Velocity name of the server players are sent to is not set; transfers cannot work.");
            ok = false;
        }

        return ok;
    }

    private void bindCommand(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            log("Command '{}' is missing from plugin.yml - skipping registration.", name);
            return;
        }

        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    private CircleOfImaginationAPI loadCircleOfImagination() {
        if (!Bukkit.getPluginManager().isPluginEnabled("CircleOfImagination")) {
            return null;
        }

        return Bukkit.getServer().getServicesManager().load(CircleOfImaginationAPI.class);
    }


    /**
     * (Re)schedules the periodic statistics save at the currently configured interval.
     *
     * <p>Reads {@code statistics.save-interval-seconds} on every call rather than trusting the value
     * captured at startup, so {@code /mb reload} can change the cadence — or switch it off.
     */
    private void scheduleStatisticsSave() {
        if (periodicSaveTask != null) {
            periodicSaveTask.cancel();
            periodicSaveTask = null;
        }

        this.saveIntervalSeconds = configLoader.getAutoSaveInterval();

        if (this.saveIntervalSeconds <= 0) {
            log("Periodic statistics saving is disabled (save-interval-seconds <= 0).");
            return;
        }

        if (database == null || statisticsManager == null) {
            return;
        }

        long saveIntervalTicks = this.saveIntervalSeconds * 20L;
        this.periodicSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (database.isConnected() && statisticsManager.getPathwayStatistics() != null && !statisticsManager.getPathwayStatistics().isEmpty()) {
                log("Periodically saving statistics...");
                database.saveStatistics(statisticsManager.getPathwayStatistics()).thenRun(() -> log("Periodic statistics save complete.")).exceptionally(ex -> {
                    log("Periodic statistics save failed: " + ex.getMessage());
                    return null;
                });
            } else if (!database.isConnected()) {
                log("Cannot perform periodic statistics save: Database not connected.");
            }
        }, saveIntervalTicks, saveIntervalTicks);

        log("Scheduled periodic statistics save every " + this.saveIntervalSeconds + " seconds.");
    }

    /**
     * Brings up the half of the event system this role is responsible for. Runs after the network
     * service, because both halves need the bus to register their handlers on.
     */
    private void startEventSubsystem() {
        EventStore store = new EventStore(networkService.client(), networkService.keys(),
                configLoader.getEventTtlSeconds());

        RewardQueue rewardQueue = new RewardQueue(this, networkService.client(),
                networkService.keys(), rewardConfig);

        if (isMinigameRole()) {
            returnService = new EventReturnService(this, networkService);
            rewardService = new RewardService(this, rewardConfig, rewardQueue);

            eventOrchestrator = new EventOrchestrator(this, networkService, store);
            eventOrchestrator.registerHandlers();

            Bukkit.getPluginManager().registerEvents(new EventArenaGuard(this, eventOrchestrator), this);
            Bukkit.getPluginManager().registerEvents(
                    new EventArenaListener(this, eventOrchestrator, returnService, rewardService), this);

        } else {
            recruitmentManager = new RecruitmentManager(this, networkService, store);
            recruitmentManager.registerHandlers();

            returnGreeter = new dev.ua.ikeepcalm.bedwars.net.smp.ReturnGreeter(this, networkService);

            RewardRedeemer redeemer = new RewardRedeemer(this, rewardConfig, rewardQueue);
            Bukkit.getPluginManager().registerEvents(
                    new SmpEventListener(this, redeemer, returnGreeter), this);

            recruitmentManager.startSchedule();

            scheduleAnnouncer = new ScheduleAnnouncer(this, recruitmentManager);
            scheduleAnnouncer.start();
        }

        // Only now: every handler is registered, so a backlog redelivered on subscribe is dispatched
        // rather than dropped.
        networkService.start();

        if (eventOrchestrator != null) {
            eventOrchestrator.recoverOnBoot();
        } else if (recruitmentManager != null) {
            recruitmentManager.recoverOnBoot();
        }

        this.eventStore = store;
        scheduleNetworkTasks();
    }

    /**
     * (Re)schedules the reaper and the reconciliation pass.
     *
     * <p>Both are {@code BukkitRunnable}s, which can only be scheduled once, and both snapshot config
     * values in their constructors because they run off the main thread — so picking up a changed
     * interval means building new instances rather than re-reading a field.
     */
    private void scheduleNetworkTasks() {
        if (networkService == null || eventStore == null) {
            return;
        }

        if (eventReaperTask != null) {
            eventReaperTask.cancel();
        }
        if (eventSyncTask != null) {
            eventSyncTask.cancel();
        }

        long reapTicks = Math.max(1L, configLoader.getEventReapIntervalSeconds()) * 20L;
        eventReaperTask = new EventReaperTask(this, networkService, eventStore);
        eventReaperTask.runTaskTimerAsynchronously(this, reapTicks, reapTicks);

        long syncTicks = Math.max(1L, configLoader.getEventSyncIntervalSeconds()) * 20L;
        eventSyncTask = new EventSyncTask(this, eventStore);
        eventSyncTask.runTaskTimerAsynchronously(this, syncTicks, syncTicks);
    }

    /**
     * Re-arms everything whose schedule is fixed at the moment it is created, so a config change can
     * take effect without a restart.
     *
     * <p>Deliberately does <b>not</b> touch the Redis connection, the network role, or the registered
     * listeners. Reconnecting a live pool or re-registering handlers underneath an in-flight event
     * trades a restart for a class of failure that is much harder to reason about; those keys stay
     * restart-only on purpose.
     *
     * @return a short description of what was re-armed, for the command to report
     */
    public java.util.List<String> reloadScheduledTasks() {
        java.util.List<String> rearmed = new ArrayList<>();

        if (networkService != null && eventStore != null) {
            scheduleNetworkTasks();
            rearmed.add("event reaper + sync");
        }

        if (recruitmentManager != null) {
            recruitmentManager.startSchedule();
            rearmed.add("event schedule");
        }

        if (scheduleAnnouncer != null) {
            scheduleAnnouncer.start();
            rearmed.add("event countdown");
        }

        if (statisticsManager != null && database != null) {
            scheduleStatisticsSave();
            rearmed.add("statistics save");
        }

        if (spectatorManager != null) {
            spectatorManager.restartUpdateTask();
            rearmed.add("spectator display");
        }

        return rearmed;
    }

    private CompletableFuture<Void> loadStatistics() {
        DatabaseMigration migration = new DatabaseMigration(this, database);

        return migration.migrateFromYaml().thenCompose(migrated -> {
            if (migrated) {
                log("Successfully migrated statistics from YAML to SQLite!");
            }

            return database.loadStatistics().thenAccept(loadedStats -> {
                if (loadedStats != null && !loadedStats.isEmpty()) {
                    statisticsManager.setPathwayStatistics(loadedStats);
                    log("Loaded " + loadedStats.size() + " pathway statistics entries from database.");
                } else {
                    log("No statistics data found in database.");
                }
            });
        });
    }

    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(new ArenaListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new DamageListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ServerShutdownListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SpectatorListener(this), this);
        Bukkit.getPluginManager().registerEvents(new VotingListener(this), this);

        projectileCooldownListener = new ProjectileCooldownListener(this);
        Bukkit.getPluginManager().registerEvents(projectileCooldownListener, this);
    }

    private void registerPlanStatistics() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Plan")) {
            return;
        }

        if (statisticsManager == null) {
            statisticsManager = new StatisticsManager(this);
        }

        try {
            // Use reflection to avoid ClassNotFoundException when Plan is not installed
            Class<?> capabilityServiceClass = Class.forName("com.djrapitops.plan.capability.CapabilityService");
            Object capabilityServiceInstance = capabilityServiceClass.getMethod("getInstance").invoke(null);
            boolean hasCapability = (boolean) capabilityServiceClass.getMethod("hasCapability", String.class)
                    .invoke(capabilityServiceInstance, "DATA_EXTENSION_VALUES");

            if (hasCapability) {
                Class<?> extensionServiceClass = Class.forName("com.djrapitops.plan.extension.ExtensionService");
                Object extensionServiceInstance = extensionServiceClass.getMethod("getInstance").invoke(null);

                if (extensionServiceInstance != null) {
                    // Load the PlanDataExtension class using reflection
                    Class<?> planExtensionClass = Class.forName("dev.ua.ikeepcalm.bedwars.integration.PlanDataExtension");
                    Object planExtension = planExtensionClass.getConstructor(StatisticsManager.class)
                            .newInstance(statisticsManager);

                    Class<?> dataExtensionClass = Class.forName("com.djrapitops.plan.extension.DataExtension");
                    extensionServiceClass.getMethod("register", dataExtensionClass)
                            .invoke(extensionServiceInstance, planExtension);
                    log("Successfully registered Plan statistics!");

                    capabilityServiceClass.getMethod("registerEnableListener", java.util.function.Consumer.class)
                            .invoke(capabilityServiceInstance, (java.util.function.Consumer<Boolean>) isPlanEnabled -> {
                                if (isPlanEnabled) registerPlanStatistics();
                            });
                }
            }
        } catch (ClassNotFoundException e) {
            log("Plan Player Analytics not found. Plan integration disabled.");
        } catch (Exception e) {
            log("Failed to register Plan statistics: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        try {
            shutdownSubsystems();
        } finally {
            if (auditEmitter != null) {
                auditEmitter.close();
            }
        }
    }

    private void shutdownSubsystems() {
        if (eventSyncTask != null) {
            eventSyncTask.cancel();
            eventSyncTask = null;
        }

        if (eventReaperTask != null) {
            eventReaperTask.cancel();
            eventReaperTask = null;
        }

        if (eventOrchestrator != null) {
            eventOrchestrator.shutdown();
            eventOrchestrator = null;
        }

        if (scheduleAnnouncer != null) {
            scheduleAnnouncer.stop();
            scheduleAnnouncer = null;
        }

        if (recruitmentManager != null) {
            recruitmentManager.shutdown();
            recruitmentManager = null;
        }

        if (networkService != null) {
            networkService.shutdown();
            networkService = null;
        }

        if (periodicSaveTask != null && !periodicSaveTask.isCancelled()) {
            periodicSaveTask.cancel();
            log("Cancelled periodic statistics save task.");
        }

        if (spectatorManager != null) {
            spectatorManager.shutdown();
            log("Spectator manager shut down.");
        }

        if (statisticsManager != null && database != null && database.isConnected()) {
            log("Saving final statistics synchronously on disable...");
            database.saveStatistics(statisticsManager.getPathwayStatistics(), true).thenRun(() -> {
                log("Final statistics saved.");
            }).exceptionally(ex -> {
                log("An unexpected issue occurred with the final save's CompletableFuture: " + ex.getMessage());
                return null;
            }).thenRun(() -> {
                database.close();
                log("Database connection closed.");
            });
        } else {
            if (database != null && !database.isConnected()) {
                log("Could not save final statistics: Database not connected.");
            } else if (database != null) {
                database.close();
                log("Database connection closed (statistics or manager was null).");
            }
        }

        if (pathwayManager != null) {
            pathwayManager.cleanupAll();
        }

        log("MythicBedwars disabled!");
    }

    /**
     * Console logging with {@code {}} placeholders.
     *
     * <p>Substitution walks the template once rather than calling {@code replaceFirst} per argument:
     * a value that itself contains a placeholder would otherwise swallow the next argument and shift
     * every placeholder after it.
     *
     * <p>Safe to call from any thread. Off the main thread it goes to the plugin logger, because the
     * console sender is a Bukkit API object and Adventure's console serializer is not built for
     * concurrent use.
     */
    public void log(String message, Object... objects) {
        if (message == null) return;

        String formatted = format(message, objects);

        if (!Bukkit.isPrimaryThread()) {
            getLogger().info(formatted);
            return;
        }

        Bukkit.getConsoleSender().sendMessage(Component.text("[MythicBedwars]").color(NamedTextColor.LIGHT_PURPLE).append(Component.text(" " + formatted)));
    }

    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage(Component.text("[MythicBedwars]").color(NamedTextColor.LIGHT_PURPLE).append(Component.text(" " + message)));
    }

    public NetworkRole getNetworkRole() {
        return networkRole;
    }

    public boolean isMinigameRole() {
        return networkRole == NetworkRole.MINIGAME;
    }

    public boolean isSmpRole() {
        return networkRole == NetworkRole.SMP;
    }

    public PathwayBalancer getPathwayBalancer() {
        return pathwayBalancer;
    }

    public ConfigLoader getConfigManager() {
        return configLoader;
    }

    public PathwayManager getArenaPathwayManager() {
        return pathwayManager;
    }

    /**
     * @return the crafting-input dispenser, or {@code null} in the SMP role, where there are no
     * matches to hand anything out in
     */
    public MaterialGrantService getMaterialGrantService() {
        return materialGrantService;
    }

    /**
     * @return the arena environment override, or {@code null} in the SMP role
     */
    public ArenaEnvironmentService getArenaEnvironmentService() {
        return arenaEnvironmentService;
    }

    /**
     * @return the projectile rate limiter, or {@code null} in the SMP role
     */
    public ProjectileCooldownListener getProjectileCooldownListener() {
        return projectileCooldownListener;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    public LocaleLoader getLocaleManager() {
        return localeLoader;
    }

    public StatisticsManager getStatisticsManager() {
        return statisticsManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public VotingManager getVotingManager() {
        return votingManager;
    }

    public SpectatorManager getSpectatorManager() {
        return spectatorManager;
    }

    public List<String> getAvailablePathways() {
        return new ArrayList<>(circleOfImaginationAPI.getAllPathwayNames());
    }
}
