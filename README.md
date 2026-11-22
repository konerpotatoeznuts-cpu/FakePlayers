# FakePlayers (Paper 26.2)

OP-only fake players ("bots"). Bots are Paper `Mannequin` entities with a player skin, so they
use only the Paper API (no NMS) and show up for Bedrock players through Geyser.

## Build from your phone
1. Create a GitHub repo and upload the contents of this folder (keep the `.github` folder).
2. Open the **Actions** tab, run **Build plugin** (it also runs on every push).
3. Download the `FakePlayers` artifact, unzip it, and put `FakePlayers.jar` in `plugins/`.

If the build fails on `paper-api`, edit `gradle.properties` and set `paperApi` to the exact
build your server runs (`/version` shows it, e.g. `26.2.build.119-stable`).

## Commands (`/bot`, aliases `/fp`, `/fakeplayer`; permission `fakeplayers.admin`, OP by default)
- `/bot spawn <name> [skin]`, `/bot remove <name|all>`, `/bot list`, `/bot respawn <bot>`
- `/bot config <bot>` or right-click a bot while OP and in creative: toggle GUI
- `/bot inv <bot>` or the armor-stand button in the config GUI: equipment screen. Click an item in
  your inventory, then click a bot slot (head, chest, legs, feet, main hand, off hand) to place it.
  Shift-click an item in your inventory to auto-equip it. Click a filled bot slot with an empty
  cursor to take the item back.
- `/bot tp|goto|follow|look|say|equip|place|break|attack|set|addline|clearlines|use`
- `/bot queue <bot> <action>` with actions `goto x y z`, `wait ticks`, `say text`, `look yaw pitch`,
  `look player`, `place`, `break`, `use`; `/bot loop <bot> on` repeats the queue.

## Integration with your other plugins
In `config.yml`, `integration.*` lists are console commands run on events
(`bot-kills-player`, `player-kills-bot`, `on-bot-hit`, `on-use`) with placeholders
`%bot% %victim% %player% %item% %x% %y% %z%`. Put your kill progression and custom weapon
plugins' own commands there. `/bot equip` copies the exact item in your hand, so custom
weapons keep their names and models.

## Limits
- Bots are mannequins, not real players: no tab-list entry, no real inventory, and other
  plugins don't see them as `Player`. That is why integration goes through console commands.
- Bot block placing/breaking does not ask WorldGuard; use `build.enabled` and
  `build.blocked-materials`.
