# Subby 1.1.0-beta.1 validation

Checked on 13 October 2026. This is a preview, with real Twitch account and full gameplay acceptance still outstanding. The [original release validation](docs/releases/1.0.0-validation.md) is preserved separately.

## Compatibility

Fabric and NeoForge build targets cover all 16 releases listed in [versions.json](versions.json): 1.21.1 through 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, and 26.3. Each mod declares its exact Minecraft target.

The portable Paper JAR compiles against the 1.21.1 API. The same source also compiles against 26.3.build.178-stable with JDK 25; the packaged plugin/core use Java 21 bytecode.

## Automated behavior

**35 JUnit checks pass with no skipped tests:** 29 core/integration checks and six Paper behavior checks.

Coverage includes journal deduplication/recovery/channel isolation, damaged-data preservation, page/volume wrapping, styles and privacy, supporter-wall grouping, book-only defaults, safe previews/tests, name-filter deferral, persistent queue recovery, saturation without skipped names, thresholds/cooldowns, weighted pools, priority/pacing, coalescing, deleted-rule suppression, item/Hardcore safety, single-pass templates, profile access/migration, safe presets, registry renames/statistics, direct Twitch token refresh, and Companion pairing/acknowledgement behavior.

Paper checks use MockBukkit 4.116.3 / Paper API 1.21.11. They exercise permissions, live books, metadata preservation, unrelated-item preservation, menu inventory protection, permission revocation, the visual rule/private-chat flow, archive deduplication/restoration, live chests, and selected-player refresh. Default plain pages are exercised through MockBukkit; native Minecraft checks cover rich clickable components.

**12 Companion tests pass.** They cover minimal default scopes, normalization, encrypted storage, wrong-key rejection, duplicate IDs, restart backlog, browser cookie/CSRF/confirmation, authenticated WebSocket replay/isolation, a real local EventSub socket, current-subscriber recovery, moderator-only chat controls, duplicate chat handling, concurrent refresh/validation, permission preservation across installations, maintaining subscriptions during optional approval, library-channel fencing, and keeping slow chat feedback out of subscription storage.

The pinned Companion WebSocket dependency is ws 8.22.0. `npm audit --omit=dev` reports no known vulnerabilities at the time of checking.

## Native data checks

Headless checks using actual Minecraft classes pass on 1.21.1 and 26.3. They verify live/archived written books, page counts, marker separation, codec round trips, clickable names, sign text components, and text-display NBT parsing.

The harness does not start a complete server world. Minecraft 26.3’s written-book default component registry is minimally initialized for serialization. Sign text is checked without a level/network update. These checks do not replace in-game placement, rendering, redstone, entity, or inventory acceptance.

## Browser and packaging

The Companion pairing page loaded in a browser with the expected world name, matching code, labelled input, and Twitch button. Computed styles and desktop sizing showed a 520-pixel card, a 50-pixel button, and no horizontal page overflow at 1280 pixels. Screenshot capture was unavailable in this environment, so no screenshot review is claimed.

Release packaging checks the 33 installable JARs, expected platform metadata/shared classes, absence of game/development classes and private files, and checksums. Source bundles exclude build outputs, dependencies, credentials, and local tooling. Code-comment and unwanted-file audits run before publication.

## Still requiring external acceptance

No broadcaster’s Twitch application/account credentials were supplied. No real account was authorized and no paid subscriptions were used in testing. Verify one real subscription and the creator’s chosen optional events in a test world before streaming.

The full lifecycle, rendering, redstone reset, Text Display behavior, and all lectern interactions have not been manually played across all targets. The Docker/Compose recipe is provided but was not executed here. No public cloud service or separate bot account was deployed.
