# Subby Companion

The connection path is **Twitch → Subby Companion → Minecraft**. Minecraft keeps its book, menus, rules, and world interactions. Companion handles Twitch authorization, EventSub, token refresh, account linking, duplicate protection, and buffered delivery.

Companion is published separately in [TAS-69/Subby-Companion](https://github.com/TAS-69/Subby-Companion). Download its [0.1.0 service preview](https://github.com/TAS-69/Subby-Companion/releases/tag/v0.1.0) and use it with the [Subby 1.1 preview](https://github.com/TAS-69/Subby/releases/tag/v1.1.0-beta.1). In the development workspace it lives alongside the mod in `../Subby Companion`.

## Streamer setup

Use a Companion service supplied by your server operator, or run your own. Set its address under **Advanced → Connection settings**, then run `/subby connect`. Match the browser code, sign in with Twitch, and confirm the channel. Each Minecraft profile has a separate private installation credential.

There is no deployed public Subby Cloud service in this preview. A hosted deployment can use the same flow once its operator supplies an HTTPS address and a registered Twitch application.

## Local operator setup

Use Node.js 24 or newer. In the Companion folder:

```text
npm ci
```

Copy `.env.example` to `.env`. Register a **confidential** application at [Twitch’s developer console](https://dev.twitch.tv/console/apps), with `http://localhost:3030/oauth/callback` as the local redirect URL. Set `TWITCH_CLIENT_ID` and `TWITCH_CLIENT_SECRET` in `.env`.

Generate a persistent encryption key:

```text
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
```

Set `SUBBY_ENCRYPTION_KEY` to that value. Keep it private and back it up with the data directory. Then start:

```text
node --env-file=.env src/main.js
```

The default service address is `http://localhost:3030`. The Minecraft profile connects outward; no Minecraft port forwarding is needed for Companion delivery.

## Hosted operator setup

Set `SUBBY_BASE_URL` to your HTTPS origin and register the matching `/oauth/callback` redirect URL at Twitch. Use a TLS reverse proxy that forwards HTTP and WebSocket upgrades. The supplied Dockerfile and Compose file are optional; Compose binds its port to localhost for a reverse proxy. The container recipe has not been exercised with Docker in this development environment.

Use a persistent data volume. Twitch tokens are encrypted with AES-256-GCM; installation credentials are stored as hashes. Protect the environment file, database, encryption key, and backups. Node 24 may show an experimental SQLite warning.

## Optional Twitch chat

Chat is off by default. Enable it under **Advanced → Optional Twitch chat**, reconnect to approve permissions, then apply its settings. Feedback styles are Silent, Cute, Professional, Chaotic, and Custom. Custom feedback supports `{user}` and `{months}`. Replies are limited to one every five seconds.

The interface currently speaks through the broadcaster account. A separate dedicated SubbyBot account is not implemented.

Broadcaster/moderator commands are `!subby pause`, `!subby resume`, `!subby status`, and `!subby lastsub`. `!book` and `!supporters` give public explanations. Chat messages are never used to detect subscriptions; EventSub supplies those events.

## Delivery and authorization

Minecraft saves an event before acknowledging its delivery. Companion retains unacknowledged events across restarts, up to 100,000 per installation. A full buffer stops intake for that channel and reports the problem. Acknowledged deliveries and service duplicate IDs are retained for 30 days. Minecraft also keeps durable event IDs locally.

Adding optional permissions preserves the basic subscription listener and the scopes used by other linked installations. The service updates its live EventSub session after browser approval. An existing library’s channel cannot silently change.

Twitch cannot replay all events missed while Companion itself was disconnected. Recovery uses the current active subscriber list and marks original date/month information as unknown. First chat means first observed by this service, not first ever.

Companion’s [operator guide](https://github.com/TAS-69/Subby-Companion/blob/main/README.md) and [protocol reference](https://github.com/TAS-69/Subby-Companion/blob/main/PROTOCOL.md) include deployment and integration details. [Twitch EventSub documentation](https://dev.twitch.tv/docs/eventsub/eventsub-subscription-types/) describes the event permissions and payload limits.
