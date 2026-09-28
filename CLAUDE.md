# MythicBedwars Developer Guide

**MythicBedwars** is a magical Bedwars addon. It integrates the magic and Beyonder pathway system
from **Circle of Imagination** (COI) into **Marcely's Bedwars** (MBedwars) gameplay, and runs
cross-server "event" matches recruited from a survival server.

---

## 🌐 The single most important architectural fact

**One jar, two roles.** `network.role` in `config.yml` decides which half of the plugin boots:

| Role | Where it runs | MBedwars |
|---|---|---|
| `MINIGAME` | the Bedwars server | **required** |
| `SMP` | the survival server players are recruited from | **not installed** |

MBedwars is therefore a `softdepend`, not a `depend`. On an `SMP` instance **no MBedwars class may
be loaded at all** — not via a field type, a method signature, a listener registration, a static
initialiser, or a `switch` on one of its enums. Bukkit resolves a listener's handler parameter types
eagerly at registration, so registering a Bedwars-facing listener there fails with
`NoClassDefFoundError`.

Practical rules when editing:

- Anything reaching `de.marcely.bedwars` is constructed only inside `enableMinigameRole()`.
- Role-neutral code asks the plugin class (`isEventArena`, `getReservedArenaNames`,
  `cancelHostedEvents`, `getEventParticipant`), which answers safely when the minigame half is absent.
- Subcommand *names* live in `cmd/Subcommands.java`, deliberately apart from the class that
  implements them, so the router can recognise a Bedwars subcommand without loading it.
- `network.enabled: false` leaves the role intact and simply skips the transport, so an existing
  single-server install behaves exactly as it always did.

---

## 🛠️ Build and Development Commands

Gradle, targeting **Java 25**.

> **Gradle 8.14 cannot run on a Java 25 JVM.** Point `JAVA_HOME` at a JDK 21 install; the
> `toolchain` block still compiles against 25.
>
> ```bash
> export JAVA_HOME=/c/Program\ Files/Eclipse\ Adoptium/jdk-21.0.9.10-hotspot
> ```

- **Compile**: `./gradlew compileJava`
- **Build the jar**: `./gradlew build`
- **Run a local Paper server**: `./gradlew runServer`

The only bundled dependency is the shared Mysterria audit client
(`dev.ua.ikeepcalm.mysterria:audit-client`, resolved from `mavenLocal()`), shaded by the shadow plugin
and relocated to `dev.ua.ikeepcalm.bedwars.libs.audit`; the plain `jar` task is disabled so exactly one
jar lands in `build/libs`. Jedis, commons-pool2 and sqlite-jdbc are resolved at runtime through
`libraries:` in `plugin.yml`, and their versions are declared once in `build.gradle` and expanded into
`plugin.yml` by `processResources`. Bump them together or not at all. Audit rows are documented in
`docs/AUDIT_EVENTS.md`.

---

## 🏗️ Project Layout

Sources live under `src/main/java/dev/ua/ikeepcalm/bedwars/`.

```
MythicBedwars
├── cmd/
│   ├── impls/
│   │   ├── EventCommand.java        # /mb event ... (both roles)
│   │   ├── PlayerCommand.java       # /bedwars - the player-facing half (both roles)
│   │   ├── MinigameSubcommands.java # stats/arena/balance/pathways/voting - MBedwars-facing
│   │   ├── SpectatorCommand.java    # spectator HUD and targeting
│   │   ├── UnavailableCommand.java  # stub bound to minigame-only commands in the SMP role
│   │   └── VotingDebugCommand.java  # voting diagnostics
│   ├── CommandManager.java          # router; permissions are per subcommand, not on the command
│   └── Subcommands.java             # role-neutral subcommand names and permission nodes
├── config/
│   ├── ConfigBackfill.java          # writes new keys into config.yml, removes retired ones
│   ├── LocaleBackfill.java          # merges new keys into the operator's lang-*.yml
│   ├── ConfigLoader.java            # one getter per key, inline defaults
│   ├── LocaleLoader.java            # EN + UK, resolved per recipient
│   └── NetworkRole.java             # SMP | MINIGAME
├── domain/
│   ├── balancer/PathwayBalancer.java
│   ├── core/                        # PathwayManager, ShopManager, StatisticsManager,
│   │                                #   MaterialGrantService, ArenaEnvironmentService
│   ├── item/                        # PotionItemSession, PotionShopItem,
│   │                                #   MaterialShopItem, MaterialKind, SandboxItems
│   ├── reward/                      # the cross-server reward pipeline
│   │   ├── model/RewardModel.java   # kinds, tiers, entries, grants, bundles
│   │   ├── CoiCapabilities.java     # what the loaded COI can actually do
│   │   ├── MatchContributionTracker.java
│   │   ├── RewardConfig.java        # rewards.yml
│   │   ├── RewardQueue.java         # Redis handoff, both idempotency guards
│   │   ├── RewardRedeemer.java      # applies on the SMP
│   │   ├── RewardRoller.java        # rolls tiers and token ceilings
│   │   └── RewardService.java       # decides who earned what, at round end
│   ├── runnable/                    # ActingProgressionTask, PathwayVerificationTask, VotingReminderTask
│   ├── spectator/SpectatorManager.java
│   ├── stats/db/                    # SQLiteDatabase, DatabaseMigration, PathwayStats
│   └── voting/                      # MagicMode, VotingSession, VotingManager
├── integration/PlanDataExtension.java   # loaded reflectively; Plan is optional
├── listener/                        # Arena, Damage, Player, Spectator, Voting, ServerShutdown,
│                                    #   ProjectileCooldown
├── net/
│   ├── EventParticipant.java        # the local half of an event, role-neutrally
│   ├── EventReaperTask.java         # sweeps events that stopped progressing
│   ├── EventSyncTask.java           # reconciles local state against the durable record
│   ├── NetworkService.java          # facade: client, bus, keys, heartbeat, registry
│   ├── event/                       # EventRecord, EventStore  (the source of truth)
│   ├── health/                      # HeartbeatTask, ServerRegistry
│   ├── minigame/                    # host side: orchestrator, selector, reservation,
│   │                                #   guard, listener, lobby hold, return service
│   ├── protocol/                    # Envelope, Heartbeat, payloads, enums
│   ├── smp/                         # recruit side: manager, announcer, signups, listener,
│   │                                #   ReturnGreeter, ScheduleJournal (schedule.yml),
│   │                                #   ScheduleAnnouncer (the player-facing countdown)
│   ├── transport/                   # RedisClient/JedisRedisClient, RedisBus, RedisKeys, LuaScripts
│   └── velocity/ServerTransferService.java
└── MythicBedwars.java               # onEnable/onDisable, role split
```

Resources: `config.yml`, `rewards.yml`, `plugin.yml`, `lang/lang-en.yml`, `lang/lang-uk.yml`.
`schedule.yml` is written at runtime, not bundled: it is the SMP's record of when the last event
went out, so `network.event.schedule` survives a restart of the server or of Redis.
Feature documentation lives in `docs/CROSS_SERVER_EVENTS.md`.

---

## 🎮 Command Reference

### `/mythicbedwars` (`/mb`, `/mbw`)

Not permission-gated as a command — see `CommandManager`'s javadoc for why. `mythicbedwars.admin`
is checked per subcommand.

| Subcommand | Role | Permission |
|---|---|---|
| `event join` | SMP | `mythicbedwars.event.join` (default true) |
| `event next` | SMP | `mythicbedwars.event.join` (default true) |
| `event status` | both | admin |
| `event preview` | SMP | admin |
| `event start` | SMP | admin |
| `event cancel` | both | admin |
| `event send <player> <smp\|minigame\|server>` | both | admin |
| `toggle` | both | admin |
| `reload` | both | admin — config, locales **and** `rewards.yml` |
| `stats`, `arena`, `balance`, `pathways`, `voting` | MINIGAME | admin |

`mythicbedwars.event.exempt` (default false) opts the holder out of recruitment broadcasts.

### `/bedwars`

The player-facing command; `/mythicbedwars` stays the admin tool. Role-neutral, registered on both
backends, and not permission-gated at the command level for the same reason as `/mb`. Bare
`/bedwars` answers the common question (when is the next one) and points at `join`.

| Subcommand | Role | Permission |
|---|---|---|
| *(none)* / `next` / `when` | SMP | `mythicbedwars.event.join` (default true) |
| `join` | SMP | `mythicbedwars.event.join` (default true) |

Declared with **no aliases on purpose**: MBedwars owns `/bw` and very likely lists `bedwars` among
its own aliases. A primary command name beats another plugin's alias in Bukkit's command map, so
`/bedwars` should win — but `/mb event next`, `/mb event join` and `/mythicbedwars:bedwars` remain
as fallbacks if it ever does not.

### `/mbspec` (`/mbspectator`, `/mythicspec`)

`mythicbedwars.spectator`. Bound to a stub in the SMP role.

`toggle <hud|actionbar|detailed>` · `target [player]` · `teams` · `inspect <player>` · `settings`.
Right-clicking a player while spectating opens their inspect overview.

---

## 🔮 Magic modes

The lobby vote is a **three-way** `MagicMode`, not a boolean:

| Mode | Pathways |
|---|---|
| `TEAM` | one per team, shared — the original behaviour and the default |
| `INDIVIDUAL` | one per player, drawn distinctly across the whole arena |
| `OFF` | ordinary Bedwars |

`INDIVIDUAL` exists because pathways are not equally suited to Bedwars, so a team that drew a
non-combat one was behind before the round started. Spreading the draw over players leaves every
team holding a mix.

Consequences worth knowing:

- **Ask the player, not the team.** `PathwayManager#getPlayerPathway` is what nearly every caller
  wants. `getTeamPathway` is only correct in `TEAM` mode; `getTeamPathwayDisplay` is the spectator
  view, and lists several pathways per team in `INDIVIDUAL` mode.
- **`getExpectedPathway` is deliberately not `getPlayerPathway`.** The verification task compares
  the two, so reading the expected value out of the player's own state would make the comparison
  tautological.
- **Statistics go through `getRoundOutcomes`,** not through teams: in `INDIVIDUAL` mode a team
  holds several pathways and a pathway may sit on several teams.
- **Individual draws are lazy,** made as each loadout opens, because a player's team is not settled
  until MBedwars' auto-balancer has run — and the draw records the team it was made for, so an
  eliminated player still counts at `RoundEndEvent`.

---

## 🎨 Coding Guidelines

1. **Java 25** target. See the `JAVA_HOME` note above.
2. **Kyori Adventure** for all chat, bossbars and titles. Never hardcode `§`; legacy `&` codes belong
   only in the locale files, which `LocaleLoader` deserializes with `legacyAmpersand()`. A
   MiniMessage tag in a locale string renders **literally**.
3. **Locale strings use flat `{name}` placeholders.** Prefer the `CommandSender` overloads
   (`formatMessage(player, key, …)`) so the recipient's own language is used — the plain overloads
   always resolve to EN.
4. **Database work is async** via `CompletableFuture`, except in `onDisable`, where synchronous saves
   guarantee persistence.
5. **Thread safety.** `PathwayManager`, `SpectatorManager` and everything under `net/` are touched
   from Bukkit tasks, async tasks and the Redis subscriber thread. Use concurrent collections and
   `volatile`.
6. **Redis is never touched on the main thread.** `RedisBus` hops handlers *to* the main thread for
   Bukkit safety, which makes it easy to accidentally do blocking I/O there — push every round trip
   back out with `runTaskAsynchronously`.
7. **A server cannot message itself.** The bus dedups its own outgoing message ids, so publishing to
   your own role's channel is a no-op locally. Inform the local half through `EventParticipant`.
8. **Dependencies**
   - `MBedwars` 5.5.5+ — `softdepend`; runtime-gated in `checkMBedwarsSupported()`. Compiles against
     the 5.5.7 API.
   - `CircleOfImagination` 1.4.8-SNAPSHOT — `depend`. Newer additions are probed at runtime by
     `CoiCapabilities`; never name a newly added enum constant as a compile-time constant.
   - `Plan` — optional, reached reflectively in `registerPlanStatistics`.
   - `Jedis` / `commons-pool2` / `sqlite-jdbc` — runtime-only via `libraries:`.

---

## ⚠️ Traps worth knowing

- **COI sequence numbers are inverted.** Lower is *stronger*. A token's `maxSequence` is a power
  ceiling, and the exchange GUI walks 9 *down* to it, so `9` offers only the weakest tier and `0`
  offers everything.
- **`setActingSpeedMultiplier` / `setActingItemMultiplier` take a duration, not a timestamp.** COI
  adds "now" itself.
- **`grantActing` returns 0 for a pathway the player does not hold**, so no reward may name a
  pathway; everything resolves against their real primary one.
- **`KickReason#isRageQuit()` is far broader than its name** — it also covers `SERVER_DISCONNECT`,
  `TELEPORT`, `SPECTATE` and any third-party `PLUGIN` kick. Match on `LEAVE` explicitly.
- **`RoundEndEvent` reports every collection as empty on a tie.** That is the documented contract.
- **`Arena#setLobbyTimeRemaining` returns a boolean and does nothing below the arena's own
  `minPlayers`.** Check it.
- **`off`, `on`, `yes` and `no` are booleans in YAML 1.1**, including as *keys* — an unquoted
  `off:` parses as `false` and every lookup under it misses silently. `MagicMode#key` therefore
  maps `OFF` to `disabled` rather than relying on quotes, which `LocaleBackfiller` would have to
  re-emit correctly on every rewrite. Pick a non-boolean word for any new key.
- **A new lang key needs nothing extra to *work*** — `LocaleLoader` calls `setDefaults` with the
  bundled file — **but it needs `LocaleBackfiller` to be *visible***. That runs automatically over
  `lang-en.yml` and `lang-uk.yml`; nothing is needed per key.
- **COI casts ability projectiles as plain `Snowball`/`Arrow`/`SmallFireball` with no shared
  marker.** `ProjectileCooldownListener` therefore gates on the *launching item* — a real bow, an
  MBedwars special id, a throwable held in hand — and never on the projectile entity. Inspecting
  the entity would throttle abilities too.
- **`network.event.magic-mode: RANDOM` rolls on every read.** `ConfigLoader.resolveEventMagicMode`
  is named for it; the host resolves it once and carries the answer on `EventReservation`, because
  the value is seeded into the vote result at three points in an event's life.
