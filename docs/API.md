# Datapacks and the local API

## Datapack functions

Create a function in a datapack using the format appropriate to your Minecraft version, for example `data/subby/function/on_sub.mcfunction`. In the visual editor, add a function action with ID `subby:on_sub`.

Select an effect player, enable Advanced interactions, approve dangerous actions where appropriate, and add the exact `function subby:on_sub` allowlist entry under Safety. Hardcore protection still applies.

Before invoking the function, Subby writes the event to storage `subby:event`, path `current`. It includes `messageId`, `broadcasterId`, `userId`, `name`, `kind`, `tier`, `months`, `receivedAt`, and `details`.

```mcfunction
tellraw @a {"text":"Thank you for supporting the stream!"}
execute as @a at @s run particle minecraft:heart ~ ~2 ~ 0.4 0.4 0.4 0 8 force @s
```

Read or copy `subby:event current` inside the triggered function. Later events can replace it; scheduled functions should keep their own copy if needed. Approved raw commands/functions run with server authority and can exceed the built-in action limits.

## Event observers

`dev.subby.core.SubbyEvents.register` accepts a listener and returns an `AutoCloseable` registration:

```java
AutoCloseable registration = SubbyEvents.register(saved -> {
    String library = saved.libraryId();
    Subscriber event = saved.event();
    handleSubscriber(library, event);
});
```

Listeners run on the Minecraft server thread after durable storage, only for newly added events. Keep callbacks short. A listener failure is logged without undoing saved names or preventing other listeners. Close the registration when your integration stops. Callbacks contain the private saved event; integrations must apply their own display/moderation policy when exposing it.

Compile against the relevant Subby JAR/core sources. Declare Subby as a runtime dependency in your mod metadata, or `depend: [Subby]` for a Paper integration. The core is bundled into each platform JAR.

`SubbyService.acceptEvent` is available for trusted local integrations. It validates the entry, enforces the library’s channel fence, saves it, and schedules rules. It is not exposed as a public unauthenticated network endpoint.

## Templates

Text actions and book lines support `{user}`, `{display_name}`, `{login}`, `{months}`, `{tier}`, `{kind}`, `{date}`, `{timestamp}`, `{streak}`, `{gifter}`, `{gift_count}`, `{bits}`, `{raid_viewers}`, `{reward}`, `{reward_id}`, `{user_input}`, `{hype_level}`, `{hype_total}`, `{poll}`, `{prediction}`, `{outcome}`, and `{combo_count}` when the event supplies them.

Replacement is single-pass. Values do not become new templates or raw command text. Missing fields use empty/zero defaults. Recovered subscribers report unknown months. Gift giver attribution is available for gift batches/current-subscriber recovery where Twitch supplies it, not reliably for individual recipient events.

## Companion protocol

The Companion source bundle includes `PROTOCOL.md`. It defines browser pairing, installation authentication, status, sequenced event frames, individual acknowledgements, optional chat preferences, current-subscriber recovery, and unlinking. The transport is outbound HTTPS/WSS, with HTTP/WS limited to local development/self-hosting on localhost.
