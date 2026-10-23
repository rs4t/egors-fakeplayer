# Egor's FakePlayer

Carpet-style fake players for **Paper / Purpur 26.3**. Bots are real server-side players:
they load and tick chunks, attack, mine, place blocks, eat, follow you, ride things and
come back after a restart. Everything they do goes through the same code a real client
triggers, so vanilla mechanics, plugins and protections treat them like normal players.

- Skins from the Mojang account with the bot's name (or yours if there's none)
- No memory leaks, no console spam, barely measurable tick cost
- Bots belong to whoever spawned them; limits per server and per player
- Everything configurable in `config.yml`, all messages translatable (MiniMessage)

## Commands
Aliases: `/fakeplayer`, `/fp`

| Command | |
|---|---|
| `/player spawn <name> [x y z [world]]` | Spawn a bot where you stand (or at coordinates) |
| `/player remove <bot>` · `/player list` · `/player reload` | |
| `/player <bot> attack\|use\|jump [once\|continuous\|interval <ticks>]` | Click whatever the bot looks at. `attack continuous` mines, `attack interval 20` hits mobs once a second |
| `/player <bot> mine` | Same as `attack continuous` |
| `/player <bot> drop\|dropstack\|swaphands` · `hotbar <1-9>` · `inventory` | `inventory` opens the bot's inventory for you |
| `/player <bot> move forward\|back\|left\|right\|stop` · `sneak` · `sprint` | `unsneak`, `unsprint` to undo |
| `/player <bot> look north\|south\|east\|west\|up\|down\|here\|at <player>\|<yaw> <pitch>` · `turn left\|right\|back` | `look here` copies where you look |
| `/player <bot> follow [player]` · `unfollow` | Walks/sprints after a player, teleports if far away |
| `/player <bot> mount` · `dismount` | Rides the closest boat/minecart/horse… within 3 blocks |
| `/player <bot> stop` | Stop all actions |

## Permissions
| Permission | Default | |
|---|---|---|
| `fakeplayer.use` | everyone | Use `/player` |
| `fakeplayer.spawn` | everyone | Spawn bots where you stand |
| `fakeplayer.spawn.location` | op | Spawn at coordinates / other worlds |
| `fakeplayer.others` | op | Control and remove other players' bots |
| `fakeplayer.bypass-limit` | op | Ignore the per-player bot limit |
| `fakeplayer.admin` | op | `/player reload` |
| `fakeplayer.action.<action>` | everyone | Per action, e.g. `fakeplayer.action.attack` |

## Config
See [`config.yml`](src/main/resources/config.yml): bot limits, tab prefix, join/leave messages,
skins, sleeping, item pickup, rejoin after restart, auto-respawn, auto-eat, follow distances,
server-wide disabled actions and every message.

## Compatibility
- One build per Minecraft version (it uses server internals). The plugin refuses to load on
  the wrong version instead of breaking.
- Paper and forks (Purpur, …). Not Spigot, not Folia. Java 25.

## Building
`./gradlew build` (JDK 25) or `./gbuild.sh` (Docker). The jar lands in `build/libs/`.
