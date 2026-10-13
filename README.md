# FarexBot

A client-side Fabric mod for **Minecraft 26.3**. Your friend presses a key, and your account (AFK at their
ender pearl stasis chamber) flips the chamber's trapdoor/lever the instant the request arrives, so they
teleport back.

Both of you install the same jar. No port forwarding or extra server is needed: the request goes through a
normal in-game whisper (`/msg`) and is hidden from chat on both sides.

## How it works

1. Your friend presses the **FarexBot key** (default `G`, rebindable in Controls).
2. Their client whispers you a signed message like `fxp1 Friend 1791848581 9c1d86c8 5f09fdeb2d81e0a3`.
3. Your client checks the signature against the secret you share, then in the same tick turns to face the
   chamber block and right-clicks it: one rotation packet and one use packet. The click is ray-traced like a
   real one (the face and point your crosshair actually lands on), and is refused if a block is in the way.
4. Your client whispers back a signed result, and your friend sees "Pearl pulled!" or the reason it failed.

Requests are signed with HMAC-SHA256 and a per-friend secret, expire after 60 seconds, and can't be replayed.
Other players can't set off your chambers by whispering you.

## Setup

**Loader (you, the account at the chambers):**

1. `/farex secret` makes a random secret. Click it to copy, then send it to your friend privately.
2. Stand at the chamber and look at its trapdoor/lever/button.
3. `/farex chamber add <FriendName> <secret>`
4. Optional: `/farex chamber reset <FriendName> 20` flips the block back after 20 ticks (1 s), for chambers
   that need re-arming.
5. `/farex chamber test <FriendName>` checks that you can reach it.
6. Stay AFK within reach (about 4.5 blocks) of every chamber you load, with a clear line of sight to each
   trigger block.

**Sender (your friend):**

1. `/farex pair <LoaderName> <secret>`
2. Throw a pearl into the chamber, go wherever you like, and press `G` (or run `/farex pull`) to come back.

**Other commands:** `/farex chamber list`, `/farex chamber remove <friend>`, and `/farex whisper <cmd>` if
the server uses `/w`, `/tell` or similar instead of `/msg`.

Settings are stored in `config/farexbot.json`.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. GitHub Actions also builds every push, and the jar is uploaded as the
`farexbot-jar` artifact. Install it with Fabric Loader and Fabric API for 26.3 (Java 25).

## Notes

- Both accounts must be on the same server, and the chamber must be loaded (the loader stands next to it).
- Some servers don't allow automation. Check the rules before you use this.
