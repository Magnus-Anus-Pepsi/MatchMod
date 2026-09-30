# Match Mode 1.1.0

Forge 1.20.1 server-side mod for match mode with presets, shrinking border, kill-cam.

## Build

**Requirements:** JDK 17+, ~4 GB RAM

```bash
chmod +x gradlew
./gradlew build
```

JAR: `build/libs/matchmode-1.1.0.jar` → put in server `mods/`

## Commands

| Command | Description |
|---------|-------------|
| `/match ready` | Vote to start |
| `/match stop` | Stop match (op) |
| `/match center` | Set zone center (op) |
| `/match size <start> <end> <seconds>` | Border settings (op) |
| `/preset save <name>` | Save current inventory as preset |
| `/preset list` | List presets |
| `/preset load <name>` | Load preset (lobby only) |
| `/preset delete <name>` | Delete preset (op) |

## Features (1.1.0)

- `/preset load` outside of match
- Inventory restored after match
- Auto end when 1 player left
- Shuffle animation on title screen
- Outro: darken + "Игра окончена" then teleport
- Kill-cam: spectator attaches to killer
