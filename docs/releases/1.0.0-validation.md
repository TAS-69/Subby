# Subby 1.0.0 validation

Checked on 13 October 2026. The latest stable Minecraft release in Mojang's release manifest was 26.3.

## Release matrix

Both loader builds compiled successfully for every release below. Each mod declares an exact Minecraft version; install the matching JAR.

| Minecraft | Fabric | NeoForge |
| --- | --- | --- |
| 1.21.1 | Pass | Pass |
| 1.21.2 | Pass | Pass |
| 1.21.3 | Pass | Pass |
| 1.21.4 | Pass | Pass |
| 1.21.5 | Pass | Pass |
| 1.21.6 | Pass | Pass |
| 1.21.7 | Pass | Pass |
| 1.21.8 | Pass | Pass |
| 1.21.9 | Pass | Pass |
| 1.21.10 | Pass | Pass |
| 1.21.11 | Pass | Pass |
| 26.1 | Pass | Pass |
| 26.1.1 | Pass | Pass |
| 26.1.2 | Pass | Pass |
| 26.2 | Pass | Pass |
| 26.3 | Pass | Pass |

The portable Paper plugin compiled against the **1.21.1 API** and its same source also passed a separate compile against **26.3.build.178-stable**. The distributed plugin contains Java 21 bytecode and does not package server classes.

## Automated checks

**15 JUnit tests passed, with no skipped tests.**

- Nine library tests: persistent event-ID deduplication, 401-entry volume rollover, restart recovery, damaged-journal preservation, incomplete-final-newline recovery, channel isolation, Unicode page limits, event parsing, safe function IDs, and configuration/name validation. Several tests cover more than one condition.
- Three service tests: the default setup writes names without extra effects, all 300 subscription records survive saturation/pausing of the 256-effect queue, and storage errors never produce a false success response.
- One Twitch integration test: a real local HTTP/WebSocket simulator exercises expired-token refresh, saving the rotated refresh token, authenticated EventSub registration for both subscription types, duplicate notification delivery, and authorization revocation. OAuth tokens are checked to remain absent from logs.
- Two Paper behavior tests using MockBukkit 4.116.3 / Paper API 1.21.11: operator permissions and live-book creation; book updates preserve custom metadata and unrelated books.

Native Minecraft book checks passed on **1.21.1 and 26.3**. They verify a live second volume, a 100-page archive, the live-library marker, and a Minecraft codec serialization/deserialization round trip. These checks use actual Minecraft item/component classes without opening a server world. Minecraft 26.3's default component registry is minimally initialized by the test harness because its full initialization normally occurs during datapack loading.

Every release JAR is audited for the shared Twitch library and expected platform metadata/logo. No Minecraft game classes, private credential files, or development integration classes are included. `SHA256SUMS.txt` covers the 33 installable JARs.

## Practical verification still needed

No creator's Twitch application/client ID or account was supplied, so no real Twitch account was connected and no paid subscriptions were used in testing. Follow the README's sign-in steps on a test world, then verify one real subscription before using it for a stream.

The full game/server lifecycle and lectern interactions have not been manually play-tested on all 16 releases. Compile checks establish API compatibility; the automated tests above establish the covered behavior. Twitch-directed handover/backoff code is implemented, while the protocol integration test specifically exercises notifications, token rotation, duplicate protection, and revocation.

Twitch does not replay events missed while the game is closed or disconnected. This limitation and the distinction between resubscription chat messages and automatic renewals are documented in the README.
