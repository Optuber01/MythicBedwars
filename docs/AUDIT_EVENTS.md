# MythicBedwars audit events

MythicBedwars emits best-effort, staff-restricted events through its shaded Mysterria audit client
(`dev.ua.ikeepcalm.mysterria:audit-client`, relocated to `dev.ua.ikeepcalm.bedwars.libs.audit`).
The producer id is `mythicbedwars`, and every event type is prefixed `mythicbedwars.`. Events are
spooled to `plugins/mysterria-audit-spool` whether or not a local audit engine is installed.

Audit is optional and never changes gameplay. If the client fails to initialise, the plugin logs
one WARN and every emit becomes a no-op. Building and emitting a row is wrapped so that nothing it
throws can reach a reward, a command or a join. The client is closed in a `finally` in `onDisable`.

Privacy is always `STAFF_RESTRICTED`. Risk is `NORMAL` unless stated otherwise below.

## Roles

One jar serves both roles (see `CLAUDE.md`). What each row reports depends on where it runs:

| Role | Server | Rows |
| --- | --- | --- |
| `MINIGAME` | #4 Bedwars | `reward.bundle_emitted`, `event.match_finished`, `admin.command` (`event.cancel`, `event.send`, `toggle`, `reload`, `voting.*`, `pathways.*`) |
| `SMP` | main | `reward.bundle_redeemed`, `reward.acting_granted`, `reward.buff_applied`, `reward.item_granted`, `reward.dead_letter`, `item.sandbox_stripped`, `event.signup`, `admin.command` (`event.start`, `event.cancel`, `event.send`, `toggle`, `reload`) |

## Correlation

* **Reward rows** use a correlation id derived from `(eventId, playerId)` as
  `UUID.nameUUIDFromBytes("mythicbedwars:bundle:<eventId>:<playerUuid>")`. Anything after the first
  `:` in the event id is stripped first. As a result, the row emitted on #4 and every row written on
  main for the same player and match share one correlation, and so do the `:overflow`, `:retry`, and `:returnN`
  bundles derived from it. `businessId` is the bundle's full event id, including any suffix.
* **Event rows** (`event.match_finished`, `event.signup`, `admin.command` for `event.start` and
  the SMP `event.cancel`) use `UUID.nameUUIDFromBytes("mythicbedwars:event:<eventId>")`, and their
  `businessId` is the event id.
* Other admin commands get a fresh correlation per row.

Reward rows set `subjectId` to the player. No actor UUID is set, and `actor` metadata is `system`.
Admin rows set `actorId` to the staff player's UUID with `actor=player`. For console they set
`actor=console` and add `actor_name`.

## Event catalog

| Event type | Where | Trigger | Outcomes |
| --- | --- | --- | --- |
| `reward.bundle_emitted` | #4 | `RewardService.award`, after the bundle is pushed to Redis and counted against the daily tally | `COMMITTED`; `DENIED` (`rage_quit`, `too_short`, `inactive`, `afk`, `daily_cap`, `small_match`, `no_grants_rolled`, `already_emitted`); `FAILED` (`redis_unavailable`) |
| `reward.bundle_redeemed` | main | See "Bundle lifecycle" below | `COMMITTED`, `CANCELLED`, `FAILED`, `DENIED` |
| `reward.acting_granted` | main | Each acting grant, including the cooldown fallback, once `grantActing` has returned | `COMMITTED`; `DENIED` (`not_applicable`, `zero_points`, `source_capped`) |
| `reward.buff_applied` | main | Acting-speed and item-multiplier setters, and acting-cooldown credit | `COMMITTED`; `DENIED` (`kept_existing`, `no_acting_method`, `zero_seconds`, `cooldown_ready`) |
| `reward.item_granted` | main | One row per reward stack, after `addItem`, after a drop, or when CoI could not build the item | `COMMITTED` (`result` `placed` / `dropped`); `FAILED`, risk `HIGH` (`drop_failed`, `item_unavailable`) |
| `reward.dead_letter` | main | `RewardQueue.quarantine`: a payload that could not be read was parked on the dead-letter list | `OBSERVED`, or `FAILED` when the park itself failed; risk `HIGH` |
| `admin.command` | both | Staff commands; see below | `COMMITTED`, `DENIED`, `FAILED` |
| `event.match_finished` | #4 | `EventArenaListener.onRoundEnd` for an event arena, before rewards are rolled | `OBSERVED` |
| `item.sandbox_stripped` | main | On join, when `SandboxItems.strip` removed any match-issued stack | `COMMITTED`, risk `HIGH` |
| `event.signup` | main | A player was newly added to an event roster (`SignupRegistry` returned `ADDED`) | `COMMITTED` |

### Bundle lifecycle on main (`reward.bundle_redeemed`)

The `result` metadata says what happened:

| `result` | Outcome | Meaning |
| --- | --- | --- |
| `redeemed` | `COMMITTED` | Every grant was applied, placed, dropped or handed to an overflow bundle. Emitted after the last item is handled. |
| `redeemed_with_error` | `COMMITTED` | Every grant had settled, but an exception was thrown afterwards (for example while formatting the footer message). Carries `error`. |
| `requeued` | `CANCELLED` | The unchanged grants were pushed back under a fresh `<eventId>:returnN` id, and the original claim was kept. `reason` is `offline` (the player logged off between claim and apply) or `held_non_beyonder` (non-Beyonder with `non-beyonder-policy: HOLD`). |
| `failed_requeued` | `FAILED`, risk `HIGH` | `apply` threw partway. Only the grants that had not landed were re-queued, as a new bundle with id `<eventId>:retry`. Carries `error`, `original_event_id`, `settled` (grants that did land) and `remainder` (the re-queued grants). |
| `overflow_requeued` | `CANCELLED` | Whole stacks that did not fit were re-queued as `<eventId>:overflow` (`item-overflow: REQUEUE`). `reason` is `inventory_full`. |
| `requeue_failed` | `FAILED`, risk `HIGH` | Redis refused the push. The bundle is owed but queued nowhere. `remainder` lists the exact grants to re-issue by hand. |
| `claim_release_failed` | `FAILED`, risk `HIGH` | Historical row from the previous return-and-release flow. Current requeues keep the original claim and use a fresh id. |
| `discarded_duplicate` | `DENIED`, risk `HIGH`, reason `already_claimed` | A bundle was polled whose claim already existed, so it was dropped unapplied. This is the "same event id redeemed twice" signal. |

Push results come from Redis, so they are emitted asynchronously once Redis has answered.

## Metadata

Every reward row carries `actor=system`, `event_id`, and the grant or bundle context listed below.
Grants are serialised as
`KIND[/ITEM] TIER amount=<pct|mult> int=<seconds|arg> count=<n> [str=<tier>] [maxSeq=<n>] [epic]`,
joined with `; `, so a `remainder` can be re-issued exactly.

* **Bundle context** (`bundle_emitted`, `bundle_redeemed`): `player_name`, `arena`,
  `event_pathway`, `won`, `earned_at` (epoch ms), `grant_count`, `grants`, and `retry` (true when
  the id carries a `:overflow`, `:retry`, or `:returnN` suffix).
* **`bundle_emitted`**: `tie`, `mvp`, `participation_ratio`, `participation_scale`, `kills`,
  `final_kills`, `beds_broken`, `actions`, `paid_today`, `participation_only`. `small_match` rows
  carry `match_size` and `min_players` instead.
* **`bundle_redeemed` (`redeemed`)**: `beyonder`, `pathway`, `sequence` (the player's sequence in
  that pathway before redemption), `needed` (acting to the next sequence), `settled`, `dropped`,
  `overflow_requeued`. Requeue rows add `pushed`, `claim_released`, `original_event_id` and
  `settled`. `claim_released=true` also covers requeues that do not require a claim release.
* **Per-grant rows** (`acting_granted`, `buff_applied`, `item_granted`): `grant_kind`,
  `grant_tier` (`PARTICIPATION`/`WINNER`/`MVP`), `epic`.
* **`acting_granted`**: `via` (`acting_percent`, `cooldown_unsupported` or `cooldown_ready`, the
  last two being the cooldown-credit fallbacks), `pathway`, `sequence`, `needed`,
  `percent_requested`, `points_requested`, `granted` (the value CoI returned), `source` (the acting
  source category id), and `capped` (true when points were requested but nothing was granted).
* **`buff_applied`**: `buff` (`acting_speed`, `acting_item_multiplier`, `cooldown_credit`),
  `percent_requested`, `pathway`. Multipliers add `multiplier`, `previous_multiplier`,
  `duration_seconds` and `kept_existing`. Cooldown credit adds `method_id`, `seconds_requested`,
  `seconds_credited` and `seconds_remaining`.
* **`item_granted`**: `result`, `substituted` (the stack is an acting bottle standing in for a
  non-item grant to a non-Beyonder), `item_kind`, `item_tier`, `token_max_sequence` (exchange
  tokens), `grant_count`, `bottle_acting` (acting stored in a bottle), `material`, and `amount`
  (what actually landed: for a partly placed stack, the `placed` row has the placed amount and the
  `dropped` row has the rest). When CircleOfImagination stamped the stack, `item_uuid` and
  `parent_item_uuid` are top-level keys, read from the string PDC keys
  `circleofimagination:item_uuid` and `circleofimagination:item_parent`. `dropped` and
  `drop_failed` rows add the drop position as `world`/`x`/`y`/`z`. A drop counts as delivered only
  when the returned `Item` entity is valid and not dead; otherwise the row is `drop_failed` with
  the exact stack in `remainder`. `item_unavailable` means CoI returned no item, so the grant cannot
  be retried; `remainder` records it.
* **`dead_letter`**: `reason` (parser message), `payload_sha256`, `payload_length`, `parked`, and
  `player_id` (plus `subjectId`) when the payload still carries a readable `playerId`. The payload
  itself is never recorded.
* **`event.match_finished`**: `arena`, `winning_team`, `tie`, `winner_count`, `loser_count`,
  `winners_list`, `losers_list`, `quit_winners_list`, `quit_losers_list` (comma-separated UUIDs).
  On a tie, MBedwars reports empty lists, so `losers_list` is everyone still in the arena.
* **`item.sandbox_stripped`**: `player_name`, `stack_count`, `item_count`, `materials_list`
  (`MATERIALxAMOUNT`, comma-separated), the player position as `world`/`x`/`y`/`z`, and item UUIDs
  when exactly one stack was removed.
* **`event.signup`**: `actorId` and `subjectId` are the player. Adds `player_name`, `position` and
  `cap`.

### `admin.command`

`command` names the subcommand. All rows are emitted after the change is applied.

| `command` | Role | Metadata | Outcomes |
| --- | --- | --- | --- |
| `event.start` | SMP | `ignore_cooldown=true`, `event_id` on success | `COMMITTED` once an offer was published; `DENIED` with the precondition message as reason. Risk `HIGH`. |
| `event.cancel` | SMP | `role=smp`, `event_id` | `COMMITTED` after the record is retired; `DENIED` `no_event_in_flight`. Risk `HIGH`. |
| `event.cancel` | MINIGAME | `role=minigame`, `released_arenas`, `arenas` | `COMMITTED`; `DENIED` `no_hosted_event`. Risk `HIGH`. |
| `event.send` | both | `targetId`, `target_name`, `destination`, `result` | `COMMITTED` `transfer_requested`; `FAILED` `transfer_failed` (risk `HIGH`); `DENIED` `target_offline` / `unknown_destination` |
| `toggle` | both | `global_enabled` (the new state), `role` | `COMMITTED` |
| `reload` | both | `role`, `rewards_sha256_before`, `rewards_sha256_after`, `rewards_changed`, `tasks_rearmed` | `COMMITTED`, risk `HIGH` |
| `voting.force` | MINIGAME | `arena`, `mode`, `previous_mode` | `COMMITTED`; `DENIED` `unknown_mode` / `arena_not_found` |
| `voting.clear` | MINIGAME | `arena` | `COMMITTED` |
| `pathways.enable` / `pathways.disable` | MINIGAME | `pathway` | `COMMITTED` after `config.yml` is saved; `DENIED` `already_enabled` / `already_disabled` |

The reward hash is the SHA-256 of `rewards.yml` on disk as last loaded, after backfill. It is
`unreadable` if the file could not be read.

## Behaviour change: partial-apply retry

Previously, a `RuntimeException` anywhere in `RewardRedeemer.apply` pushed the **whole** bundle
back and released its claim, so the next login paid every grant that had already landed a second
time. Now each grant is marked settled as soon as its effect lands. A failure re-queues only the
unsettled grants, as `<eventId>:retry`, and the original claim is kept, so a stray copy of the
original can never be applied. For a partly placed stack, only the part that has not been handled
is owed. Offline and HOLD requeues also keep their claims and use fresh `:returnN` IDs.
Player messages, their order, and the overflow and drop policies are unchanged.

## Overlap policy

Console lines (`plugin.log`) are unchanged and remain the operational diagnostics. The Redis
pending, granted and claimed keys are transient (30-day TTL); the audit rows are the durable
record of what was paid.
