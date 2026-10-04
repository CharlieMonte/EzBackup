# EzBackup

**Reliable, lossless world backups and in-place restores for Minecraft, on Forge, Fabric and NeoForge.**

EzBackup allows users to creates compressed, verified backups of your Minecraft world and provides tools to restore them without disconnecting players.

Backups use standard **ZIP** or **Zstandard (`.tar.zst`)** archives, so your worlds are never locked into a proprietary backup format.

## Supported versions

Each version is its own Gradle project in its own folder. They share the same backup, archive, restore and GUI code; only the loader-specific parts differ.

| Folder | Minecraft | Mod loader | Java |
| ------ | --------- | ---------- | ---- |
| `forge/1.18.2/` | 1.18.2 | Forge 40.x | 17 |
| `forge/1.20.1/` | 1.20.1 | Forge 47.x | 17 |
| `fabric/1.20.1/` | 1.20.1 | Fabric Loader 0.15.11+ and Fabric API 0.92.2+ | 17 |
| `neoforge/1.21.1/` | 1.21.1 | NeoForge 21.1.x | 21 |

> The Fabric and NeoForge ports were written without being able to compile them. Treat the first build as the test, and test `/backup restore` first.

## Features

* **Verified backups** — world data is copied to a snapshot before compression and verified before the backup is finalized.
* **Lossless restores** — restores the complete saved world state rather than attempting to reconstruct individual inventories or blocks.
* **ZIP and Zstandard support** — choose between universally compatible ZIP archives and faster, more compact Zstandard archives.
* **In-place restore** — restore a backup without shutting down the server or disconnecting connected players.
* **Automatic rollback** — failed restores are rolled back, including recovery after a crash during the world swap.
* **Built-in GUI** — configure backups from the escape menu with a simple settings screen.
* **Command support** — create, list, monitor, cancel, and restore backups entirely from `/backup`.
* **LAN / e4mc friendly** — clients joining a hosted world do not need EzBackup installed.
* **Player-state preservation** — restored player data includes inventory, armor, ender chest, XP, spawn point, position, and modded slots such as Curios and Artifacts.
* **Cross-platform** — supports Windows, macOS, and Linux on the supported architectures.

## Commands

```text
/backup create <name> [zip|zstd] [level]
/backup list [page]
/backup status
/backup cancel
/backup restore <name> [confirm]
/backup help [page]
```

Only the world owner, the server console, and players with permission level 4 can use `/backup`.

## Backup Formats

EzBackup creates ordinary archive files that can be opened with standard tools.

| Format | File       | Compression | Best for                         |
| ------ | ---------- | ----------- | -------------------------------- |
| `zstd` | `.tar.zst` | Zstandard   | Smaller files and faster backups |
| `zip`  | `.zip`     | ZIP         | Maximum compatibility            |

### Zstandard

Zstandard is the default format and uses compression level 3 by default.

Archives can be opened with tools such as **7-Zip** or `tar --zstd`.

### ZIP

ZIP backups are standard ZIP archives and can be opened with Windows Explorer, 7-Zip, `unzip`, and similar tools.

## How Backups Work

EzBackup is designed to avoid leaving partially written or corrupted backups behind.

When a backup starts:

1. The world is saved. Any item a player is holding on their mouse cursor, or has in the inventory crafting grid, is first returned to their inventory so it is included in the backup (that player's inventory screen closes for a moment).
2. World files are copied into a temporary snapshot.
3. The snapshot is compressed in the background.
4. The resulting archive is verified against the original files.
5. Only after successful verification is the backup given its final filename.

If a backup fails or is cancelled, the incomplete backup is discarded.

A backup is refused when the destination is inside the world directory, the requested name already exists, or there is insufficient free disk space.

## Restoring a World

```text
/backup restore <name>
```

Restores a backup as the live world without shutting down the server.

Because restoring replaces the current world, EzBackup requires explicit confirmation:

```text
/backup restore <name> confirm
```

The confirmation expires after 60 seconds.

### Restore process

EzBackup performs the restore in several safety stages:

**1. Optional safety backup**

When **Auto Backup When Restoring** is enabled, the current world is backed up and verified before the restore continues.

**2. Archive extraction**

The backup is unpacked into a temporary working directory. Damaged or truncated archives are rejected before the live world is touched.

**3. Player handling**

Players are temporarily moved into a holding dimension while the world is swapped.

**4. World swap**

The current dimensions are closed, the world files are replaced, and the restored world state is loaded.

**5. Player restoration**

Players found in the backup receive the saved player data from that backup.

If the restore fails at any stage, EzBackup attempts to restore the previous world automatically.

### What is restored?

Player data includes:

* Position
* Inventory
* Armor
* Ender chest
* Experience
* Spawn point
* Modded inventory slots

World-wide state such as the following is also restored:

* Time
* Weather
* Spawn
* World border
* Ender Dragon fight state
* Wandering trader state

### What is not restored?

Some settings belong to the Minecraft installation rather than the world save and are intentionally left alone:

* Game rules
* Difficulty
* Data packs
* World-generation settings
* Seed
* `session.lock`
* `serverconfig`

Some mod-specific server-wide state may also remain unchanged.

> **Important:** Restoring an entire world is inherently more fragile than creating a backup. Always keep a backup of important worlds before experimenting with restores.

## Settings

Single-player and LAN hosts can open EzBackup's settings from the **B** button in the escape menu.

Settings are stored in:

```text
config/ezbackup.properties
```

Available settings include:

| Setting                 | Description                                   | Default             |
| ----------------------- | --------------------------------------------- | ------------------- |
| `backup_directory`      | Folder where backups are stored               | None                |
| `backup_name`           | Name used by the GUI Backup button            | Automatic timestamp |
| `default_algorithm`     | `zstd` or `zip`                               | `zstd`              |
| `default_level`         | Compression level                             | `3`                 |
| `warn_mid_backup`       | Warn before leaving while a backup is running | `true`              |
| `warn_no_backup`        | Warn when leaving without a completed backup  | `false`             |
| `list_details`          | Show backup size, format, and timestamp       | `false`             |
| `list_page_size`        | Number of backups shown per page              | `10`                |
| `backup_before_restore` | Create a safety backup before restoring       | `false`             |

### Dedicated servers

Dedicated servers do not have the client-side escape menu, so configuration can be created manually:

```properties
backup_directory=/srv/minecraft/backups
backup_name=
default_algorithm=zstd
default_level=3
warn_mid_backup=true
warn_no_backup=false
list_details=false
list_page_size=10
backup_before_restore=false
```

Invalid values are ignored and replaced with their defaults.

## Installation

1. Install the Minecraft version and mod loader that match the jar you built (see *Supported versions*). Fabric also needs **Fabric API**.
2. Place the EzBackup `.jar` in your `mods` folder.
3. Launch Minecraft.

Only the player hosting the world needs EzBackup installed. Players joining through single-player LAN or services such as e4mc do not need the mod on their own clients.

## Building from Source

### Requirements

* The JDK listed for your version in *Supported versions* (`java_version` in its `gradle.properties`)
* Gradle: 8.1 (Forge 1.20.1), 8.8 (Fabric), 8.10 (NeoForge); the version is set in each folder's `gradle/wrapper/gradle-wrapper.properties`

Open the folder of the version you want (for example `fabric/1.20.1/`). On Windows, the included build script can be used:

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Or run Gradle directly:

```bash
gradle clean build
```

The resulting mod jar is placed in:

```text
build/libs/
```

The project also bundles the required `zstd-jni` library into the final mod jar.

## Project Structure

Each version folder is organized so that most Minecraft-version-specific code is isolated from the backup implementation.

```text
src/
├── main/
│   ├── java/
│   │   └── com/ezbackup/
│   │       ├── client/          # Client GUI and input
│   │       ├── Backup*.java     # Backup workflow
│   │       ├── Restore*.java    # Restore workflow
│   │       ├── McCompat.java    # Minecraft/loader compatibility layer
│   │       ├── McWorldSwap.java # Version-sensitive restore internals
│   │       └── ...
│   └── resources/
│       └── META-INF/ or fabric.mod.json, pack.mcmeta
└── test/
    └── java/
```

The main backup and archive code does not depend heavily on Minecraft internals, making it easier to maintain and port.

## Porting to Other Minecraft Versions

See *Supported versions* for what exists today.

Most version-specific work is concentrated in:

```text
McCompat.java
McWorldSwap.java
client/
```

The restore system is particularly version-sensitive because it interacts directly with Minecraft's world and dimension internals.

When porting the mod, start with:

```text
gradle.properties
```

and update the Minecraft, loader, Java, build-plugin, and resource pack versions as appropriate.

For reference, these are the changes between the versions in this repository.

**Forge 1.18.2 to Forge 1.20.1**

* **Build:** Forge 47, ForgeGradle 6, Gradle 8.1, resource pack format 15.
* **`McCompat`:** `Component.literal(...)` instead of `TextComponent`, `sendSystemMessage` instead of `sendMessage`, and `sendSuccess` taking a supplier.
* **`McWorldSwap`:** the dimension constructor takes a `LevelStem` and a `RandomSequences`, the Ender Dragon fight is a typed `EndDragonFight.Data`, and `Registry`/`WorldEvent`/`Entity.getLevel()` became `Registries`/`LevelEvent`/`level()`.
* **`client/`:** `GuiGraphics` instead of `PoseStack`/`GuiComponent`, `Button.builder` instead of the `Button` constructor, `getX()`/`getY()` instead of `x`/`y`, `ScreenEvent.Init` and `ScreenEvent.MouseButtonPressed`, and commands sent through `connection.sendCommand`.

**Forge 1.20.1 to Fabric 1.20.1**

* **Build:** Fabric Loom 1.6 and Gradle 8.8 instead of ForgeGradle; `fabric.mod.json` instead of `mods.toml` and `pack.mcmeta`; the same official Mojang mappings, so no class or method had to be renamed.
* **`EzBackup`:** a `ModInitializer` that registers Fabric API callbacks (`CommandRegistrationCallback`, `ServerLifecycleEvents`, `ServerTickEvents`) instead of Forge `@SubscribeEvent` handlers.
* **`McCompat`:** the config folder and the mod version come from `FabricLoader`. The "server side only" flag is gone: Fabric servers do not require clients to have the same mods.
* **`McWorldSwap`:** Fabric API's `ServerWorldEvents.LOAD/UNLOAD` and `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD` replace the Forge events; Forge's cached dimension array does not exist on Fabric, so that workaround was removed; the command storage is found by its constructor because class names are obfuscated at runtime on Fabric.
* **`client/`:** a `ClientModInitializer` (`EzBackupClient`) registers `ScreenEvents.AFTER_INIT` (adds the **B** button through `Screens.getButtons`) and `ScreenMouseEvents.allowMouseClick` (the disconnect warning). The screen classes themselves are unchanged.

**Forge 1.20.1 to NeoForge 1.21.1**

* **Build:** NeoForge 21.1, ModDevGradle 2 and Gradle 8.10 instead of ForgeGradle, Java 21, `neoforge.mods.toml` instead of `mods.toml`, resource pack format 34. NeoForge runs with Mojang names, so there is no re-obfuscation step.
* **`EzBackup` / `McCompat`:** the packages are `net.neoforged.*`; the tick event is `ServerTickEvent.Post`; the "server side only" flag is gone (a client can join a server that has mods it lacks, as long as those mods register no network payloads).
* **`McWorldSwap`:** `ResourceLocation.parse` instead of the constructor; `NbtIo` takes a `Path` and an `NbtAccounter`; the codec result is unwrapped without the removed `getOrThrow(boolean, ...)`; the saved dimension is read with `Level.RESOURCE_KEY_CODEC`; events are posted on `NeoForge.EVENT_BUS`.
* **`client/`:** `mouseScrolled` has four parameters; `Screen.render` now draws the background itself, so the text that sits behind the widgets is drawn from a `renderBackground` override; `EditBox.tick()` no longer exists; the event handler uses `@EventBusSubscriber`.

The archive and file-management layers are intentionally kept independent of Minecraft so they can remain largely unchanged between versions.

## Send Suggestion

EzBackup includes an optional in-game **Send Suggestion** feature for submitting suggestions, bug reports, and other feedback.

The feature:

* Runs network requests asynchronously
* Limits message frequency
* Limits messages per game session
* Sends only the entered message, category, and version information
* Does not upload worlds, screenshots, player names, or logs

For security, webhook credentials should **never be committed to a public repository**. Maintainers configuring the feature should provide the webhook through an appropriate private configuration mechanism.

## Safety

Backups are intended to protect against data loss, but no backup system should be treated as infallible.

For important worlds, use a **3-2-1 backup strategy**:

* Keep multiple backups
* Keep them on more than one device
* Keep at least one copy in a different location

Before performing a major restore, it is recommended to create an additional backup of the current world.

## Contributing

Contributions are welcome.

Bug reports, compatibility fixes, performance improvements, documentation updates, and feature proposals are all useful.

When reporting a problem, please include:

* Minecraft version
* Mod loader (Forge, Fabric or NeoForge) and its version
* Which folder of this repository you built from
* EzBackup version
* Operating system
* Relevant error messages or logs
* Steps to reproduce the issue

For restore-related bugs, include whether the failure occurred during extraction, the world swap, or recovery after restarting.

## License

See [`LICENSE.txt`](LICENSE.txt) in this folder for the full license.

## Acknowledgements

EzBackup was developed with assistance from AI-based development tools, including Claude (Anthropic).

The project is intentionally structured to keep the backup/archive implementation separate from Minecraft-specific code, making the project easier to understand, maintain, and port.
