# BattleRoyale

[English](README.md) | [简体中文](README.zh-CN.md)

BattleRoyale is a multi-room Battle Royale plugin for Paper.

> Current version: **1.0.0-rc.10**
> Target: **Paper 26.2** · **Java 25**
> Status: **Release Candidate**

BattleRoyale 是一个面向 Paper 的多房间 Battle Royale 插件，提供随机战区、连续缩圈、动态物资、队伍、观战、离线替身、崩溃恢复、排名、经济和外观系统。

## Features

- Independent multi-room matches using isolated template-world clones.
- Random square safe zones that finish shrinking to zero; the BossBar displays the target side length squared (for example `100²`).
- A bilingual preparation progress bar, followed by a randomly routed moving aircraft platform. Temporary elytra is removed and chest armor restored on landing.
- Lower container loot chance (40%, 1–3 rolls) and stratified field supply points; only match clones replace high-value storage blocks, preserving ores and ancient debris.
- Field supply points show colored particle rings and nearby chimes; items only appear within 4 blocks after a durable, one-time claim. No unopened item or display entities.
- Cached surface samples select natural or built-area loot for containers and field supplies, without changing ordinary enchantment limits.
- Population-scaled initial squares (400/600/1000/1500 blocks wide), optional map-specific opening centers, and a 7m10s four-stage default zone schedule; later centers still move randomly.
- Coordinate-free center arrows, shortest distance to the next zone when outside, a separate supply-drop arrow, and stage totals with next-zone area.
- Supply drops announced at fixed coordinates at least 60 seconds before descent, marked by real yellow beacon beams granting only Speed I to living participants within a 24-block sphere for 100 ticks per refresh.
- Solo, Duo, Squad and configurable team sizes.
- Dynamic container LootPoints, ground LootAreas and native-item loadouts.
- Automatic map-container loot with persistent refill protection, plus an endgame return countdown and early exit command.
- Short level-I potions, single-star combat fireworks, a 35% chance of one safe level-I/II equipment enchantment, and limited extra loot from naturally spawned hostile mobs; no elytra loot.
- Each supply crate guarantees one enchanted diamond weapon/armor piece or bow and one totem, before its random contents.
- Stronger supply enchantments with rare compatible vanilla-max combinations; wood, stone, ores and scarce single diamonds in material loot.
- Random adult, tamed and saddled horses: up to 16 per match, one attempt every 15 seconds; larger harmless victory-firework waves.
- Combat attribution, assists, shared DeathBoxes and spectator mode.
- Attackable OfflineBodies and reconnect support.
- SQLite or MySQL storage with checkpoint-based crash recovery.
- Rating, statistics and daily, weekly and monthly leaderboards.
- Lobby menus, cosmetic ownership and an optional economy-backed shop.
- A compact live lobby sidebar with one line per room; a red bed leaves the queue.
- Client-language UI: Chinese for `zh` locales, English for every other or unknown player locale, including menus and lobby displays. Native item names follow Minecraft's own client translations; custom labels and player names stay unchanged.
- A persistent lobby Rating TOP 8 display, refreshed from real player data, with a right-click personal-statistics stand.
- CoinsEngine, ExcellentEconomy and Vault adapters.
- Safe admin map editor, map validation and controlled chunk pregeneration.
- Diagnostics, bounded performance metrics and redacted support bundles.

## Requirements

- Paper **26.2**.
- Java **25 or newer**, subject to Paper and other installed plugins' requirements.

Optional economy dependencies remain CoinsEngine 2.7.0, nightcore 2.15.0, Vault 1.7.3-b131 and PlaceholderAPI 2.12.3. The rc.9 isolated Paper 26.2 / Java 25 checks verified startup, safe shutdown and economy persistence; see the verification record for scope. CoinsEngine 2.7.0 with nightcore 2.16.6 failed startup checks and is not an interchangeable combination.

The official [CoinsEngine 2.7.0 release](https://modrinth.com/plugin/excellenteconomy/version/2.7.0) and [NightCore 2.15.0 release](https://modrinth.com/plugin/nightcore/version/2.15.0) list Minecraft 1.21.8–1.21.11; both warn about Paper 26.2 at startup. The local checks do **not** establish official 26.2 support.

Without an economy provider, matches and free cosmetics remain available; paid purchases are disabled. See [Economy Integrations](docs/ECONOMY.md) for exact combinations and [Verification](docs/VERIFICATION.md) for current test status. A provider-availability check does not verify a real shop purchase or refund.

## Installation

1. Install Paper 26.2 and a compatible Java runtime.
2. Build BattleRoyale and place `battleroyale-1.0.0-rc.10.jar` in `plugins/`.
3. Start the server once to generate configuration files.
4. Configure the lobby, rooms, maps, zones and storage.
5. Install saved, unloaded map templates under `plugins/BattleRoyale/maps/`.
6. Restart the server and run `/br admin diagnose`.

The plugin can start without valid map templates, but a room needs an available map before it can start a match. Never use a loaded server world as a template. Back up configuration, templates and the database before upgrading.

rc.8 completes the rename to BattleRoyale: commands `/br` and `/battleroyale`, data directory `plugins/BattleRoyale`, permissions `battleroyale.*`, and Java package `com.npucraft.battleroyale`. There are no old command aliases or automatic old-directory, database-schema or namespace migrations. Existing installations require a backed-up offline migration before installing this version; do not run both plugin JARs together.

## Quick Start

1. Set the lobby world in `config.yml`.
2. Add a saved Paper 26.2 overworld dimension (including `data/minecraft/world_gen_settings.dat`), or a complete 26.2 save, and register it in `maps.yml`.
3. Configure a room and its map pool, team size and zone profile in `rooms.yml`.
4. Configure its loadout using `/br admin loadout edit <room>`.
5. Run `/br admin map edit <map>` to define the playable area, LootPoints, LootAreas and spectator point; save the metadata.
6. Run `/br admin map validate <map>`; optionally use `--deep` for structural checks.
7. Join with `/br join <room>`. The three-room deployment example uses a 60-second countdown and minimum populations of 4/8/16 for Solo/Duo/Squad, corresponding to four full teams. Existing configuration files are preserved; Duo must be configured if needed.

See [Administrator Guide](docs/ADMIN.md), [Map Authoring](docs/MAPS.md) and [Detailed Gameplay Setup](docs/GAMEPLAY.md).

On Paper 26.2, match copies live in `<level-name>/dimensions/battleroyale_game/`; editor and pregeneration copies use `battleroyale_maintenance/`. Older templates must first be converted in a separate Paper installation. Old active match copies are not migrated automatically.

## Important Commands

```text
/br rooms
/br join <room>
/br autojoin
/br spectate <room>
/br profile
/br leaderboard
/br shop
/br cosmetics
/br admin diagnose
/br admin map list
/br admin map edit <map>
/br admin map validate <map>
```

Administrative commands default to operators. See [Commands & Permissions](docs/COMMANDS.md) for the complete reference.

## Building

Use JDK 25 and the included Gradle wrapper:

```bash
./gradlew clean test build
```

Windows:

```powershell
.\gradlew.bat clean test build
.\gradlew.bat stressTest
.\gradlew.bat clean check build
```

Output: `build/libs/battleroyale-1.0.0-rc.10.jar`, with an adjacent `.sha256` file. Generated JARs are not committed. Runtime dependency versions are locked; Paper API is pinned to `26.2.build.129-stable` and is provided by the server.

The MySQL contracts require an isolated test database and `BATTLEROYALE_MYSQL_TEST_PORT`; otherwise they are explicitly excluded. See [Verification](docs/VERIFICATION.md) for test setup, evidence and measured scale.

## Recovery and Economy Safety

BattleRoyale supports checkpoint-based crash recovery. Minecraft world files and SQL storage are not part of one cross-storage ACID transaction: recovery prioritizes safety and consistency, but cannot guarantee zero rollback. See [Recovery](docs/RECOVERY.md).

External economy providers and BattleRoyale's SQL database cannot participate in a generic cross-plugin ACID transaction. Ambiguous cosmetic purchases enter `MANUAL_REVIEW` instead of being automatically charged again. See [Economy Integrations](docs/ECONOMY.md).

## Project Status and Limitations

`1.0.0-rc.8` is a **release candidate**. Its clean build passed 606 unit tests and 8 stress tests; 3 Windows symlink tests were skipped. Current real-Paper checks and their client-test limitations are recorded in [Verification](docs/VERIFICATION.md). M1–M9 and previous release-candidate results remain historical evidence. The official artifact checksum and deployment receipt belong to the corresponding [GitHub release](https://github.com/NPUcraft/BattleRoyale/releases); this source document does not claim that rc.8 is already deployed. Production-scale long-term use is still being evaluated.

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
