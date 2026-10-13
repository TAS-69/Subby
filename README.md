<div align="center">

# Subby

**Every subscriber. A place in your world.**

Minecraft **1.21.1–26.3** · **Fabric · NeoForge · Paper** · **1.0.0**

**[Download](https://github.com/TAS-69/Subby/releases/tag/v1.0.0) · [Setup guide](docs/GUIDE.md) · [Report a bug](https://github.com/TAS-69/Subby/issues/new)**

</div>

## Still stopping the stream to write names?

A new subscriber arrives. You put down your pickaxe, open a book, and type their name. Then another arrives.

**Subby writes subscriber names into Minecraft books automatically.** Connect the creator's Twitch account, get a book, and keep playing.

## Connect. Get your book. Keep playing.

**①** Install the matching Subby JAR.<br>
**②** Link Twitch with `/subby setup <clientId>` and `/subby login`.<br>
**③** Run `/subby book`. New subscriber names appear automatically.

Twitch requires a registered Public application client ID. The [setup guide](docs/GUIDE.md#connect-twitch) walks through this. Sign in as the broadcaster; a moderator or bot account cannot grant the required access.

**Names save automatically.** The book updates while it is in an online player's inventory or a bound lectern.

| Twitch does this | Subby records |
| --- | --- |
| A new subscription | The subscriber's name |
| A gifted subscription | The recipient's name |
| A shared resubscription message | The returning subscriber's name |

Resubscription messages are optional. Automatic monthly renewals without a shared message are not recorded by this release. Gift-giver names and mass-gift totals are not recorded.

## Build a subscriber library.

**Put the book on display. You do not have to carry it.**

Look at an empty lectern and run `/subby lectern bind`. Subby places a live subscriber book there and keeps it updated while the chunk is loaded. Existing unrelated books stay untouched.

Each volume holds **100 pages and 400 entries**. The live book follows the newest volume. Use `/subby book 1`, `/subby book 2`, and so on to create permanent archive copies.

**Lost the book? Get another. The saved names are still there.** Subby keeps the subscriber history separately from the physical books and ignores repeated Twitch event IDs after reconnects or restarts.

## Want a little more?

Optional rules can send chat messages, play sounds, or run a fixed datapack function when a subscription arrives. Configure these in `rules.json`; the default setup concentrates on the book and has no extra rule effects.

- **Messages** — use names, subscription months, tiers, and timestamps.
- **Sounds** — choose a Minecraft sound for each supported subscription kind.
- **Datapack functions** — connect subscriptions to your own Minecraft creations.
- **Pause and resume** — stop queued effects while names keep recording.
- **Supporter lookup** — use `/subby supporter <name>` to inspect recorded subscription history.

**Version 1.0 uses commands and JSON settings.** It does not include a graphical editor, Channel Points, Bits, raids, follows, or a dashboard. See the [guide](docs/GUIDE.md) for the exact supported behavior.

## Install & play

Download **one JAR** for your Minecraft version and platform.

| Platform | Install |
| --- | --- |
| **Fabric** | Put Subby and the matching Fabric API in `mods`. Requires Fabric Loader 0.19.5+. |
| **NeoForge** | Put Subby in `mods`. Use the matching [NeoForge version](versions.json). |
| **Paper** | Put the Subby Paper plugin in `plugins`. One plugin JAR covers 1.21.1–26.3. |

Use Java **21** for Minecraft 1.21.x and Java **25** for Minecraft 26.x. Some intermediate NeoForge versions are beta releases.

For singleplayer, install the Fabric or NeoForge edition in the creator's game. For multiplayer, install Subby on the server. Management requires operator permissions, or `subby.admin` on Paper.

## Know when Twitch is connected.

Use `/subby status` to check the connection, recorded names, volumes, lecterns, and queued effects. Subby reconnects automatically when it can.

**Twitch does not replay events missed while Minecraft is closed or disconnected.** This release does not recover those missing events. It was checked with automated tests and a local Twitch simulator; real-account and full gameplay checks are still needed before a live stream. [Validation details →](VALIDATION.md)

**[Installation and Twitch setup](docs/GUIDE.md#install) · [Commands](docs/GUIDE.md#commands) · [Rules and functions](docs/GUIDE.md#settings-and-rules) · [Release notes](CHANGELOG.md)**

<details>
<summary><strong>Build from source</strong></summary>

Use JDK 25 to run Gradle and keep JDK 21 available as a toolchain:

~~~sh
./gradlew build
~~~

Windows: use `gradlew.bat`. To build a different Minecraft version:

~~~sh
./gradlew -Pmc=26.3 :fabric:build :neoforge:build
~~~

The [build scripts](scripts) assemble the full release matrix. Installable JARs appear in each platform's `build/libs` directory.

</details>

[MIT](LICENSE) · [Third-party notices](gradle/NOTICE)
