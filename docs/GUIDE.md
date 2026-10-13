# Subby preview guide

This guide describes 1.1.0-beta.1. For the original stable release, use the [1.0.0 guide](releases/1.0.0-guide.md).

## First book

Install the JAR matching your loader and Minecraft version. Fabric also needs Fabric API. Paper needs only the plugin JAR. Multiplayer installations run on the server; no separate client installation is needed to open the vanilla inventory menus.

Ask the Companion operator for the service address, or run the [self-hosted Companion](COMPANION.md). The default address is `http://localhost:3030`. Change it under **Advanced → Connection settings** if necessary.

Run `/subby connect`. Minecraft privately shows a pairing code and browser link. Match the code, sign in to Twitch as the broadcaster, and confirm the channel. Twitch passwords and Twitch tokens stay outside Minecraft.

Run `/subby`, then click **Get your subscriber book**. Names arrive as subscription events occur. You can carry the live book or look at an empty lectern and click **Put it on a lectern**. Names are saved even if the item is lost.

## Book settings

The main menu contains connection, book, lectern, sample preview, saved volumes, and help. **What goes in the book?** controls new subscribers, gift recipients, and shared resubscription messages.

**Advanced → Book appearance** contains title, author, line format, sorting, headers/footers, opening pages, layout, colours, clickable names, tier symbols, milestone styling, monthly headings, and Roman numerals. **More text options** contains prefix/suffix, display/login choice, and optional player binding.

The default is four entries per page. Page layout also accounts for text width and the 14-line page height. Long templates can span pages. Every volume holds at most 100 pages. The live item follows the newest volume; **Saved volumes** creates snapshot copies of earlier books.

Registry view keeps one row per Twitch user ID, using the newest subscription identity/tier and the highest reported month count. Historical view keeps separate subscription events. Founder labels are assigned manually in moderation; subscriptions do not contain a reliable founder badge field.

## Visual rules

Open **Advanced → Visual rule editor → Add a rule**.

1. Choose **WHEN**: the Twitch event.
2. Choose optional **IF** conditions: months, tier, amount, reward ID, combo size, or cooldown.
3. Click **Add an action** and choose **DO**: a message, sound, item, or another supported action.

Common resources have visual pickers. Text and custom IDs use a temporary private chat prompt. Type `cancel` to return. Prompt answers are cancelled before broadcast to other players. Other server plugins may still inspect chat events.

Enable **Extra interactions** when the rules are ready. Optional Twitch event types also need a reconnect to approve their permissions. Direct Twitch mode supports only the three original subscription event types; Companion supplies the extra types.

The amount condition means Bits, gift-batch size, raid viewers, Hype Train level, or months, according to event type. Reward conditions use the reward’s ID. Random pools choose one action using its weight; ordinary rules run all actions. Higher priorities run first.

## Queue, combos, and safety

Open **Advanced → Safety and queue**. Defaults are 256 pending effects and four effects per one-second interval. Pending effects survive restart by default. Limits and cooldowns apply to optional effects; subscriber journal entries are still saved.

The combo window counts nearby subscription/resubscription/recipient events together. Other event kinds have separate counters. Use `{combo_count}` in messages or a minimum-combo condition. **Combine repeated effects** waits for the configured window and merges identical actions from the same rule/event kind. Every subscriber remains in the journal.

Paused effects wait while books continue recording and updating. Disabling Advanced also stops optional rule processing. Removed or disabled rules do not execute their old queued actions. Effects may repeat if the server crashes after executing an action but before saving its queue progress; book entries remain deduplicated.

Select an **Effect player** before player-specific actions. Commands, functions, mobs, redstone, and unknown/harmful potion effects require dangerous actions to be enabled. Commands/functions also require an exact allowlist entry, such as `function subby:on_sub`. Hardcore protection blocks dangerous actions in Hardcore worlds. Health, nearby wither/dragon/active raid, dimension, item-per-minute, and mob-per-minute limits are available. Raw commands/functions can perform more than the built-in item/mob limits can inspect; only approve commands you intend to run.

## Physical locations

Open **Advanced → Physical locations** while looking at the target block. Choose a type and enter a short binding name.

| Type | Behavior |
| --- | --- |
| Archive chest | Maintains copies of completed volumes in a chest/barrel. Missing copies can be restored. |
| Live book chest | Keeps the current live book in a chest/barrel. |
| Items and keepsakes chest | Rules put items into empty chest/barrel slots. |
| Subscriber sign | Writes the front of a configured sign. |
| Floating text | Creates and updates a vanilla Text Display above the target block. |
| Button pulse | A rule presses a bound button, then resets it. |

Boards can show the latest subscriber, recent subscribers/events, top gifters/Bits, or rule-controlled text. Signs have four lines; floating text suits longer displays. Choose wall mode on several signs/displays to assign consecutive groups of four known supporters. Lecterns and archive books can complete the supporter hall.

Book destinations keep updating while Advanced effects are off. Optional boards/effects need Advanced enabled. Targets update only in loaded chunks. Chests preserve unrelated contents and stop accepting items when full. Binding a sign replaces its front text. Unbinding leaves existing blocks, books, and displays in place. Pending button resets are saved and retried after restart/chunk loading.

Keepsake actions create written-book items named after the supporter. They can go to the selected player or a named chest through the action’s destination setting. Minecraft player-head skin linking is not supplied.

## Testing and dashboard

The main menu’s **Preview** creates a sample book without changing the journal. **Advanced → Test an event** lets you choose the event, sample name, amount, tier, combo, and reward ID. Preview shows matching rules and text. **Run optional test effects** asks for confirmation, uses the ordinary safety limits, and never records fake subscriber entries. Pending tests are not restored after restart.

**Connection and queue** shows connection state, book entries, volumes/pages, people recorded, today’s event count in UTC, queue size, and physical bindings. Click to refresh.

## Profiles and access

Use **Advanced → Profiles** or `/subby profile`. Each profile has separate sign-in, rules, settings, and journal. Server administrators create profiles and grant access:

```text
/subby profile create survival
/subby profile switch survival
/subby profile grant survival <Minecraft player UUID> book,connect,appearance,rules,bind,moderate,configure,test,pause
/subby profile revoke survival <Minecraft player UUID>
```

Paper has matching `subby.book`, `subby.connect`, `subby.appearance`, `subby.rules`, `subby.bind`, `subby.moderate`, `subby.configure`, `subby.test`, and `subby.pause` permissions for the default profile. `subby.admin` grants full administration. Profile delegation works on all platforms, including without an external permissions plugin.

## Presets and moderation

Book-only, Friendly, and Hardcore presets are available. Import/export uses files in that profile’s `settings/presets` folder. Import backs up existing settings, leaves Advanced off, and clears command approvals, dangerous actions, player bindings, and server-specific filtering. Export excludes credentials, private user lists, and player bindings.

Name moderation can block users/words, hide or restore names, choose removal placeholders, and mark founders. Known names resolve to Twitch user IDs so a rename does not undo a manual block. `/subby remove <name>` and `/subby restore <name>` are available. The raw private journal remains intact. Live books and boards update; archive copies already taken out of managed storage remain snapshots.

Twitch ban/timeout filtering is optional and needs moderation permission approval. It applies to events observed after enabling; existing bans are not automatically fetched. Timeouts stop hiding names after their supplied expiry. The configured Minecraft text filter is supported on Fabric/NeoForge when the selected online player has an active server filter. Unchecked names stay hidden until processing completes. Paper does not expose that filter; local user/word filtering remains available.

## Other commands

| Command | Purpose |
| --- | --- |
| `/subby` | Open the visual menu. |
| `/subby connect` | Pair or reconnect. |
| `/subby disconnect` | Stop this installation’s connection. |
| `/subby logout` | Unlink/remove the selected connection’s sign-in. |
| `/subby status` | Show status in chat/console. |
| `/subby book [volume]` | Recover the live book or a snapshot volume. |
| `/subby lectern bind` | Bind the lectern you are looking at. |
| `/subby lectern unbind` | Remove that lectern binding. |
| `/subby pause`, `/subby resume` | Pause/resume optional effects. |
| `/subby supporter <name or ID>` | Look up saved supporter statistics. |
| `/subby test bits ExampleViewer 500` | Preview a sample event; no effects run. |
| `/subby recover` | Request missing current active subscribers from Companion. |
| `/subby preset export <name>` | Export a shareable preset. |
| `/subby preset import <name>` | Import a preset file. |
| `/subby reload` | Reload validated settings. |

Direct mode is an advanced fallback. Register a Public Twitch application, select Direct under Connection settings, then use `/subby setup <clientId>` and `/subby login`. Never enter a Twitch application secret or password into Minecraft.

## Backups and recovery

Back up the world’s `subby` folder. The default library keeps its original `subscribers.jsonl` and `library.json` location. Per-world settings live in `subby/profiles/default/settings`; additional profiles have their own settings/library folders. Existing 1.0 settings are copied without deleting the originals.

`credentials.json` contains direct-mode Twitch tokens; `companion-credentials.json` contains this installation’s private service token. Keep them out of modpacks, screenshots, source control, and exported presets. Unlinking Companion removes its service-side installation and pending deliveries, while Minecraft’s saved history remains.

Companion buffers Minecraft outages while it stays connected to Twitch. Twitch does not replay all missed events after a Companion/Twitch outage. Current-subscriber recovery adds users not already represented in the library, with unknown original dates/months. It does not recreate old renewals, gifts, Bits, rewards, or historical events.

Malformed or damaged journals/settings are preserved and cause a startup error. Restore a backup before restarting. Storage failures prevent delivery acknowledgements so Companion can retry.
