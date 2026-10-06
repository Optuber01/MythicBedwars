# MythicBedwars audit events

Producer `mythicbedwars`; every type is prefixed `mythicbedwars.`. Rows are spooled to
`plugins/mysterria-audit-spool` by the shaded audit client, privacy `STAFF_RESTRICTED`, risk `NORMAL`
unless noted. Reward rows share the correlation `nameUUIDFromBytes("mythicbedwars:bundle:<eventId before first ':'>:<playerUuid>")`;
event rows use `nameUUIDFromBytes("mythicbedwars:event:<eventId>")`.

The Server column is the network role that emits the row: `main` (SMP), `minigame`, or `both`.

| Event type | Server | Outcomes | Key facts |
| --- | --- | --- | --- |
| `reward.bundle_emitted` | minigame | `COMMITTED`; `DENIED` (`rage_quit`, `too_short`, `inactive`, `afk`, `daily_cap`, `small_match`, `no_grants_rolled`, `already_emitted`); `FAILED` (`redis_unavailable`) | player, arena, won/tie/mvp, contribution stats, `paid_today`, `grants` |
| `reward.bundle_redeemed` | main | `COMMITTED` (`redeemed`, `redeemed_with_error`); `CANCELLED` (`requeued` under a fresh `:returnN` id, `overflow_requeued`); `FAILED`, HIGH (`failed_requeued`, `requeue_failed`); `OBSERVED`, HIGH (`claim_unresolved`, reason `redis_unavailable`, settled by a later row for the same id); `DENIED`, HIGH (`discarded_duplicate`) | `result`, `grants`, `settled`, `remainder` (exact grants still owed), `original_event_id` |
| `reward.acting_granted` | main | `COMMITTED`; `DENIED` (`not_applicable`, `zero_points`, `source_capped`) | `via`, pathway, sequence, `needed`, `points_requested`, `granted`, `source` |
| `reward.buff_applied` | main | `COMMITTED`; `DENIED` (`kept_existing`, `no_acting_method`, `zero_seconds`, `cooldown_ready`) | `buff`, multiplier/previous/duration or cooldown seconds requested/credited/remaining |
| `reward.item_granted` | main | `COMMITTED` (`placed`, `dropped`); `FAILED`, HIGH (`drop_failed` when the world returned no entity, `item_unavailable`, `delivery_unknown` with reason `inventory_add_failed`) | material, landed `amount`, drop position, `error`, `remainder` on failure |
| `reward.dead_letter` | main | `OBSERVED`; `FAILED` when parking failed; HIGH | `payload_sha256`, `payload_length`, `parked`, `player_id` if readable; payload never recorded |
| `event.match_finished` | minigame | `OBSERVED` | arena, winning team, tie, winner/loser/quitter UUID lists |
| `event.signup` | main | `COMMITTED` | player, roster `position`, `cap` |
| `item.sandbox_stripped` | main | `COMMITTED`, HIGH | stack/item counts, `materials_list`, player position |
| `admin.command` `event.start` | main | `COMMITTED`; `DENIED` (precondition message), HIGH | `ignore_cooldown`, `event_id` |
| `admin.command` `event.cancel` | both | `COMMITTED`; `DENIED` (`no_event_in_flight` / `no_hosted_event`), HIGH; `FAILED` (`cancel_failed`), HIGH | `role`, `event_id` or released arenas; on the SMP also `state_before`, plus `error` on `FAILED` (the store or bus call threw, so some of the cancel may already have been applied) |
| `admin.command` `event.send` | both | `COMMITTED`; `FAILED` (`transfer_failed`, HIGH); `DENIED` (`target_offline`, `unknown_destination`) | target, destination |
| `admin.command` `toggle` | both | `COMMITTED` | `global_enabled`, `role` |
| `admin.command` `reload` | both | `COMMITTED`, HIGH; `FAILED` (`reload_failed`), HIGH | `rewards_sha256_before`/`_after`, `rewards_changed`, `config_sha256_before`/`_after`, `config_changed`, `tasks_rearmed`; on `FAILED` `stage` (`config`, `locales`, `rewards` or `tasks`: the stage that threw, earlier stages were already applied) and `error`. A `_before` hash is absent when the startup hash had not finished yet |
| `admin.command` `voting.force` | minigame | `COMMITTED`; `DENIED` (`unknown_mode`, `arena_not_found`) | arena, mode, `previous_mode` |
| `admin.command` `voting.clear` | minigame | `COMMITTED` (no arena check, so also for an arena that does not exist) | arena, `previous_mode`, `had_session` |
| `admin.command` `voting.test` | minigame | `OBSERVED` (the call gives no result: it skips event arenas, disabled arenas and running sessions); `DENIED` (`not_a_player`, `not_in_arena`) | arena, `arena_status` |
| `admin.command` `pathways.enable` / `pathways.disable` | minigame | `COMMITTED`; `DENIED` (`already_enabled`, `already_disabled`) | pathway |
| `admin.command` `arena.enable` / `arena.disable` | minigame | `COMMITTED` (the config save is synchronous and Bukkit swallows a write error) | arena, `action_arg` (anything other than `enable` disables), `enabled_after` |
| `admin.command` `balance.toggle` | minigame | `COMMITTED` (`/mb balance` with no arguments) | `auto_balance_before`, `auto_balance_after` |

Not recorded: in-arena acting changes (kills, bed breaks, the 1 Hz passive loop) act on the sandboxed virtual
Beyonder of a match and never reach persisted progression; the pathway statistics saves complete without a
result the plugin can read.

Also not recorded, because each needed a read on the server thread that only the row would use: the Circle of
Imagination `item_uuid` and `parent_item_uuid` on `reward.item_granted` (item meta and data container reads); whether a
dropped item entity is still valid (a drop that another plugin removes at once still shows as `dropped`; `drop_failed` is
only for a world that returned no entity); `signups` and the reserved `arenas` on `event.cancel`; the three vote counts on
`voting.clear` and `players` on `voting.test`; `enabled_before` on `arena.enable` / `arena.disable`.

Admin rows carry `actorId` for players or `actor=console` with `actor_name`; reward rows carry
`subjectId` and `actor=system`. Grants are serialised as
`KIND[/ITEM] TIER amount=.. int=.. count=.. [str=..] [maxSeq=..] [epic]`, joined with `; `.
