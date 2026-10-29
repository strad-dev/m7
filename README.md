# M7 Practice Map
This is a Minecraft 26.2 server plugin that simulates Master Mode The Catacombs Floor 7 in Hypixel SkyBlock.

**This is not a client-side mod and cannot be used on the real Hypixel server, nor is it in any way associated or affiliated with Hypixel.**

A practice server is available at `mc.strad.dev` for Minecraft 26.2

**ADVANCED USERS**: This plugin and map can be locally hosted if you know how to run a PaperMC server.  It can also be built yourself if you know how to get Paper NMS via Maven (the dependency is there, there are a few extra steps).  Stradivarius Violin is not responsible for any issues that may arise from improper building and installation, and does not guarantee that it will work on your machine.  Limited support for self-hosting is available in the [Discord Server](https://discord.gg/gNfPwa8)

## Practice Mode

### Getting Started

Select a class using the `/class` command, then tune its kit with `/m7loadout`.  `/m7practice` hands you that kit and the corresponding class tag, allowing you to use the class's passives and abilities (if applicable).

The recommended Minecraft game mode is **Adventure Mode**

### Running Practice Mode

Use the `/m7practice` command with 1-5 players.  By default, this will run all sections.

You can also specify a specific section (`clear` `boss` `maxor` `storm` `goldor` `necron` `witherking`) to practice.

If you are practicing a section that is NOT clear or maxor, the plugin will, by default, teleport you to the spawn location for that section.  You can bypass this by including the `--no-teleport` flag, but beware that it is up to you to start at the correct location!

## Quality of Life

### General

- `/eq` is supported, and will also show your speed
- Your speed automatically assumes Black Cat w/ Unalloyed Speed when equipping Racing Helmet or Cow Hat, and outside Realistic mode that pet swap is real: the hat costs you the Golden Dragon and gives you the Black Cat's +100 Intelligence
- You can shift + left click an item in your inventory to swap it with whatever is in the hotbar slot directly below it.  Exception: items in the right-most column will be swapped with whatever is in your 8th slot

### Verbose Mode

**TIMER Mode (Default)**

Displays the amount of time in ticks and seconds it took for important boss progress to be made.

**VERBOSE Mode (On)**

Also shows what packets are being sent by Fake Players and standard information about how ability results are calculated

**SUPER VERBOSE Mode**

Sends a tick-by-tick replay of most things in addition to adding location data and marking the tick each item was sent

### Main Differences from Hypixel

**Aggro**

Maxor, Storm, and Necron will aggro onto the player that last hit them.  If they are invulnerable, AOTS and Mage Beam count.  If no player has hit the boss yet, it will aggro onto the nearest player.

**Storm**

Lightning will not actually kill you, *unless* the run is in **Perfect RNG** or **Realistic** mode.

**Goldor**

- Goldor has no death ticks, except in **Perfect RNG** and **Realistic** mode, where they are checked every 3s (and the action bar counts down to the next check)
- Goldor will not chase you around at his maximum speed even if you complete a section early
- Terminals will automatically complete 1 tick after you click on them - *unless* the run is in **Realistic**
  mode, where clicking one opens a randomly generated puzzle you have to solve
- SS: You need to click the button 15 times total (i1 but very generous timing).  In **Realistic** mode it is the
  real device: the wall flashes a sequence and you play it back on the buttons, and spamming the start button
  inside its 10-tick window buys you skips
- i4: You just need to hit each of the 9 spots at least once with arrows while on the pressure plate.  In
  **Realistic** mode it is sequential instead: stepping on the plate starts it and an emerald block marks the
  target to shoot, in a fresh random order each time you step on.  Only the emerald counts - though two arrows
  landing on the same tick can clear it and the next one together.  Step off the plate and the whole device resets
- i3: The first click on any arrow frame solves it.  In **Realistic** mode you have to turn all nine of them to
  the same rotation

**Wither King**

- The dragons spawn on the same schedule every run, but **which colour is in which slot is rolled**: two spawn
  together on the first timer, a third on its own timer, and the last two when the last living dragon dies
- Putting a relic in the wrong cauldron sends it back to its statue (and, in Perfect RNG and Realistic, kills you)

**Perfect RNG mode**

Everything real about the damage, plus death: live debuffs, the blessings you actually collected, and the
instakills below.  What it does *not* do is make the dungeon fight back - the RNG always rolls your way, so a
terminal opens one click from solved and is always the same type (1 Starts With, 2 Same Color, 3 Rubix, 4 On/Off,
S2's 5 Melody), Sharp Shooter always walks its targets in the same order, the Wither King's
dragons take their set colours, Simon Says and Arrow Align are the short stand-ins, and the pet you need is the
pet you have.

**Realistic mode**

Everything Perfect RNG does, plus all the work a real run makes you do by hand: generated terminal puzzles, the
real Simon Says and Arrow Align, a Sharp Shooter order and a dragon order rolled per run, and your own pet menu
(`/pets` and `/petloadout`, below).

**Death (both of the above)**

Standing out from under one of the three real pillars during Storm's lightning (the inert Red one does not shelter
you), being caught inside a pillar, being in a Goldor section that isn't open yet *or* one Goldor has physically
walked past you into (checked every 3s - S4 is the one corridor that's always safe), and putting a relic in the
wrong cauldron all kill you outright.  Finishing a section early never endangers you; Goldor has to actually catch
up.  Goldor's ordinary damage still doesn't.  A Bonzo's Mask or Spirit Mask on your head saves you once per
cooldown and shows the cooldown on its own durability bar and in the action bar; so does a Phoenix pet, which you
are assumed to have out unless you're in Realistic mode, where you have to actually have it summoned.  Die anyway
and you become a ghost and revive yourself after 5 seconds, where you're standing, with the inventory you died with.
If everyone is dead the run ends in failure: the session is ended for you, everyone gets their inventory and
Adventure mode back, and the party is gathered at `28.5 166 118.5`.

**Pets (Realistic only)**

`/pets` opens your pet menu - click a head and that pet comes out.  There are five: Golden Dragon, Ender Dragon,
Phoenix, Crow and Black Cat, each with the held item and stats the real one has.  `/petloadout` is the same window
in arranging mode, where you pick a pet up and drop it somewhere else.  Slot 46 is Autopet: a rule per trigger
(run start, entering combat, Maxor spawning, and a Pitchin' Rod throw, which steps through a cycle instead),
left click to change the pet it summons and right click to set a pet it won't interrupt.  In every other mode
your pet is assumed from what you're doing and wearing, so both commands refuse.

## For Plugin Developers

**SUPER ADVANCED USERS ONLY.**  There is no published artifact to depend on yet, so the supported way in is
reflection: you load M7's classes by name at runtime and read them through `Method#invoke`, with no
compile-time safety net whatsoever.  A typo in a class name, a field this plugin renames, or an M7
version older than the one you tested against are all runtime surprises, not compile errors.  Nothing here
is a stable, versioned API: it can change between releases, and it is on you to fail gracefully when it
does.  If that trade doesn't appeal, wait for the proper option below.

M7 exposes a deliberately small API in two halves: an **item catalogue** (what the classes carry, as
real `ItemStack`s) and **custom events** (what just happened in a run).  Both halves are plain Bukkit.  You
do **not** need NMS or `paper-nms` to consume them, even though the plugin itself is full of NMS.

Two principles shape the whole thing:

1. **M7 never calls out.** It fires its events into the void; if nothing is listening, nothing happens.
   The plugin stays fully standalone, so your integration is always optional and can never break a run.
2. **Everything that crosses the plugin boundary is a Bukkit type, a `String`, or JSON.** That means you can
   integrate *without compiling against M7 at all* (see [Hooking in](#hooking-in) below).

### The item catalogue

Every custom item (e.g. Hyperion, Terminator, Infinityboom TNT, Infinileap) is an ordinary `ItemStack` whose
**identity is its first lore line**.  There is no NBT, no persistent-data container, no registry: an item *is*
a Terminator if and only if its first lore line reads `skyblock/combat/terminator`.  The ability listener
dispatches purely on that string, so anything that reproduces the lore line behaves like the real item.

```java
String id = listeners.CustomItems.getID(player.getInventory().getItemInMainHand());
if(id.equals("skyblock/combat/terminator")) {
	// they're holding a Terminator
}
```

`getID` returns `""` for a vanilla item, so it's always safe to call.  The IDs currently in use:

| Namespace | IDs |
|-----------|-----|
| `skyblock/combat/` | `aots` `aotv` `bonzo` `claymore` `dungeonbreaker` `explosive_bow` `flaming_flay` `golem_sword` `gyro` `ice_spray` `infinityboom` `jerrychine` `last_breath` `rag` `scylla` `spring_boots` `stonk` `tac` `terminator` |
| `skyblock/game/` | `energy_crystal` |
| `skyblock/utility/` | `infinileap` |

Two ways to *get* those items:

**In code** (requires compiling against the plugin, route B below):

```java
ItemStack[] kit = plugin.ClassKits.classLoadoutContents("Mage"); // 41 slots, see layout below
loadout.Loadouts.applyFor(player);                                        // the player's SAVED kit + class tag (what /m7practice does)
```

Valid roles are `Archer`, `Berserk`, `Healer`, `Mage`, `Tank`.

**From disk, with no dependency at all.**  On every enable, `plugin.Catalog` exports the whole catalogue to
`<server>/../data/m7-item-catalog.json`.  M7 is the sole writer of that file;
it is the single source of truth for item definitions and anyone may read it (may change/be configurable later):

```json
{
  "palette":  [ "<base64 ItemStack>", ... ],
  "defaults": { "Archer": [ 41 entries, base64 or null ], "Mage": [ ... ], ... }
}
```

- **`palette`**: every distinct item across all five kits, deduped.  Use it to build an item picker.
- **`defaults`**: each class's default kit as a 41-slot array: `[0..35]` main inventory, `[36]` helmet,
  `[37]` chestplate, `[38]` leggings, `[39]` boots, `[40]` off-hand.  `null` means an empty slot.

Each entry is base64 of Paper's `ItemStack#serializeAsBytes()`, so decoding is two lines and needs nothing
from this plugin:

```java
ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
```

(`plugin.ItemSerial#toB64`/`#fromB64` is that pair of one-liners if you'd rather call them.  Because it's
Paper's own binary format, both sides must run the same Paper build, which they do, since you're reading a
file written by the server you're running on.)

### Custom events

There are three events, all ordinary Bukkit `Event`s.  None is `Cancellable` and there's nothing to
override.  They are notifications, not hooks:

- **`plugin.RunCompleteEvent`**: fired the moment a `/m7practice` run finishes (for Wither-King runs, only
  *after* the death dialogue ends).
- **`plugin.ScoreMilestoneEvent`**: fired MID-RUN, the instant the team reaches a clear-score milestone
  (currently only 300).  That's the point of it: the milestone's time is a real achievement whether or not the
  run is ever finished, since the team can reset straight after hitting 300.  Adds `int score()` on top of the
  same payload.
- **`plugin.BlessingChangeEvent`**: fired MID-RUN whenever the blessing tally moves - a blessing was found, the
  clear phase started, or the tally was cleared as a section was set up.  Carries a `plugin.BlessingState`
  instead of a `RunResult`; see [The blessings](#the-blessings) below.

The first two carry a **`plugin.RunResult`**: a snapshot of every fact about the run, deliberately knowing
nothing about leaderboards or categories.  Deciding what a run *qualifies for* is your plugin's business.

| Field | Meaning |
|-------|---------|
| `section` | what `/m7practice` was invoked with: `all` `clear` `boss` `maxor` `storm` `goldor` `necron` `witherking` |
| `runId` | unique per run, **identical across every report that run makes** (see the dedupe note below) |
| `difficulty` | the mode the run was set under: `classic`, `perfect_rng` or `rta`.  **`rta` is Realistic** - `realistic` was the old id of what is now Perfect RNG, so it is not reused, and this field is always the stored id even though players type `realistic`.  **Times from the three are not comparable** |
| `success` | `false` for a failed run: an enraged Storm with no pillars left, or, in a mode where death is on, the whole party dead |
| `runTicks` | total run length in server ticks |
| `clearEndTick`, `bloodDoneTick`, `score300Tick`, `fullClearTick` | clear-phase milestones, as overall ticks |
| `teamScore`, `grade` | final score and its letter grade (`S+`, `S`, `A`, …) |
| `phaseDurations` | per-boss durations, relative to that boss's own start, measured to the end of that boss's phase (the tick it chains to the next one), not to its killing blow |
| `splitEnds` | overall tick each section ended: `Clear` `Maxor` `Storm` `Terminals` `Goldor` `Necron` `WitherKing` |
| `participants` | uuid, name, and `stayedAdventure` for everyone who took part, **disconnected players included** |

Five things to know before you use them:

- **`RunCompleteEvent` fires for failed runs too** (`success == false`).  That's intentional, so a listener
  holding a session or a slot still gets told to let go.  A failed run's payload still carries every phase
  duration and clear milestone the party actually reached, so if you record per-section times you can keep the
  sections they finished; what it can never be is a whole-run time.
- **Deduplicate on `runId`.**  A run that hits 300 and then finishes reports its `score300Tick` *twice* - once
  live on `ScoreMilestoneEvent`, once again in the final `RunCompleteEvent`.  Both payloads share the same
  `runId`, so ignore a milestone you've already recorded for that id rather than counting it twice.
- **`participants` is the run's ROSTER, not a roll call at the finish.**  A player who lags out at 64 seconds is
  still on it, with the state they left with, so a group size derived from it can't shrink under the survivors.
  Spectators and mid-run leavers who went to spectator are never on it; someone who joins mid-run is.
- **`Integer`/`String` fields are `null` when not reached.**  Every clear milestone is `null` on a boss-only
  practice; `witherKing` is `null` for a run that stopped at Necron.  A score of `null` is not a score of 0.
- All ticks are server ticks (20/s).  "Overall" ticks are relative to the run's t=0; phase durations are
  relative to their own boss's start, and run to the **end of the phase** (after the death dialogue, the tick
  the next boss spawns), not to the killing blow, so they line up with the `splitEnds` deltas.

### The blessings

`plugin.BlessingChangeEvent` carries a **`plugin.BlessingState`**, and you can also ask for one at any moment
without waiting for an event - useful if your plugin started up (or reloaded) mid-run and so missed the awards:

```java
String json = (String) Class.forName("plugin.BlessingState").getMethod("currentJson").invoke(null);
```

| Field | Meaning |
|-------|---------|
| `runId` | the run these belong to, the same id its `RunResult`s carry |
| `difficulty` | `classic`, `perfect_rng` or `rta` |
| `runActive` | whether a practice run is live at all - i.e. whether this is current, or a finished run's last word |
| `clearActive` | whether the clear phase is live right now |
| `hasClearData` | whether `level`/`count` describe this run at all (see below) |
| `assumedMax` | whether the damage pipeline is using the maxed table instead of what was collected |
| `blessings` | one entry per type, in the order Power, Wisdom, Time, Stone, Life |

Each entry has `type` (`POWER` `WISDOM` `TIME` `STONE` `LIFE`), `level` (total level **collected**, so a Power V
contributes 5), `count` (how many separate blessings of that type were found), `effectiveLevel` (the total level
the damage formulas actually **used**), and the effect of that: `multiplier` for the three multiplicative
blessings, `flatDamage` for Stone.  The two that aren't modelled are simply absent - Stone has no `multiplier`
(its Defense half is unmodelled) and Life has neither.

Two things will trip you up if you assume the obvious:

- **`level` and `effectiveLevel` are different numbers and usually disagree.**  In classic mode - the default -
  the formulas use the maxed table no matter what the party collected, so a run four blessings in is still being
  damaged as though fully blessed.  `assumedMax` tells you which figure is the live one; a display showing only
  `level` misreports every classic run.
- **`hasClearData == false` means "there is no chest history", not "they collected nothing".**  A boss-only
  practice never runs a clear phase, so there is nothing to collect and max blessings are assumed even in the
  two live modes.
- **The empty tally is reported too, not just the awards.**  The clear phase starting, and a section setup
  clearing the tally, both fire this event.  A display fed only by awards keeps showing the previous run's
  blessings for the whole of the next one, since a section that collects nothing never fires again.  Note the
  *order* a finished run tears down in:
  `/m7practice end` turns practice mode off first, so the last thing the run publishes has `runActive == false` -
  check that flag rather than assuming a payload describes something live.

### Hooking in

**Route A: no dependency (recommended).**  Read `m7-item-catalog.json` for items, and reach the event
reflectively.  The event exposes a single no-arg `String json()` method for exactly this purpose, so the whole
integration is one `Class.forName` and one `getMethod("json")` (you never touch `RunResult` itself):

```java
try {
	Class<? extends Event> ev = (Class<? extends Event>) Class.forName("plugin.RunCompleteEvent");
	getServer().getPluginManager().registerEvent(ev, new Listener() {}, EventPriority.MONITOR,
			(listener, event) -> {
				String json = (String) event.getClass().getMethod("json").invoke(event);
				// parse json with your own DTO; field names match RunResult
			}, this);
} catch(ClassNotFoundException e) {
	// M7 isn't installed on this server; skip the integration
}
```

Register the same way for `plugin.ScoreMilestoneEvent` if you want the live 300 (its extra `int score()` reads
reflectively just like `json()` does) and for `plugin.BlessingChangeEvent` if you want the live blessings - every
event here exposes that same `json()`, so one helper covers all three.  Add `softdepend: [m7]` to your
`plugin.yml` so you load *after* M7
and the class resolves.  This is how
the strad.dev network plugin consumes it, and it means one jar can run on servers with and without M7.

**Route B: compile against the plugin.**  Put the M7 jar on your compile classpath with `provided`
scope and `depend: [m7]` in your `plugin.yml`; at runtime Paper hands you M7's classes through its
plugin classloader.  You get real types and IDE completion, at the cost of a hard dependency.

The API classes (`RunCompleteEvent`, `RunResult`, `BlessingChangeEvent`, `BlessingState`, `ItemSerial`,
`Catalog`, `ClassKits`) are pure
Bukkit, so `paper-nms` is *not* needed to compile against them, only to build M7 itself.

> **Don't copy `RunCompleteEvent` into your own plugin.**  Bukkit matches listeners by class identity; your
> copy would be a different class from the one M7 fires, and your listener would simply never run.  Depend
> on the jar (route B) or go reflective (route A).

### Proper Artifact When???

Reflection is the recommended approach *for now*.  A proper artifact you can depend on will be ready in
**3-5 business days**.
