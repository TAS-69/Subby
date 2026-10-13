<div align="center">

# Subby

**Every subscriber. A place in your world.**

Minecraft **1.21.1–26.3** · **Fabric · NeoForge · Paper**

**[Try the 1.1 preview](https://github.com/TAS-69/Subby/releases/tag/v1.1.0-beta.1) · [Setup guide](docs/GUIDE.md) · [Feature checklist](docs/FEATURES.md) · [Report a bug](https://github.com/TAS-69/Subby/issues/new)**

</div>

## Still stopping the stream to write names?

A new subscriber arrives. You put down your pickaxe, open a book, and type their name. Then another arrives.

**Subby writes subscriber names into Minecraft books automatically.** Connect the creator’s Twitch account, get a book, and keep playing.

## Connect. Get your book. Keep playing.

**①** Install the matching Subby JAR.<br>
**②** Run `/subby connect` and follow the private browser pairing link.<br>
**③** Open `/subby` and click **Get your subscriber book**.

Subby Companion handles Twitch sign-in and event delivery. It must be running at the configured service address. **The self-hostable service is published separately in [Subby-Companion](https://github.com/TAS-69/Subby-Companion); a public Subby Cloud service has not been deployed.** The service operator sets up Twitch once. Players sign in through their browser. [Companion setup →](docs/COMPANION.md)

**The default experience is the book.** Extra events, sounds, messages, mobs, commands, and other interactions start switched off.

<div align="center">

| Twitch does this | Subby records |
| --- | --- |
| A new subscription | The subscriber’s name |
| A gifted subscription | The recipient’s name |
| A shared resubscription message | The returning subscriber’s name |

<div align="center">

The book updates in an online player’s inventory or a bound lectern. Optional live book chests and a selected book recipient give your library a permanent home. Names are saved separately from the physical items, so you can recover a lost book.

## Build a subscriber library.

Look at an empty lectern and click **Put it on a lectern**. Existing unrelated books are preserved.

Subby creates pages and new volumes automatically. The default layout holds four entries per page and 100 pages per volume. An archive chest can keep completed volumes for you. You can also recover any volume through the menu.

Choose the title, author, name format, colours, clickable names, layout, monthly headings, and Roman volume numbers. Preview your design with sample names without adding them to your subscriber history.

**Want each supporter to have one entry?** Enable the registry view. Resubscriptions update the supporter’s recorded months and tier instead of adding another historical row.

## A little more, when you want it.

Open **Advanced** to build optional interactions visually:

**WHEN** a Twitch event arrives → **IF** your conditions match → **DO** the actions you choose.

- **Choose an event:** subscriptions, gift batches, Bits, rewards, raids, follows, Hype Trains, poll and prediction results, moderator/VIP changes, or first observed chatters.
- **Choose an action:** chat, titles, sounds, particles, items, chest deposits, potion effects, mobs, scoreboards, advancements, approved commands, or datapack functions.
- **Make it part of the world:** subscriber signs, floating text, recent-event boards, top-gifter displays, supporter keepsakes, and bound button pulses.
- **Keep it manageable:** cooldowns, weighted action pools, thresholds, combos, a saved priority queue, and pause/resume controls.

Optional events request their Twitch permissions when you reconnect. The subscription book continues recording while effects are paused or the effect queue is full.

## Your stream. Your settings.

Profiles keep each creator’s Twitch sign-in, library, and rules separate. Administrators can delegate book access, connection management, editing, testing, moderation, and pause controls independently.

Friendly and Hardcore presets give you a starting point. Exported presets leave out credentials and private user lists. Imported presets keep interactions off until you review and enable them.

Name moderation includes display/login choice, user and word filters, removal placeholders, observed Twitch bans/timeouts, and an optional configured Minecraft text filter on supported modded servers. Live books and boards reflect changes; previously copied archive books remain snapshots.

**Test before going live.** Preview matching rules, or explicitly run local test effects with normal safety limits. Fake subscribers are never added to the journal.

## Install & play

Download **one JAR** for your Minecraft version and platform.

<div align="center">

| Platform | Install |
| --- | --- |
| **Fabric** | Put Subby and the matching Fabric API in `mods`. Requires Fabric Loader 0.19.5+. |
| **NeoForge** | Put Subby in `mods`. Use the matching [NeoForge version](versions.json). |
| **Paper** | Put Subby in `plugins`. One plugin JAR covers 1.21.1–26.3. |

<div align="center">

Use Java **21** for Minecraft 1.21.x and Java **25** for Minecraft 26.x. Install on the server for multiplayer. Some intermediate NeoForge targets use beta loader builds.

**1.1.0-beta.1 is a preview.** Automated tests and build checks pass; real Twitch account and gameplay acceptance are still needed. [Validation →](VALIDATION.md)

The [original stable 1.0.0 release](https://github.com/TAS-69/Subby/releases/tag/v1.0.0) uses direct Twitch application setup and command/JSON configuration. It does not contain the visual editor or Companion client.

## Know what can be recovered.

Companion buffers events while Minecraft is disconnected. Minecraft acknowledges them after saving them, and repeated event IDs are ignored across restarts.

Twitch cannot replay every event missed while the Companion itself is disconnected. Optional recovery adds missing **current active subscribers**; original dates and subscription months remain unknown. Automatic renewals without a shared resubscription message are not supplied as resub events. Gift batches identify the giver and count; individual recipient events do not reliably identify their giver. [Exact coverage and limits →](docs/FEATURES.md)

**[Commands and menus](docs/GUIDE.md) · [Companion](docs/COMPANION.md) · [Datapacks and API](docs/API.md) · [Release notes](CHANGELOG.md)**

<details>
<summary><strong>Build from source</strong></summary>

Run Gradle with JDK 25 and keep JDK 21 available as a toolchain.

```powershell
.\gradlew.bat -Pplatform=paper :core:test :paper:build
.\scripts\build-parallel.ps1 -JavaHome 'C:\path\to\jdk-25'
.\scripts\package-release.ps1
```

Installable JARs and bundles are written to `dist/<version>`. The sequential `build-all.ps1` also supports Windows PowerShell. The optional parallel script requires PowerShell 7.

</details>
