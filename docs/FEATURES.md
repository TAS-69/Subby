# Feature coverage

This checklist describes **1.1.0-beta.1 with Companion 0.1.0**. The original 1.0.0 release is more limited. “Implemented” means present in the source and covered by the relevant build/automated checks; it does not mean real-account or full gameplay acceptance has been completed.

| Requested area | Status and exact coverage |
| --- | --- |
| Automatic subscriber book | Implemented. New subs, gift recipients, and shared resub messages; automatic pages/volumes; recoverable live and snapshot books. |
| Book destinations | Implemented. Online inventories, selected player, lecterns, live book chests, and completed-volume archive chests. Unrelated contents are preserved. |
| Subscription metadata | Implemented when supplied: tier, cumulative months, shared streak, date/time, gift-batch giver/count, and current-subscriber recovery metadata. Individual recipient events do not reliably identify the gift giver. |
| Book styling | Implemented. Title/author, headers/footers, prefix/suffix, colours, line/paragraph/page layout, clickable names, symbols, milestones/streaks, monthly headings, Roman volumes, and opening pages. Founder labels are manual. |
| Twitch events | Implemented through Companion: subs, resubs, recipients, gift batches, Bits, rewards, raids, follows, Hype Train start/progress/end, poll/prediction results, moderator/VIP changes, first observed chat, and observed bans/unbans. Extras are opt-in. |
| Custom rewards | Implemented. Match redemption events by reward ID and configure their actions/conditions. Reward creation and reward refund/fulfilment management are not part of this preview. |
| Visual editor | Implemented. WHEN/IF/DO menus, event/action pickers, common-resource choices, thresholds, tier/month/reward/combo conditions, action amounts, durations, weights, and deletion. Text/custom IDs use private chat prompts. |
| Text templates | Implemented. Subscriber, gift, Bits, raid, reward, Hype Train, result, date, and combo placeholders. Missing information is not invented. |
| Minecraft actions | Implemented. Book entries for extra events, chat, title/subtitle/actionbar, sound, particle, item, chest deposit, fixed command, button pulse, scoreboard addition, temporary effect, mob, advancement, and datapack function. Configured advancements can provide their normal vanilla toast. |
| Physical world features | Implemented. Signs, Text Displays, latest/recent boards, recent-event boards, top-gifter/Bits boards, and multi-sign supporter walls. Named written-book keepsakes can go to a player or supporter chest. Automatic player-head skin linking is not supplied. |
| Random pools and thresholds | Implemented. Weighted single-action pools and event-specific amount/tier/month/reward thresholds. |
| Combos and queue | Implemented. Combo counters/conditions, optional repeated-effect coalescing, processing interval, capacity, priority, saved pending effects, pause/resume, and removal of queued actions whose rule was deleted/disabled. Names are saved independently. |
| Duplicate protection | Implemented. Durable Minecraft message IDs and service-side duplicate IDs; acknowledgements follow local storage. |
| Outage recovery | Implemented for Minecraft outages while Companion stays connected. Current-active-subscriber recovery is also available. Full historical recovery after Twitch/Companion outages is unavailable from these APIs. |
| Streamer pause mode | Implemented. Effects pause; name recording and core book destinations continue. |
| Testing | Implemented. All event types can be previewed with sample values. Explicitly confirmed tests can run safe configured effects without fake journal entries. Pending tests do not survive restart. |
| Dashboard | Implemented. Connection/channel, entries, people recorded, volumes/pages, today’s events in UTC, bindings, paused/enabled state, and queued-effect count. |
| Profiles | Implemented. Per-world settings plus up to 32 independent creator/setup profiles per server. |
| Import/export and presets | Implemented. Book-only, Friendly, and Hardcore presets; validated imports, backups, and exports without credentials/private user lists. Imported effects remain off. |
| Permissions | Implemented. Administration plus separate book, connect, appearance, rules, binding, moderation, configure, test, and pause rights. Profile delegation works across all platforms. |
| Safety | Implemented. Rule/global cooldowns, queue pacing, item/mob quotas, exact command/function allowlist, dimension restrictions, dangerous-action opt-in, health checks, wither/dragon/active-raid checks, and Hardcore protection. Approved arbitrary functions/commands can exceed built-in quotas. |
| Name moderation | Implemented. Display/login selection, formatting/control sanitization, user/word blocks, stable-ID resolution for known names, remove/restore, placeholders, and observed Twitch bans/timeouts. Raw history remains private; previously copied archive items cannot be recalled. |
| Minecraft text filtering | Implemented on Fabric/NeoForge where the selected online player has a configured server filter. Unprocessed names remain hidden and queued effects wait. Paper does not expose this filter, so its local user/word filtering remains available. |
| Other-mod API and datapacks | Implemented. Server-thread saved-event observers, trusted local ingestion, fixed function actions, and event data in `subby:event current`. An outbound webhook fan-out service is not supplied. |
| Supporter registry | Implemented by Twitch user ID. Renames, first observation/recorded subscription, last event, reported months/tier, gifts received/given, Bits, moderator/VIP status, and one-entry-per-supporter book view. Figures cover recorded events, not complete historical Twitch activity. |
| Companion service | Implemented locally/self-hostable. Browser pairing, encrypted Twitch tokens, separate installation credentials, refresh/validation, EventSub reconnects, scoped routing, durable buffering/replay, and multi-installation permission preservation. |
| Optional chat interface | Implemented. Moderator/broadcaster pause/resume/status/last-sub commands, public book/supporter explanations, and selectable feedback styles. It currently speaks as the broadcaster. |
| Public Subby Cloud | Not deployed. Requires an operator’s hosted HTTPS origin and Twitch application configuration. No streamer-facing public service address is bundled. |
| Dedicated SubbyBot account | Not implemented. The optional chat interface uses the connected broadcaster account; a separate bot account needs its own authorized connection. |

## Practical limits

- Twitch resub messages are shared chat notifications, not every automatic monthly renewal.
- “First chat” means first observed by this service, not first ever.
- Recovery marks original subscription dates/months as unknown and skips users already represented by a subscription in that library.
- Effects are not an exactly-once transaction with Minecraft world saves. A crash at the execution/checkpoint boundary can repeat an effect; saved book events stay deduplicated.
- Physical targets require loaded chunks. Chests wait when full; signs have four lines. Up to 64 optional physical bindings are supported per profile.
- The browser pairing screen was checked for loading, accessible controls, styles, and desktop overflow. Native Minecraft gameplay and real Twitch account acceptance are still pending.
- Custom client toast rendering, automatic player-head skin linking, automatic founder attribution, outbound webhook fan-out, dedicated-bot authentication, and public cloud hosting are not claimed as implemented.
