# FakePlayers (Paper 26.2)

OP-only fake players ("bots") with a real inventory and hotbar, a Java/Bedrock mode, and a very
configurable PvP brain. Bots are Paper `Mannequin` entities with a player skin (Paper API only,
no NMS), so they also show up for Bedrock players through Geyser.

## Build from your phone
1. Create a GitHub repo and upload the contents of this folder (keep the `.github` folder).
2. Open the **Actions** tab and run **Build plugin** (it also runs on every push).
3. Download the `FakePlayers` artifact, unzip it, and put `FakePlayers.jar` in `plugins/`.

If the build fails on `paper-api`, edit `gradle.properties` and set `paperApi` to the exact
build your server runs (`/version` shows it). If it fails on code, send me the error log.

## Basics
- `/bot spawn <name> [skin]`, `/bot remove <name|all>`, `/bot list`, `/bot respawn <bot>`
- `/bot config <bot>` or right-click a bot while OP + creative: main menu
  (toggles, Inventory, PvP settings, preset, kit, platform).
- `/bot inv <bot>`: real inventory. Top 3 rows = main inventory, 4th row = hotbar, bottom row =
  head, chest, legs, feet, off hand. Drag items from your inventory in and out. The paper button
  picks which hotbar slot the bot holds (`/bot select <bot> <1-9>` too).
- `/bot platform <bot> <java|bedrock>`
  - Java: attacks follow the weapon's cooldown (sword ~0.6s, axe ~1.1s, mace ~1.7s).
  - Bedrock: click-spam at `hit.cps`, no cooldown, and the name gets `platform.bedrock-prefix` (default ".").
  - Other plugins can read the entity's `fakeplayers:bot_platform` data or `fakeplayers_platform` metadata.

## PvP
Turn it on with the menu toggle or `/bot pvp <bot> on`. Everything is an option you can change per
bot in the PvP menu (click to change, right-click/shift for more) or with
`/bot pvp <bot> set <option> <value>`, `/bot pvp <bot> list`, `/bot pvp <bot> reset [option]`.

A value comes from: the bot's custom value, then its **preset**, then `pvp.defaults` in `config.yml`.
Presets: sword, axe, mace, crystal, anchor, trap, ranged, pot, hybrid, bedrock-spam, cart-insta, cart-xbow,
spearmace, elytra-mace, stunslam, web-lava, pearl-pvp, pearl-catch, grapple. Each has a matching kit (`/bot kit <bot> <name>`).
`/bot preset <bot> <name>` picks one. `/bot kit <bot> [name]` fills the inventory from `pvp.kits`.
`/bot target <bot> <player|off>` forces a target.

| Style | What the bot does |
|---|---|
| Melee | aim smoothing and wobble, reaction time, strafing (circle/random), spacing, jump crits, W-tap style closing, retreat at low health, miss chance, extra knockback |
| Shield | emulated blocking in the front arc, axe hits disable it, bots switch to an axe against blocking players |
| Totem / heal | keeps a totem in the off hand, eats golden/enchanted apples, drinks or throws healing potions, drinks buff potions |
| Cobweb | places a web on the target, removes it later |
| Lava | places lava on a webbed target, picks it back up |
| Water draining | when the target places water (or lava) to escape, the bot drains it and can re-web them |
| Crystal | finds or places obsidian, places a crystal, detonates only if estimated damage to the target is high enough and to itself low enough |
| Anchor | places and charges an anchor, detonates under the same damage checks |
| Mace | launches with a wind charge, steers in the air, dives, smash damage (Density, Wind Burst) |
| Ranged | bow, crossbow, thrown trident with target leading and spread |
| Pearls | closes distance, escapes when low, pearls away before healing (`pearl.heal`), punish crit (`pcrit.enabled`, see below) |
| Pearl catch | pearl + wind charge, bot lands above the target and mace dives (`pcatch.*`, mode up/horizontal/down/auto) |
| Punish crit | after you hit the bot and knock it up, it crits back while falling (`pcrit.window`) |
| Grapple | pearls at a target that is mid-air, teleports to them and maces (`grapple.*`) |
| Cart | `cart.mode` insta or xbow: rail + TNT minecart on the target |
| Lunge swap | attribute swap: waits holding a fast item (bare hand or sword), then swaps to the Lunge spear and jabs in the same tick so the lunge uses the fast cooldown, and swaps back (`lungeswap.*`, preset `lunge-swap`) |
| Spearmace | lunge swap into a mace smash: waits with a fast item (bare hand or sword), swaps to the Lunge spear and lunges in the same tick, then swaps to the mace and smashes (`spearmace.attribute-swap`, `spearmace.swap-delay`); works mid-air up to `spearmace.air-range` (50 blocks), chaining lunges every `spearmace.chain-cooldown` ticks while the target is far |
| Elytra mace | swaps to elytra, rockets up, dives and maces, swaps chestplate back |
| Stun slam | falling onto a blocking player: the axe disables the shield and the mace smashes in the same tick (on the ground: axe hit, then launch and slam) |
| Web combo | web, then lava on top after `lava.combo-delay` ticks; bot places water on webs it is stuck in (`water.web-escape`) or cuts free (`web.cut-ticks`) |
| Bridge | builds toward the target with blocks when no pearls (`bridge.*`) |
| Attribute swap / breach swap | picks the best hotbar item for the charge and the hit |
| Water clutch | water bucket when falling |

## Linking bots to an item (click to command)
Hold a non-block item and run `/bot link <bot|all> [player]` (you can link many bots, to different items too;
`/bot unlink <bot|all>` removes it). Then, as an OP in creative, with that item in your hand:
- **Left-click** a block (or the air, up to 128 blocks): linked bots travel there using their `travel.mode`
  (walk, run or sprintjump). Higher targets: they pillar up with blocks from their inventory (`nav.build`).
  Lower targets or walls: they dig (`nav.dig`). Several bots spread out around the spot so they don't cram.
- **Right-click** a block: linked bots teleport there (spread out the same way).
- **Hit an entity**: linked bots attack it even with PvP off (PvP turns on, then off again when it is gone).
`nav.spread` picks `ring` or `scattered` for groups (`nav.spread-radius` sets spacing); dug blocks drop as items (`nav.drops`).
**Stealth:** do any of the above while sneaking (`nav.stealth-click`) and the bots sneak, go silent and hide their name tag (`nav.stealth-speed` slows them). If the target spots one of them (it looks their way with a clear line of sight) stealth drops for all of them (`nav.stealth-spot-drop`, `nav.stealth-spot-angle`). When attacking they creep round to the target's back first and only start fighting from there.
Links are kept in memory only; redo `/bot link` after a restart.

## Scripting helpers
- **Look:** `/bot look <bot> <player>` turns once; add `continuous` to keep watching that player; `/bot look <bot> off` stops. Scripts: `look <player> continuous`.
- **Waypoints:** `/bot waypoint set <name>` (where you stand), `remove`, `list`. Use them anywhere: `/bot goto <bot> <waypoint>`, queue `goto <waypoint>`, `tp <waypoint>`.
- **Patrol:** `/bot patrol <bot> <wp1> <wp2> ...` walks them round in a loop, pausing at each (`patrol.pause-ticks`); `off` stops it.
- **Scripts:** write action lists under `scripts:` in `config.yml`, then `/bot script run <bot|all> <name>`. Actions: goto, tp, wait, say, look, select, place, break, use, sneak on|off, swing, target, command (console command, `%bot%` = bot name). Add `/bot loop <bot> on` to repeat.
- **Greeting:** `/bot greet <bot> Welcome, %player%!` says it when a player walks within `npc.greet-range` (once per `npc.greet-cooldown-seconds` per player).
- **Chat replies:** `/bot reply <bot> add <keyword> <response>` makes the bot answer when a nearby player's message contains the keyword (`npc.reply-range`, `npc.reply-delay-ticks`).

## Tab list, messages, leaving
- Bots appear in the player list (`tablist.enabled`). This uses raw server packets, so it is best effort; if your Paper build rejects them the log says so once.
- `messages.bot-join`, `bot-leave`, `bot-kill`, `bot-death` in `config.yml` are broadcast (empty = off).
- In a bot's menu: "Leave the game when killed" and "Rejoin after: Ns" (or `/bot set <bot> leave true`, `/bot set <bot> rejoin 30`).

## Death
The hopper button in a bot's menu (default ON) makes it drop its items on death and empties it; `death.restock: true` refills from its last kit on respawn.

## Integration with your other plugins
`integration.*` in `config.yml` runs console commands on events (`bot-kills-player`,
`player-kills-bot`, `on-bot-hit`, `on-use`) with `%bot% %victim% %player% %item% %platform% %x% %y% %z%`.
Items keep all their data, so custom weapons keep their names and models. Custom attack-damage
attribute modifiers on an item are used for its melee damage.

## Limits (please read)
- Pearl catch and grapple are emulated: the bot's pearl is intercepted and the bot is teleported (with 5 pearl damage, `pearl.damage`). Pearl catch ends high above the landing spot (`pcatch.height`) and grapple gives the bot a fall bonus so the mace smashes.
- Bots are mannequins, not real players: no tab-list entry, and other plugins do not see them as
  `Player`, so kill progression works through the console hooks above.
- Bots have no pathfinding. They walk straight toward targets and hop over one-block steps, so
  mazes and caves will stop them.
- Crystal and anchor damage is an estimate (ignores line of sight and protection enchantments).
- Breach does not reduce armor, and shields are emulated (the raised animation is tried via `shield.visual`).
- Bots place and break blocks directly and do not ask WorldGuard. Use `build.blocked-materials`.
- Written against Paper 26.2 docs but not yet compiled or tested in-game.
