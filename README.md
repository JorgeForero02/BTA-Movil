# BTA-Movil

Better than Adventure 8.0.1 running on Android. It's an unofficial port for the Amethyst and Zalith
launchers that renders straight through OpenGL ES 3.2, with a lot of performance work for phones and
console-style menus for when you play with a controller.

> **This is an unofficial project.** It isn't made, endorsed or supported by the Better than Adventure team
> or by Mojang, so please don't take BTA-Movil bugs to them. There's no BTA or Minecraft code in here. The
> installer patches your own copy of the official `bta.jar` and only carries the differences.

> **About AI use.** I'm a Spanish speaker. I wrote the documentation and its English translation with AI
> help, and part of the code too. Corrections are welcome.

- **Version:** 1.0.0, for BTA 8.0.1 only
- **Download:** `BTA-Movil-Installer.jar` from the [Releases](../../releases) page
- **Author:** JorgeForero02
- **No PC needed.** The installer runs on the phone from the launcher's "run .jar" option. On Amethyst it
  installs the version for you, and on Zalith it gives you a modpack zip to import.

## Contents

- [Compatibility](#compatibility)
- [Features](#features)
- [Install and play](#install-and-play)
- [Options](#options)
- [Known issues and troubleshooting](#known-issues-and-troubleshooting)
- [Building from source](#building-from-source)
- [Credits](#credits)
- [Legal](#legal)

## Compatibility

| | Supported |
|---|---|
| Game | Better than Adventure **8.0.1** only (the official `bta.v8.0.1.mmc.zip` for MultiMC/Prism) |
| Launchers | Amethyst and Zalith (both based on Pojav) |
| Renderer | Krypton Wrapper |
| Java | 17 |
| GPU | OpenGL ES **3.2** (Android 7+), 64-bit. You can check it with DevCheck or GPU Info |
| Memory | 2048-4096 MB for the game. A phone with 6 GB of RAM is recommended |
| Tested on | Mali-G615 |

## Features

**Compatibility**

- Renders directly with OpenGL ES 3.2 instead of going through the launcher's translation layer.
- The game's shaders are converted to GLSL ES 3.20.
- Fixes for Pojav-based launchers (window, rendering and buffers).

**Performance**

- Chunks are built on a background thread, so the game doesn't freeze while they load.
- Cave Culling and Entity Culling skip anything hidden behind terrain.
- Geometry is uploaded without waiting on the GPU. Fences make sure nothing gets written while the GPU is
  still reading it.
- Instant Block Updates: blocks you break or place show up in the same frame, with no flicker (like in
  Minecraft 1.18+).
- Chunk saving is spread out, 2 chunks per tick (5 when a lot are waiting). If you'd rather use a time
  budget of 1 to 5 ms, set `-Dbta.saveBudgetMs=N`.
- The VBO size depends on your render distance.
- TexSkip: animated textures are only uploaded again when they actually change.

**Rendering and memory**

- Merge Draw Calls joins geometry ranges that sit next to each other into a single draw call, since
  OpenGL ES has no `glMultiDrawArrays`. What you see on screen doesn't change.
- Chunk Building Threads: Auto (2 on an 8-core phone) or up to 4 set by hand, always leaving 2 cores free.
  Changes apply right away.
- Compact Vertices: terrain vertices take 24 bytes instead of 28, about 14% less geometry memory.
  Positions stay exact.
- If an option can't be read, the default is used instead of failing.
- Optional memory report in the log (`-Dbta.memReport=true`).

**World**

- Tree and ore decoration is spread out over time.
- An option to hide far chunks until they're decorated.
- Random ticks only run nearby, and only when the neighbouring chunks are loaded.

**Console-style menus** (Legacy Console Edition), turned on automatically when you play with a controller

- A 3x3 crafting table like on console, with 7 tabs and a row of 12 buttons with variants.
- 2x2 personal crafting (X in game), with 6 tabs and a row of 10.
- Both screens show the grid with missing items in red, the result and your inventory.
- Every table recipe is there, dyeing included.
- Recipes BTA never lets you make are hidden.
- The furnace, chests, trommel, dispenser, activator and inventory get the console frame, a button guide
  with your controller's icons and console-style X/Y (X takes half or places one, Y moves items quickly).
- It uses the interface sounds that come with BTA.
- Crafting goes through normal game clicks, so nothing gets duplicated or lost, and in multiplayer the
  server checks every click.

Console crafting controls:

```
A craft (hold = keep going)    X (or left stick click) craft all
Y only what you can make       LB/RB tab
d-pad/left stick choose        up/down variant
right stick: ingredient        B leave
```

The right stick picks which planks, wool, stone, bucket and so on you want to use (left/right). When a
recipe takes more than one of those (a bed takes wool and planks), up/down switches between them, and the
one you're changing gets a white frame. It only offers what you have, and your choice carries over to
every recipe. On touch, just tap a tab, a button, a variant or the result. Tapping an ingredient slot that
has options changes it.

**Installer and tools**

- The installer only carries the differences and checks everything with SHA-1.
- It works with a window or from the command line.
- `create-modpack.sh` and `create-modpack.bat` shortcuts.
- A stutter meter (`-Dbta.prof=true`).

## Install and play

BTA-Movil doesn't include the game, so you'll need your own official copy.

1. Download the official BTA 8.0.1 for MultiMC/Prism, `bta.v8.0.1.mmc.zip`. Don't use copies from other
   sites, a different BTA version or the server file.
2. Run `BTA-Movil-Installer.jar` on your phone (Amethyst or Zalith). Save it to Downloads, open the
   launcher's "run .jar" option and pick it. When it asks, choose the `bta.v8.0.1.mmc.zip` you downloaded
   (or the `bta.jar` inside it).
   - On Amethyst it finds the launcher's `.minecraft` folder. With "Also install into this .minecraft
     folder" ticked (it is by default when the folder is found), it installs the BTA-Movil version for you.
   - It also creates `BTA-Movil-1.0.0-Amethyst.zip` and `BTA-Movil-1.0.0-Zalith.zip`. The Zalith one is the
     modpack you'll import. Both are just for you, so please don't share them.
   - You can also run it on a PC with Java 17 (`java -jar BTA-Movil-Installer.jar`) and copy the zips to
     your phone.
   - Without a window: `./create-modpack.sh <bta.jar | bta.v8.0.1.mmc.zip | folder> [output_folder] [--install <.minecraft>]`
     (`create-modpack.bat` on Windows).
3. Set it up in your launcher.
   - **Amethyst:** go to Install a new version > Installed > BTA-Movil, and you're done.
     If you ran the installer on a PC (or unticked the install option), first unzip
     `BTA-Movil-1.0.0-Amethyst.zip` into `.minecraft` and accept replacing the files. That updates
     `versions/BTA-Movil/BTA-Movil.jar` and its `.json` and puts `README.txt`, `recommended-options.txt` and
     `BTA-Movil-controls.json` in `.minecraft`. Your worlds and your `options.txt` aren't touched.
   - **Zalith:** use "Import modpack (MMC)" with `BTA-Movil-1.0.0-Zalith.zip` and name the version
     BTA-Movil. If you already have worlds, copy your `saves` folder over. It comes with render distance 10
     and fancy graphics on.
   - Either way, use the Krypton Wrapper renderer, Java 17 and 2048-4096 MB of memory.

Want to share it with someone? Check [SHARING.md](SHARING.md).

### Touch controls

`BTA-Movil-controls.json` (included in both zips) is the classic button layout, but the movement, jump,
sneak, inventory and attack/use buttons are bigger (70 instead of 50, closer to Pocket Edition) and the top
bar is a bit larger. Everything works the same. To use it, open the launcher's controls editor, choose
load/import and pick the file, or copy it into the launcher's `controlmap` folder.

### On-screen keyboard (with a controller)

When you create a world, the name and seed you type with the on-screen keyboard now actually make it into
the settings, and you can save a new server. `-Dbta.keyboardSync=false` puts it back the way BTA does it.

## Options

You'll find them under Options > Video > "BTA Movil":

| Option | What it does |
|---|---|
| Cave Culling | Skips sections hidden behind terrain. |
| Entity Culling | Skips hidden mobs and chests. Needs Cave Culling. |
| Smooth World Decoration | Spreads decoration out over time, so the framerate doesn't dip while you explore. |
| Hide Undecorated Chunks | ON hides far chunks until they're decorated, so you'll see gaps that fill in. OFF shows decoration popping in, like in BTA. |
| Instant Block Updates | Blocks you break or place are rebuilt and shown in the same frame, so there's no flicker while building. |
| Merge Draw Calls | Joins neighbouring draw calls into one. Applies right away, and you can see the effect in F3 under "D:". |
| Chunk Building Threads | How many threads build chunks. Auto is recommended, or pick from 1 up to the max (4 at most, always leaving 2 cores free). More threads load terrain faster while exploring, but the phone gets hotter. Applies right away. |
| Compact Vertices (restart) | Terrain vertices take 24 bytes instead of 28, about 14% less geometry memory. |
| Background Chunk Building (restart) | Builds chunks on separate threads so the game doesn't freeze. OFF works like BTA and also turns Cave Culling off. |
| Water Edge Fix (restart) | Gets rid of the flickering triangles where water meets ice or glass, and makes submerged ice visible. OFF leaves water like in BTA. |

If you use a resource pack with its own `terrain.vsh` and the log says it isn't recognised, turn Compact
Vertices off.

Under Options > Controls > "Console Style (Legacy)", at the bottom:

| Option | What it does |
|---|---|
| Console Crafting | Turns on the console crafting screens. |
| Console Menus and Buttons | Adds the console frame, the button guide and X/Y to the other screens. |

Options marked (restart) take effect after you restart the game, and the rest apply right away. Keep in
mind that turning everything off **doesn't** give you plain BTA. TexSkip, the mapped upload, random ticks
only with loaded neighbours and 2 saves per tick stay on. To go fully back to BTA, use the Java arguments
below.

### F3

- "T: visible/total" is the number of sections being drawn.
- "D:" is the number of draw calls (not the render distance). Merge Draw Calls brings it down.
- "B:" on the entities line is the number of hidden entities.

### Advanced settings (Java arguments)

You only need these if something isn't working right or you want to measure things.

```
-Dbta.decorateBudgetMs=20  decorates faster (FPS may drop in forests)
-Dbta.gles=false           try this if the screen is black with another renderer
-Dbta.caveCull=false  -Dbta.entityCull=false  -Dbta.asyncMesh=false
-Dbta.texSkip=false   -Dbta.randomTickNoLoad=false
-Dbta.tickRadiusFix=false
-Dbta.smoothDecoration=false  -Dbta.vboMB=128
-Dbta.deferCascade=false   decorates in cascade like BTA (with Smooth World Decoration, nested
                           decoration waits for later)
-Dbta.savePerTick=2        chunks saved per tick (BTA uses 5)
-Dbta.syncEdits=false      forces Instant Block Updates off
-Dbta.mappedUpload=false   uploads geometry the way BTA does
-Dbta.prof=true            stutter meter in the log
-Dbta.prof.sample=decorate with the meter on, shows which part of decoration is slow
                           ([BTA-sample] in the log, every 10 s)
-Dbta.glErrors=false       turns off the OpenGL error log ([BTA-GL])
-Dbta.glDebug=true         also logs driver messages, if the driver sends any
-Dbta.recipeWarmup=false   loads the console recipes when the menu opens instead of ahead of time
-Dbta.mergeDraws=false     forces Merge Draw Calls off
-Dbta.asyncMesh.threads=N  sets the chunk building threads (1 to 4), overriding the option
-Dbta.bytePool=false       stops reusing geometry buffers
-Dbta.tileSnapshot=false   chunk building reads the live chest and furnace map instead of a copy
-Dbta.packedVertices=false forces Compact Vertices off (=true forces it on)
-Dbta.memReport=true       logs the 40 classes using the most memory after 2 minutes and then every
                           10 (it forces a full GC, so only use it to measure)
-Dbta.fluidGap=0.005       gap between water and ice/glass (0.0001 is what BTA uses)
-Dbta.fluidContact=false   turns off the submerged ice contact pass
```

## Known issues and troubleshooting

- **Water and ice.** Ice, glass, stained glass and permafrost under water no longer show flickering
  triangles, because the water leaves a tiny gap (0.005 of a block) next to them. From very far away you
  might still see a bit of it, which is a GPU limit. You can see submerged ice from inside the water
  thanks to an extra contact pass, but from outside, under the surface, it's still almost invisible, same
  as in BTA.
- **Black screen or crash on startup** (for example with a different renderer). Try the Java argument
  `-Dbta.gles=false`.
- **The installer says your bta.jar isn't valid.** That means the file isn't exactly the official BTA
  8.0.1. It's usually one of these: a different BTA version, an incomplete or broken download, the server
  jar instead of the client, a jar that was already modified (with mods, or the `BTA-Movil.jar` from an
  earlier install), or a download from an unofficial site. Delete it, download `bta.v8.0.1.mmc.zip` again
  from the official page and run the installer again. If it still fails on your phone, try it on a PC with
  `java -jar BTA-Movil-Installer.jar`.
- **You can't use the classic 3x3 table with a controller** (for labels, copying maps or chain mail). Turn
  off Options > Controls > Console Style (Legacy) > Console Crafting.
- If your phone doesn't report OpenGL ES 3.2, it isn't supported.

## Building from source

What you need:

- **JDK 21** for the patchers, since they use `Opcodes.ASM9` from the JDK's internal ASM.
- **Java 17** for the game. Everything that goes into the jar compiles with `--release 17`, and the tests
  run on Java 17 (`JDK17` variable, `/opt/jdk17` by default).
- `python3`, `zip`/`unzip` and Node.js for the scripts.
- **Docker** on Windows. The scripts run inside the `scripts/Dockerfile` image, which has both JDKs.

Put the original `bta.v8.0.1.mmc.zip` in `_local/`. That folder is ignored, so it never gets committed.

```bash
bash scripts/verify.sh                        # quick check: script syntax, patchers, installer and stubs compile
bash scripts/verify.sh --full                 # also rebuilds the jar from the original, checks the warning ceiling and runs the tests
bash scripts/verify.sh --game                 # --full plus tests with the real game
bash scripts/verify-docker.sh --full          # the same inside Docker (Windows), also takes --game
bash source/create-installer.sh _local/bta.v8.0.1.mmc.zip   # builds BTA-Movil-Installer.jar
```

`--game` needs the libraries BTA itself uses in `_local/game-libs/`. The versions come from
`patches/net.minecraft.json` in the official zip, and the sha1 is checked:

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

Git hooks: run `git config core.hooksPath .githooks`. The pre-commit hook runs the quick check, and
pre-push runs `--full` if `_local/bta.v8.0.1.mmc.zip` is there.

## Credits

- **Better than Adventure** by the Better than Adventure team, led by **jonkadelic** and
  **Mak (skydeckagogo)**, with Bestsoft100, Bigjango, Flamarine, Martin (sunsetsatellite), MelonMojito,
  NebulaBC and Useless. Official site: [betterthanadventure.net](https://betterthanadventure.net) ·
  [wiki](https://bta.miraheze.org) · [Discord](https://www.betterthanadventure.net/discord).
  BTA-Movil only exists thanks to their work, and the game shaders here are theirs, converted to GLSL ES 3.20.
- **Minecraft** by Mojang Studios. BTA is a fork of Minecraft Beta 1.7.3.
- **Sodium** (CaffeineMC). Cave culling, entity culling and the default thread count follow ideas from
  Sodium, and they're credited in the code where they're used.
- **glsl-fog** by Hugh Kennedy ([hughsk/glsl-fog](https://github.com/hughsk/glsl-fog)), used for the fog in
  the world, entity, item and particle shaders.
- **Shadertoy** snippets, credited in `acid_overlay.fsh` and `post/post.fsh`, and a curve by
  **Inigo Quilez (iq)** in `post/post.fsh`.
- **LWJGL 3.3.3** (`lwjgl.jar`, `lwjgl-glfw.jar` and `lwjgl-opengl.jar` in `source/libs`), Copyright (c)
  2012-present Lightweight Java Game Library, under the BSD 3-Clause license. It's only here to compile
  `source/src`. Full text in [source/libs/LICENSE-LWJGL.txt](source/libs/LICENSE-LWJGL.txt).

## Legal

- Better than Adventure and Minecraft belong to their respective owners. Neither this repository nor the
  installer contains any of their code or assets, and every player needs their own official copy of
  BTA 8.0.1.
- Please don't share the patched `BTA-Movil.jar` or the generated zips. Share the installer instead (see
  [SHARING.md](SHARING.md)).
- **License:** BTA-Movil is a modification of Better than Adventure, so using and sharing it is subject to
  the terms the Better than Adventure team sets for BTA and its modifications (see
  [betterthanadventure.net](https://betterthanadventure.net)). This repository doesn't grant a separate
  license.
