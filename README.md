# BTA-Movil

**Better than Adventure 8.0.1 on Android**: an unofficial port for the Amethyst and Zalith launchers that
draws directly with OpenGL ES 3.2, with performance work for phones and console-style menus for gamepads.

> **Unofficial project.** BTA-Movil is not made, endorsed or supported by the Better than Adventure team or
> by Mojang. It contains **no BTA or Minecraft code**: it is built by patching the bytecode of your own copy
> of the official `bta.jar`, and the installer carries only the differences. Please do not report
> BTA-Movil problems to the BTA team.

- **Version:** 1.0.0 (for BTA 8.0.1 only)
- **Download:** the installer `BTA-Movil-Installer.jar` from the [Releases](../../releases) page
- **Author:** JorgeForero02
- **No PC needed:** the installer runs on the phone itself, from the launcher's "run .jar" option, and on Amethyst it creates the BTA-Movil version directly (no zip to import).

## Contents

- [Compatibility](#compatibility)
- [What it brings](#what-it-brings)
- [Install and play](#install-and-play)
- [Options](#options)
- [Known issues and troubleshooting](#known-issues-and-troubleshooting)
- [Building from source](#building-from-source)
- [Credits](#credits)
- [Legal](#legal)

## Compatibility

| | Supported |
|---|---|
| Game | Better than Adventure **8.0.1** (official `bta.v8.0.1.mmc.zip` for MultiMC/Prism); no other version |
| Launchers | Amethyst, Zalith (Pojav-based) |
| Renderer | Krypton Wrapper |
| Java | 17 |
| GPU | OpenGL ES **3.2** (Android 7+), 64-bit. Check it with DevCheck or GPU Info |
| Memory | 2048-4096 MB for the game; a phone with 6 GB of RAM is recommended |
| Tested on | Mali-G615 |

## What it brings

**Compatibility**
- Direct drawing with OpenGL ES 3.2, without the launcher's translation layer.
- Shaders converted to GLSL ES 3.20.
- Fixes for the Pojav launchers (window, drawing, buffers).

**Performance**
- Chunks are built on a separate thread (no game freezes).
- Cave Culling and Entity Culling: nothing hidden behind terrain is drawn.
- Geometry upload without waiting for the GPU, with fences so nothing is written where the GPU still reads.
- Instant Block Updates: what you break or place shows in the same frame, without flicker (like Minecraft 1.18+).
- Chunk saving spread over time: 2 per tick (5 if many are pending). By time, 1 to 5, only with `-Dbta.saveBudgetMs=N`.
- VBO size chosen from the render distance.
- TexSkip: animated textures that did not change are not uploaded again.

**Drawing and memory**
- Merge Draw Calls: joins adjacent geometry ranges into a single draw call (OpenGL ES has no `glMultiDrawArrays`). Same picture on screen.
- Chunk Building Threads: Auto (2 on an 8-core phone) or up to 4 by hand, always leaving 2 cores free. Applied at once.
- Compact Vertices: terrain vertices of 24 bytes instead of 28 (-14% geometry memory). Positions stay exact.
- Reading an option never fails: if it cannot be loaded, the default is used.
- Optional memory report in the log (`-Dbta.memReport=true`).

**World**
- Smooth decoration (trees, ores) spread over time.
- Option to hide far chunks until they are decorated.
- Random ticks only nearby and with loaded neighbours.

**Console-style menus (Legacy Console Edition), when playing with a gamepad (detected automatically)**
- 3x3 crafting table as on console: 7 tabs and a row of 12 buttons with variants.
- Personal 2x2 crafting (X in game): 6 tabs and a row of 10.
- Both screens show the grid with what is missing in red, the result and the inventory.
- All table recipes, including the dyeing ones.
- Recipes BTA never lets you make are hidden.
- Furnace, chests, trommel, dispenser, activator and inventory get the console frame, a button guide with your controller's icons and console X/Y (X take half / place one, Y quick move).
- The interface sounds that BTA ships.
- It crafts with normal game clicks: nothing is duplicated or lost, and in multiplayer the server validates every click.

Console crafting controls:

```
A craft (hold = keep going)    X (or left stick click) craft all
Y only what you can make       LB/RB tab
d-pad/left stick choose        up/down variant
right stick: ingredient        B leave
```

The right stick chooses which planks, wool, stone, bucket, etc. (left/right). If the recipe has several
(bed: wool and planks), up/down changes which one (white frame). It offers only what you have, and the
choice applies to all recipes. Touch: tap the tab, the button, the variant or the result. Tapping an
ingredient slot that can be chosen changes it.

**Installer and tools**
- The installer carries only the differences and checks everything with SHA-1.
- It works in a window or on the console.
- Shortcuts `create-modpack.sh` and `create-modpack.bat`.
- Stutter meter (`-Dbta.prof=true`).

## Install and play

BTA-Movil does not include the game. Each player uses their own official copy.

1. Download the official BTA 8.0.1 for MultiMC/Prism: `bta.v8.0.1.mmc.zip`. Do not use copies from other
   sites, another BTA version or the server file.
2. Run the installer, `BTA-Movil-Installer.jar`. **Everything can be done on the phone; a PC is optional.**
   - **On the phone (Amethyst or Zalith), no PC needed:** save the jar in Downloads, use the launcher's
     "run .jar" option, pick it and, when asked, choose the `bta.v8.0.1.mmc.zip` you downloaded (or the
     `bta.jar` inside it). It finds the launcher's `.minecraft` folder and, with "Also install into this
     .minecraft folder" ticked (the default when it finds one), **creates the BTA-Movil version directly**:
     on Amethyst just pick it and play, no zip needed. It also creates the zips (for Zalith, or as a backup).
   - Optional, on a PC with Java 17: `java -jar BTA-Movil-Installer.jar`, then copy the generated zips to the phone.
   - Without a window: `./create-modpack.sh <bta.jar | bta.v8.0.1.mmc.zip | folder> [output_folder] [--install <.minecraft>]`
     (`create-modpack.bat` on Windows).
   - It creates `BTA-Movil-1.0.0-Amethyst.zip` and `BTA-Movil-1.0.0-Zalith.zip`. They are only for you: do not share them.
3. Install in your launcher.
   - **Amethyst:** if you ran the installer on the phone with the install option ticked, this step is
     already done: pick the BTA-Movil version. Otherwise, unzip `BTA-Movil-1.0.0-Amethyst.zip` inside `.minecraft` and accept replacing. It changes
     `versions/BTA-Movil/BTA-Movil.jar` and its `.json`, and leaves `README.txt`, `recommended-options.txt` and
     `BTA-Movil-controls.json` in `.minecraft`; your worlds and your `options.txt` are not touched.
     Then pick the version BTA-Movil.
   - **Zalith:** import `BTA-Movil-1.0.0-Zalith.zip` as a modpack and use the version name BTA-Movil. If you
     already have worlds, copy your `saves` folder. It brings options with render distance 10 and graphics on.
   - In both: Krypton Wrapper renderer, Java 17 and 2048-4096 MB of memory.

Sharing: see [SHARING.md](SHARING.md).

### Touch controls

`BTA-Movil-controls.json` (in both zips) is the classic button layout with bigger movement, jump, sneak,
inventory and attack/use buttons (70 instead of 50, similar to Pocket Edition) and a slightly larger top
bar. They work the same. In the launcher: controls editor > load/import, and pick the file (or copy it to the
launcher's `controlmap` folder).

### On-screen keyboard (with a gamepad)

When creating a world, the name and the seed typed with the on-screen keyboard are copied to the settings,
and a new server can be saved. `-Dbta.keyboardSync=false` leaves it as in BTA.

## Options

Options > Video > "BTA Movil":

| Option | What it does |
|---|---|
| Cave Culling | Does not draw sections hidden by terrain. |
| Entity Culling | Does not draw hidden mobs/chests (needs the previous one). |
| Smooth World Decoration | Spreads decoration over time: no drops while exploring. |
| Hide Undecorated Chunks | ON: far undecorated chunks are not shown (you see holes that fill in). OFF: you see decoration pop in, as in original BTA. |
| Instant Block Updates | What you break or place is built and shown in the same frame (no flicker while building). |
| Merge Draw Calls | Joins neighbouring draw calls into one (at once; visible in F3 "D:"). |
| Chunk Building Threads | Threads that build chunks: Auto (recommended) or 1..max (up to 4, leaving 2 cores free). More threads = faster loading while exploring, but more heat. At once. |
| Compact Vertices (restart) | Terrain vertices of 24 bytes instead of 28: -14% geometry memory. |
| Background Chunk Building (restart) | Builds chunks on separate threads (no freezes). OFF = as BTA, and Cave Culling turns off too. |
| Water Edge Fix (restart) | Water next to ice/glass without flickering triangles, and visible submerged ice. OFF = water as in BTA. |

If you use a resource pack with its own `terrain.vsh` and the log says it is not recognised, turn Compact
Vertices off.

Options > Controls > "Console Style (Legacy)", at the end:

| Option | What it does |
|---|---|
| Console Crafting | Console crafting. |
| Console Menus and Buttons | Frame, guide and X/Y on the other tables. |

Options marked (restart) apply when the game restarts; the others apply at once. All OFF is **not** exactly
BTA: TexSkip, mapped upload, random ticks only with loaded neighbours and 2 saves per tick stay active. To go
fully back to BTA you need the Java arguments below.

### F3

- "T: visible/total" = sections drawn.
- "D:" = draw calls (not the render distance). It goes down with Merge Draw Calls.
- "B:" on the entities line = hidden entities.

### Advanced settings (Java arguments), only if needed

```
-Dbta.decorateBudgetMs=20  decorate faster (may lower FPS in forests)
-Dbta.gles=false           if it starts black with another renderer
-Dbta.caveCull=false  -Dbta.entityCull=false  -Dbta.asyncMesh=false
-Dbta.texSkip=false   -Dbta.randomTickNoLoad=false
-Dbta.tickRadiusFix=false
-Dbta.smoothDecoration=false  -Dbta.vboMB=128
-Dbta.deferCascade=false   decorate in cascade like BTA (with Smooth World Decoration, nested
                           decoration is postponed)
-Dbta.savePerTick=2        chunks saved per tick (BTA: 5)
-Dbta.syncEdits=false      forces Instant Block Updates off
-Dbta.mappedUpload=false   uploads geometry like original BTA
-Dbta.prof=true            stutter meter in the log
-Dbta.prof.sample=decorate with the meter: which part of decoration is slow ([BTA-sample] in the
                           log, every 10 s)
-Dbta.glErrors=false       no OpenGL error log ([BTA-GL])
-Dbta.glDebug=true         also driver messages (if it has them)
-Dbta.recipeWarmup=false   console recipes when opening the menu
-Dbta.mergeDraws=false     forces Merge Draw Calls off
-Dbta.asyncMesh.threads=N  fixes the chunk-building threads (1 to 4), above the option
-Dbta.bytePool=false       does not reuse geometry buffers
-Dbta.tileSnapshot=false   building reads the live chest/furnace map (default: a copy)
-Dbta.packedVertices=false forces Compact Vertices off (=true forces it on)
-Dbta.memReport=true       after 2 minutes and every 10 writes the 40 classes using the most memory
                           to the log (forces a full GC: only for measuring)
-Dbta.fluidGap=0.005       gap between water and ice/glass (0.0001 = like BTA)
-Dbta.fluidContact=false   turns the submerged-ice contact pass off
```

## Known issues and troubleshooting

- **Water and ice.** Ice, glass, stained glass and permafrost under water no longer show flickering
  triangles: the water leaves a small gap (0.005 of a block) next to those blocks. From very far away
  something may still show (GPU limit). Submerged ice is visible from inside the water (contact pass);
  from outside, under the surface, it is almost invisible, as in BTA.
- **Black screen or crash at start** (for example with another renderer): try the Java argument
  `-Dbta.gles=false`.
- **The installer says the bta.jar is not valid.** The file is not exactly the official BTA 8.0.1. Usual
  causes: another BTA version; an incomplete or damaged download; the server jar instead of the client
  one; an already modified jar (with mods, or the `BTA-Movil.jar` of a previous install); a download from an
  unofficial site. Delete the file, download `bta.v8.0.1.mmc.zip` again from the official page and run the
  installer again. If it still fails on the phone, try the installer on a PC with `java -jar BTA-Movil-Installer.jar`.
- **With a gamepad you cannot use the classic 3x3 table** (labels, copying maps, chain mail): turn off
  Options > Controls > Console Style (Legacy) > Console Crafting.
- If your phone does not report OpenGL ES 3.2, it is not supported.

## Building from source

Requirements:

- **JDK 21** for the patchers (they use `Opcodes.ASM9` from the JDK's internal ASM).
- **Java 17** for the game: everything that goes inside it compiles with `--release 17`, and the tests run
  with Java 17 (`JDK17` variable, default `/opt/jdk17`).
- `python3`, `zip`/`unzip` and Node.js for the scripts.
- On Windows, **Docker**: the scripts run inside the image of `scripts/Dockerfile` (it brings both JDKs).

The original `bta.v8.0.1.mmc.zip` goes in `_local/` and is **never committed** (`_local/` is ignored).

```bash
bash scripts/verify.sh                        # quick: script syntax, patchers, installer and stubs compile
bash scripts/verify.sh --full                 # also rebuilds the jar from the original, warning ceiling, tests
bash scripts/verify.sh --game                 # --full + tests with the real game
bash scripts/verify-docker.sh --full          # the same, inside Docker (Windows); also accepts --game
bash source/create-installer.sh _local/bta.v8.0.1.mmc.zip   # builds BTA-Movil-Installer.jar
```

`--game` needs the libraries BTA itself uses in `_local/game-libs/` (versions from `patches/net.minecraft.json`
of the official zip; the sha1 is checked):

```bash
mkdir -p _local/game-libs && cd _local/game-libs
M=https://repo1.maven.org/maven2
for a in it/unimi/dsi/fastutil/8.5.15/fastutil-8.5.15 org/joml/joml/1.10.7/joml-1.10.7 \
         org/joml/joml-primitives/1.10.0/joml-primitives-1.10.0 org/slf4j/slf4j-api/2.0.7/slf4j-api-2.0.7 \
         com/google/code/gson/gson/2.10.1/gson-2.10.1; do
  f=$(basename $a).jar; curl -sfLO "$M/$a.jar"
  echo "$(curl -sfL "$M/$a.jar.sha1" | head -c 40)  $f" | sha1sum -c
done
J=https://jitpack.io/com/github/Better-than-Adventure/legacy-lwjgl3/1.0.6/legacy-lwjgl3-1.0.6.jar
curl -sfLO "$J" && echo "$(curl -sfL "$J.sha1" | head -c 40)  legacy-lwjgl3-1.0.6.jar" | sha1sum -c
B=https://jitpack.io/com/github/Better-than-Adventure/bta-brigadier/1.0.2/bta-brigadier-1.0.2.jar
curl -sfLO "$B" && echo "62a793e236961c71f4fd7c570131ee574f519954  bta-brigadier-1.0.2.jar" | sha1sum -c
```

Git hooks: `git config core.hooksPath .githooks` (pre-commit runs the quick check; pre-push runs `--full`
if `_local/bta.v8.0.1.mmc.zip` exists).

## Credits

- **Better than Adventure** by the Better than Adventure team: lead developers **jonkadelic** and
  **Mak (skydeckagogo)**, with Bestsoft100, Bigjango, Flamarine, Martin (sunsetsatellite), MelonMojito,
  NebulaBC and Useless. Official site: [betterthanadventure.net](https://betterthanadventure.net) ·
  [wiki](https://bta.miraheze.org) · [Discord](https://www.betterthanadventure.net/discord).
  BTA-Movil exists only because of their work; the game shaders here are theirs, converted to GLSL ES 3.20.
- **Minecraft** by Mojang Studios: BTA is a fork of Minecraft Beta 1.7.3.
- **Sodium** (CaffeineMC): the cave culling, entity culling and the default thread count follow ideas
  from Sodium (credited in the code where they are used).
- **glsl-fog** by Hugh Kennedy ([hughsk/glsl-fog](https://github.com/hughsk/glsl-fog)): fog functions in
  the world, entity, item and particle shaders.
- **Shadertoy** snippets credited in `acid_overlay.fsh` and `post/post.fsh`, and a curve by
  **Inigo Quilez (iq)** in `post/post.fsh`.
- **LWJGL 3.3.3** (`lwjgl.jar`, `lwjgl-glfw.jar`, `lwjgl-opengl.jar` in `source/libs`), Copyright (c)
  2012-present Lightweight Java Game Library, BSD 3-Clause license; included only to compile
  `source/src`. Full text in [source/libs/LICENSE-LWJGL.txt](source/libs/LICENSE-LWJGL.txt).

## Legal

- Better than Adventure and Minecraft belong to their respective owners. This repository and the
  installer contain none of their code or assets; each player needs their own official copy of BTA 8.0.1.
- Do not share the patched `BTA-Movil.jar` or the generated zips: share the installer (see
  [SHARING.md](SHARING.md)).
- No license has been chosen yet for the code in this repository: until then, all rights are reserved.
