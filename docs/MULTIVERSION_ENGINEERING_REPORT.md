# YJHack multi-version engineering report

## Scope

This report covers a Minecraft Java 1.21.5 Fabric client joining a server whose combat behavior is based on 1.8.9 through ViaVersion or a comparable protocol translation stack.

The code and tests can prove client-side scheduling, input ownership, validation, configuration, and GUI behavior. They cannot prove the target server's real damage, knockback, no-damage window, anti-cheat decisions, tick health, or custom combat plugin settings.

## Primary sources reviewed

- ViaVersion project overview and source: https://github.com/ViaVersion/ViaVersion
- ViaVersion official site and downloads: https://viaversion.com/
- ViaBackwards source: https://github.com/ViaVersion/ViaBackwards
- ViaRewind source: https://github.com/ViaVersion/ViaRewind
- ViaFabricPlus source: https://github.com/ViaVersion/ViaFabricPlus
- Minecraft 1.21.5 Yarn mappings selected by this project: `yarn_mappings=1.21.5+build.1`
- YJHack `main`, the reference tag `v1.0.6-autoleft-hold-fix-20260730`, and their complete diff.

Direction matters: ViaVersion primarily enables newer clients on older servers. ViaBackwards and ViaRewind primarily address older clients on newer servers, so they are relevant to the wider server stack but do not by themselves describe this client's exact attack path. ViaFabricPlus matters only when it is actually installed client-side.

## What the client can and cannot know

### Proven from YJHack code

- AutoLeft and AutoRight each have one independent `cps` field, normalized to 1–40.
- The fixed-rate limiter is monotonic and releases at most one action per callback.
- Missed deadlines are discarded. There is no queue, backlog, replay, or catch-up.
- Physical mouse state is read separately from the synthetic key state.
- Follow-up attacks have one owner and call Minecraft's own `doAttack()` path.
- Follow-up block uses have one owner and call Minecraft's own `doItemUse()` path.
- AutoLeft does not create an artificial miss attempt when vanilla has no entity hit result.
- AutoRight rejects obviously invalid placement candidates before calling vanilla.
- AimAssist is hard-capped at 3.5 blocks, requires line of sight, and does not modify attack reach.
- Reset changes one module config only and has no packet or synthetic-input dependency.

### Proven from protocol architecture

- Protocol translators rewrite packet formats; they do not make the client authoritative for damage or block placement.
- The server remains authoritative for accepted interactions, damage, knockback, world state, and corrections.
- A sent attack attempt, a packet accepted by the network stack, a server-processed attack, and a damage-producing hit are different events.
- A locally predicted placement can be corrected by the server when the server rejects it.

### Not knowable without the target server

- Actual no-damage ticks or hurt-resistance window.
- Custom damage scaling, hit delay, knockback, or critical rules.
- Anti-cheat rate limits and packet-order checks.
- Whether ViaVersion compatibility options were changed from defaults.
- Server TPS, proxy congestion, upload loss, packet buffering, and plugin conflicts.
- The reason for one specific half-heart result without server-side logs and repeated controlled measurements.

## Why equal CPS can produce unequal damage

Most likely to least likely:

1. Server damage-immunity or custom hit-delay windows make many attempts non-damaging.
2. A custom combat or anti-cheat plugin modifies acceptance, damage, or knockback.
3. Ping, jitter, upload congestion, proxy buffering, or server tick delay changes when attempts arrive.
4. Low FPS or a render-thread stall changes client callback timing; without a no-backlog policy, this can cluster attempts.
5. Target range, line of sight, movement, or rotation changed between the local raycast and server processing.
6. Protocol translation or compatibility configuration changes hitbox/range/movement representation.
7. The observed health display, absorption, armor, enchantments, or plugin UI made damage appear inconsistent.

A single hit is not evidence. Compare large samples under fixed equipment, target state, ping, and movement, and collect server logs when possible.

## Generated CPS versus useful hits

- **Generated CPS**: follow-up opportunities released by the local limiter.
- **Vanilla attack attempt**: `doAttack()` ran with a current vanilla entity hit result.
- **Translated packet**: the protocol layer encoded the interaction for the older server protocol.
- **Server-processed attack**: the server accepted the packet for combat processing.
- **Damage-producing hit**: server combat rules allowed damage at that moment.

YJHack measures and controls only the first two stages. It does not claim that 40 CPS creates 40 accepted or damaging hits per second.

## Implemented policy

### AutoLeft

- Default: 12 CPS.
- User range: 1–40 CPS.
- First physical click stays vanilla.
- Follow-ups occur only while the physical button remains held, gameplay is active, and vanilla currently targets an entity.
- One follow-up per callback maximum.
- No randomization, queue, replay, catch-up, reach increase, rotation spoofing, or direct packet construction.

Twelve CPS is a conservative responsiveness default: it is high enough to reduce delay before an eligible legacy-style hit opportunity while avoiding making 40 attempts per second the default. It is not a damage guarantee.

### AutoRight

- Default: 10 CPS.
- User range: 1–40 CPS.
- First physical use stays vanilla.
- Blocks use the fixed follow-up policy.
- Fire Charge, pearl, projectiles, buckets, and other known instant items use once per physical press.
- Food, bows, shields, and hold/charge items remain vanilla.
- A block hold can continue across a block-stack-to-block-stack slot change.
- A transition to a non-block item cancels block context.
- Candidate checks require a block item, a block hit result, a replaceable target/adjacent position, and world-border validity.

Ten CPS is a conservative placement default. Vanilla remains responsible for sequence IDs, prediction, collision, interaction packets, and final server acceptance.

### NinjaBridge

- Auto Switch default: enabled.
- Slot delay default: 120 ms, configurable from 50–500 ms.
- Switching is attempted only at a real grounded edge.
- Repeated selection of the current slot is avoided.
- Synthetic sneak never cancels a real physical sneak hold.
- World, focus, GUI, death, and disable paths release synthetic state.

### AimAssist

- Maximum and default retention range: 3.5 blocks.
- FOV: 70 degrees.
- Speed: 0.22.
- Smoothness: 0.62.
- Sticky Lock: enabled.
- Line of Sight: mandatory.
- Bed Lock: enabled.
- No jitter or random offset.
- No attack reach modification.

Sticky Lock retains the selected valid player through small FOV changes, but releases on distance, death, disappearance, invalid state, line-of-sight loss, world change, disconnect, disable, or Bed Lock.

Bed Lock applies only while the same bed is actually being broken. Ordinary blocks do not create this lock.

### Tracker

- Range: 48 blocks.
- Ignore own team: enabled.
- HUD position: X 8, Y 8.
- Uses only player entities already present in the client's normal world state.

## Reset contract

Every module page owns a Reset button. Reset:

- creates a fresh recommended config object;
- changes the open module only;
- normalizes and validates the new values;
- applies them to runtime;
- writes them immediately to disk;
- refreshes the current page's controls in place;
- shows `Recommended settings restored`;
- does not close the page;
- has no global-reset behavior;
- sends no input and no packet.

## Known limitations

- Prevalidation reduces obviously invalid block attempts; it cannot eliminate server-side rejection or every ghost block.
- Automated tests are simulations of client logic, not proof of real server damage or knockback.
- A real target-server test requires its exact Via configuration, plugin list/configuration, server logs, and controlled repeated trials.
