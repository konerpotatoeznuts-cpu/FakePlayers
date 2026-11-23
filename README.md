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
spearmace, elytra-mace, stunslam, web-lava, pearl-pvp, grapple. Each has a matching kit (`/bot kit <bot> <name>`).
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
| Pearls | closes distance, escapes when low, pearls away before healing (`pearl.heal`), catches the target's pearl landing (`pearl.catch`), pearl-crit (`pcrit.enabled`) |
| Cart | `cart.mode` insta or xbow: rail + TNT minecart on the target |
| Spearmace | spear lunge, swap to mace and smash (attribute swapping) |
| Elytra mace | swaps to elytra, rockets up, dives and maces, swaps chestplate back |
| Stun slam | axe breaks shield, then launches and slams with the mace |
| Web combo | web, then lava on top after `lava.combo-delay` ticks; bot places water on webs it is stuck in (`water.web-escape`) or cuts free (`web.cut-ticks`) |
| Grapple | fishing-rod pull |
| Bridge | builds toward the target with blocks when no pearls (`bridge.*`) |
| Attribute swap / breach swap | picks the best hotbar item for the charge and the hit |
| Water clutch | water bucket when falling |

## Death
`death.drop-items: true` drops the bot's items and empties it; `death.restock: true` refills from its last kit on respawn.

## Integration with your other plugins
`integration.*` in `config.yml` runs console commands on events (`bot-kills-player`,
`player-kills-bot`, `on-bot-hit`, `on-use`) with `%bot% %victim% %player% %item% %platform% %x% %y% %z%`.
Items keep all their data, so custom weapons keep their names and models. Custom attack-damage
attribute modifiers on an item are used for its melee damage.

## Limits (please read)
- Bots are mannequins, not real players: no tab-list entry, and other plugins do not see them as
  `Player`, so kill progression works through the console hooks above.
- Bots have no pathfinding. They walk straight toward targets and hop over one-block steps, so
  mazes and caves will stop them.
- Crystal and anchor damage is an estimate (ignores line of sight and protection enchantments).
- Breach does not reduce armor, and shields are emulated (the raised animation is tried via `shield.visual`).
- Bots place and break blocks directly and do not ask WorldGuard. Use `build.blocked-materials`.
- Written against Paper 26.2 docs but not yet compiled or tested in-game.
