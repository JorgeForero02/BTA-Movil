BTA 8.0.1 MOBILE - VERSION 1.0.0

Better than Adventure 8.0.1 for Android (Amethyst and Zalith).
Draws straight with OpenGL ES.
Unofficial port, made by JorgeForero02.

REQUIREMENTS
  - Java 17 and the Krypton Wrapper renderer.
  - Game memory: 2048-4096 MB.
  - OpenGL ES 3.2 (Android 7+), 64-bit.
  - A phone with 6 GB of RAM is recommended.
  - Tested on a Mali-G615.
  Check your OpenGL ES version with DevCheck or GPU Info.

WHAT IT BRINGS
  Compatibility
  - Direct drawing with OpenGL ES 3.2, without the launcher's translator.
  - Shaders converted to GLSL ES 3.20.
  - Fixes for the Pojav launchers (window, drawing, buffers).
  Performance
  - Chunks are built on a separate thread (the game does not freeze).
  - Cave Culling and Entity Culling: does not draw what the terrain hides.
  - Geometry upload without waiting for the GPU, with fences so nothing
    is written where the GPU is still reading.
  - Instant Block Updates: what you break or place shows in the same
    frame, without flicker (like Minecraft 1.18+).
  - Chunk saving spread out: 2 per tick (5 if there is a lot to spare).
    By time, from 1 to 5, only with -Dbta.saveBudgetMs=N.
  - VBO size according to the render distance.
  - TexSkip: does not upload animated textures again if they did not
    change.
  Drawing and memory optimizations
  - Merge Draw Calls: joins into a single draw call the geometry spans
    that ended up next to each other (OpenGL ES has no
    glMultiDrawArrays). Same result on screen.
  - Chunk Building Threads: threads that build chunks. Auto (2 on an
    8-core phone) or set by hand up to 4, always leaving 2 cores free.
    Applies instantly.
  - Compact Vertices: terrain vertices of 24 bytes instead of 28
    (-14% geometry memory). The position stays exact.
  - Reading an option never fails: if it does not load, it uses the
    default value.
  - Optional memory report in the log (-Dbta.memReport=true).
  World
  - Smooth decoration (trees, ores) spread over time.
  - Option to hide distant chunks until they are decorated.
  - Random ticks only nearby and with the neighbors loaded.
  - Saving spread per tick.
  Console-style menus (Legacy Console Edition), when playing with a
  controller (detected automatically)
  - 3x3 crafting table as on console: 7 tabs and a row of 12 buttons
    with variants.
  - Personal 2x2 crafting (X in game): 6 tabs and a row of 10.
  - Both screens show the grid with what is missing in red, the result
    and the inventory.
  - All the table recipes, including dyeing by color.
  - Recipes that BTA never lets you make are hidden.
  - Furnace, chests, trommel, dispenser, activator and inventory:
    - console frame;
    - button guide with the icons of your controller;
    - X/Y as on console (X take half / place one, Y quick move).
  - Interface sounds that BTA already has.
  - Crafting is done with normal game clicks: nothing is duplicated or
    lost, and in multiplayer the server validates every click.
  Console crafting controls
      A craft (hold = keep going)   X (or left stick click) craft all
      Y only what you can make      LB/RB tab
      d-pad/left stick choose       up/down variant
      right stick: ingredient       B exit
    The right stick picks which planks, which wool, which stone, which
    bucket, etc. (left/right). If the recipe has several (bed: wool and
    planks), up/down switches which one (white frame). It only offers
    what you have, and the choice applies to all recipes.
    Touch: tap the tab, the button, the variant or the result. Tapping
    the slot of a selectable ingredient changes it.
  Installer and tools
  - The installer carries only the differences and checks everything
    with SHA-1.
  - Works in a window or in the console.
  - Shortcuts create-modpack.sh and create-modpack.bat.
  - Stutter meter (-Dbta.prof=true).

AMETHYST
  1. Run BTA-Movil-Installer.jar with "run .jar" and pick the
     bta.v8.0.1.mmc.zip (or its bta.jar). With "Also install into this
     .minecraft folder" ticked (the default), it installs the
     BTA-Movil version directly.
  2. Go to install a new version > Installed > BTA-Movil. That is all.
  If you ran the installer on a PC (or with that option unticked):
  before step 2, unzip BTA-Movil-1.0.0-Amethyst.zip inside .minecraft
  and accept replacing. It changes versions/BTA-Movil/BTA-Movil.jar and
  its .json, and leaves README.txt, recommended-options.txt and
  BTA-Movil-controls.json in .minecraft (replaced if they were already
  there). Your worlds and your options.txt are not touched.

ZALITH (BTA-Movil-1.0.0-Zalith.zip)
  1. Run the installer to create the modpack zip, then use
     "Import modpack (MMC)" with BTA-Movil-1.0.0-Zalith.zip.
  2. Use the version name BTA-Movil.
  3. If you already have worlds, copy your saves folder.
  It comes with render distance 10 and fancy graphics on.

WATER AND ICE
  Ice, glass, stained glass and permafrost under water no longer show
  flickering triangles: the water leaves a small gap (0.005 of a block)
  next to those blocks. From very far away something may still show
  (GPU limit). Java argument to adjust it:
  -Dbta.fluidGap=0.005 (0.0001 = like BTA).
  Submerged ice: from inside the water it is now visible (contact pass;
  -Dbta.fluidContact=false turns it off). From outside, below the
  surface, it is still almost invisible as in BTA.

ON-SCREEN KEYBOARD (with a controller)
  When creating a world, the name and seed typed with the on-screen
  keyboard are copied to the settings, and a new server can be saved.
  -Dbta.keyboardSync=false leaves it like BTA.

TOUCH CONTROLS (BTA-Movil-controls.json, included in both zips)
  The classic button layout with the movement, jump, sneak, inventory
  and attack/use buttons bigger (70 instead of 50, similar to Pocket
  Edition) and the top bar a bit larger. They work the same. In the
  launcher: controls editor > load/import, and pick the file (or copy
  it to the launcher's controlmap folder).

OPTIONS (Options > Video > "BTA Movil")
  Cave Culling              does not draw sections hidden by terrain
  Entity Culling            does not draw mobs/chests that are hidden
                            (requires the previous one)
  Smooth World Decoration   spreads the decoration: no drops while
                            exploring
  Hide Undecorated Chunks   ON: does not show distant undecorated chunks
                            ("holes" are seen that get filled in)
                            OFF: you see the decoration pop in at once,
                            like original BTA
  Instant Block Updates     what you break or place is built and shown
                            in the same frame (no flicker when building)
  Merge Draw Calls          joins neighboring draw calls into one
                            (instant; visible in F3 "D:")
  Chunk Building Threads    threads that build chunks: Auto
                            (recommended) or 1..max (up to 4, leaving 2
                            cores free). More threads = faster loading
                            while exploring, but more heat. Instant.
  Compact Vertices          terrain vertices of 24 bytes instead of
  (restart)                 28: -14% geometry memory
                            (when the game restarts)
    If you use a resource pack with its own terrain.vsh and the log
    says it does not recognize it, turn Compact Vertices off.
  Background Chunk Building build chunks on separate threads (the game
  (restart)                 does not freeze). OFF = like BTA, and
                            Cave Culling turns off too.
  Water Edge Fix            water next to ice/glass without flickering
  (restart)                 triangles, and submerged ice visible.
                            OFF = water like in BTA.

OPTIONS (Options > Controls > "Console Style (Legacy)", at the end)
  Console Crafting             console crafting
  Console Menus and Buttons    frame, guide and X/Y in the other tables

  Those marked (restart) apply when the game restarts; the others,
  instantly. OFF on all of them is NOT exactly BTA: TexSkip, mapped
  upload, random ticks only with loaded neighbors and 2 saves per tick
  stay active. To go fully back to BTA you need the Java arguments of
  ADVANCED SETTINGS.

F3
  "T: visible/total" = sections drawn.
  "D:" = draw calls (it is not the render distance). Goes down with
  Merge Draw Calls.
  "B:" on the entities line = hidden entities.

ADVANCED SETTINGS (Java arguments), only if needed:
  -Dbta.decorateBudgetMs=20  decorates faster (may lower FPS in forests)
  -Dbta.gles=false      if it starts black with another renderer
  -Dbta.caveCull=false  -Dbta.entityCull=false  -Dbta.asyncMesh=false
  -Dbta.texSkip=false   -Dbta.randomTickNoLoad=false
  -Dbta.tickRadiusFix=false
  -Dbta.smoothDecoration=false  -Dbta.vboMB=128
  -Dbta.deferCascade=false   decorates in cascade like BTA (with Smooth
                             World Decoration, nested work is deferred)
  -Dbta.savePerTick=2        chunks saved per tick (BTA: 5)
  -Dbta.syncEdits=false      forces Instant Block Updates off
  -Dbta.mappedUpload=false   uploads geometry like original BTA
  -Dbta.prof=true (stutter meter in the log)
  -Dbta.prof.sample=decorate with the meter: which part of the
                             decoration is slow ([BTA-sample] in the
                             log, every 10 s)
  -Dbta.glErrors=false       no OpenGL error log ([BTA-GL])
  -Dbta.glDebug=true         also driver messages (if it has them)
  -Dbta.recipeWarmup=false   console recipes when opening the menu
  -Dbta.mergeDraws=false     forces Merge Draw Calls off
  -Dbta.asyncMesh.threads=N  sets the chunk building threads (1 to 4),
                             over the option
  -Dbta.bytePool=false       does not reuse the geometry buffers
  -Dbta.tileSnapshot=false   building reads the live map of chests and
                             furnaces (by default it reads a copy)
  -Dbta.packedVertices=false forces Compact Vertices off
                             (=true forces it on)
  -Dbta.memReport=true       at 2 minutes and every 10 it writes to the
                             log the 40 classes that use the most
                             memory (forces a full GC: only to measure)

HOW TO SHARE
  Share only BTA-Movil-Installer.jar.
  NEVER share these files, because they carry the BTA game inside:
    - BTA-Movil.jar
    - BTA-Movil-1.0.0-Amethyst.zip
    - BTA-Movil-1.0.0-Zalith.zip
  Each player downloads their own official BTA 8.0.1, and the
  installer builds their files. Full steps in SHARING.md.

Personal use. BTA cannot be redistributed: share the installer, not
the jar.
