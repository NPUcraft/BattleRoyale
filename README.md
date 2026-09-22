# LastSector

[English](README.md) | [简体中文](README.zh-CN.md)

LastSector is a multi-room Battle Royale plugin for Paper.

> Current version: **1.0.0-rc.1**
> Target: **Paper 1.21.8** · **Java 21**
> Status: **Release Candidate**

LastSector 是一个面向 Paper 的多房间 Battle Royale 插件，提供随机战区、连续缩圈、动态物资、队伍、观战、离线替身、崩溃恢复、排名、经济和外观系统。

## Features

- Independent multi-room matches using isolated template-world clones.
- Random square safe zones, continuous shrinking and zone damage.
- Solo, Duo, Squad and configurable team sizes.
- Dynamic container LootPoints, ground LootAreas and native-item loadouts.
- Combat attribution, assists, shared DeathBoxes and spectator mode.
- Attackable OfflineBodies and reconnect support.
- SQLite or MySQL storage with checkpoint-based crash recovery.
- Rating, statistics and daily, weekly and monthly leaderboards.
- Lobby menus, cosmetic ownership and an optional economy-backed shop.
- CoinsEngine, ExcellentEconomy and Vault adapters.
- Safe admin map editor, map validation and controlled chunk pregeneration.
- Diagnostics, bounded performance metrics and redacted support bundles.

## Requirements

- Paper **1.21.8**.
- Java **21 or newer**, subject to Paper and other installed plugins' requirements.

Optional economy integrations tested: **CoinsEngine 2.7.0**, **Vault 1.7.3** with a registered economy backend, and **ExcellentEconomy 2.8.0** separately.

LastSector itself targets Java 21. ExcellentEconomy 2.8.0 was tested on **Java 25** and requires a newer Java runtime than 21 in the tested distribution. Follow the provider's own requirements too. Without an economy provider, matches and free cosmetics remain available; paid purchases are disabled. See [Economy Integrations](docs/ECONOMY.md).

## Installation

1. Install Paper 1.21.8 and a compatible Java runtime.
2. Build LastSector and place `lastsector-1.0.0-rc.1.jar` in `plugins/`.
3. Start the server once to generate configuration files.
4. Configure the lobby, rooms, maps, zones and storage.
5. Install saved, unloaded map templates under `plugins/LastSector/maps/`.
6. Restart the server and run `/ls admin diagnose`.

The plugin can start without valid map templates, but a room needs an available map before it can start a match. Never use a loaded server world as a template. Back up configuration, templates and the database before upgrading.

## Quick Start

1. Set the lobby world in `config.yml`.
2. Add a complete world template, including `level.dat`, and register it in `maps.yml`.
3. Configure a room and its map pool, team size and zone profile in `rooms.yml`.
4. Configure its loadout using `/ls admin loadout edit <room>`.
5. Run `/ls admin map edit <map>` to define the playable area, LootPoints, LootAreas and spectator point; save the metadata.
6. Run `/ls admin map validate <map>`; optionally use `--deep` for structural checks.
7. Join with `/ls join <room>`. The configured player threshold starts the countdown.

See [Administrator Guide](docs/ADMIN.md), [Map Authoring](docs/MAPS.md) and [Detailed Gameplay Setup](docs/GAMEPLAY.md).

## Important Commands

```text
/ls rooms
/ls join <room>
/ls autojoin
/ls spectate <room>
/ls profile
/ls leaderboard
/ls shop
/ls cosmetics
/ls admin diagnose
/ls admin map list
/ls admin map edit <map>
/ls admin map validate <map>
```

Administrative commands default to operators. See [Commands & Permissions](docs/COMMANDS.md) for the complete reference.

## Building

Use JDK 21 and the included Gradle wrapper:

```bash
./gradlew clean test build
```

Windows:

```powershell
.\gradlew.bat clean test build
.\gradlew.bat stressTest
.\gradlew.bat clean check build
```

Output: `build/libs/lastsector-1.0.0-rc.1.jar`, with an adjacent `.sha256` file. Generated JARs are not committed. Runtime dependency versions are locked; Paper remains a provided SNAPSHOT API.

The MySQL contracts require an isolated test database and `LASTSECTOR_MYSQL_TEST_PORT`; otherwise they are explicitly excluded. See [Verification](docs/VERIFICATION.md) for test setup, evidence and measured scale.

## Recovery and Economy Safety

LastSector supports checkpoint-based crash recovery. Minecraft world files and SQL storage are not part of one cross-storage ACID transaction: recovery prioritizes safety and consistency, but cannot guarantee zero rollback. See [Recovery](docs/RECOVERY.md).

External economy providers and LastSector's SQL database cannot participate in a generic cross-plugin ACID transaction. Ambiguous cosmetic purchases enter `MANUAL_REVIEW` instead of being automatically charged again. See [Economy Integrations](docs/ECONOMY.md).

## Project Status and Limitations

`1.0.0-rc.1` is a **release candidate**. The M1–M9 feature set has undergone automated, stress, crash-recovery and real Paper integration testing. Production-scale long-term deployment is still being evaluated.

- No Party provider integration, season system or skill-based matchmaking yet.
- OfflineBody is an attackable surrogate, not a real player-skin NPC.
- Some indirect damage attribution is limited by observable Bukkit/Paper events.
- SQL, world and external economy operations cannot be globally ACID.
- Deep terrain validation samples bounded locations; large-world save/unload can pause the server.

See [Verification](docs/VERIFICATION.md) and [Roadmap](docs/ROADMAP.md) for the full boundaries. This is a private repository; no project license has been selected.

## Documentation

- [Administrator Guide](docs/ADMIN.md)
- [Commands & Permissions](docs/COMMANDS.md)
- [Map Authoring](docs/MAPS.md)
- [Configuration](docs/CONFIGURATION.md)
- [Detailed Gameplay Setup](docs/GAMEPLAY.md)
- [Recovery](docs/RECOVERY.md)
- [Economy Integrations](docs/ECONOMY.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Verification](docs/VERIFICATION.md)
- [Roadmap](docs/ROADMAP.md)
- [Changelog](CHANGELOG.md)
