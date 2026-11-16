# BTA-Movil 1.0.0 - How to share it

BTA-Movil is a port of Better than Adventure 8.0.1 to Android, made by JorgeForero02.

The installer does NOT include the game:

- Each player supplies their own official copy of BTA 8.0.1.
- The installer adapts it.

So the installer can be shared, but never the already patched game.

## A) For the author

### What to upload

As release files:

- `BTA-Movil-Installer.jar`

Before uploading, open the file and confirm it does not contain any of the files in "Never upload" below.

### Where

- **GitHub Releases (recommended):**
  1. Create a release with the tag `v1.0.0`.
  2. Attach the installer as a release file.
- **Modrinth:**
  1. Check their content rules first.
  2. Upload only the installer.
  3. Link the official BTA page instead of uploading anything from BTA.
- **Official BTA community (Discord/forum):**
  - Post in the projects or mods channel, with the presentation text.
  - Put the link to the release instead of the loose file, so people always download the latest version.

Respect each site's rules and make clear that it is an unofficial port.

### Presentation text (copy and paste)

```
BTA-Movil 1.0.0 - Better than Adventure 8.0.1 on Android

Unofficial port of BTA 8.0.1 for Amethyst and Zalith, by JorgeForero02.
- Draws directly with OpenGL ES.
- Builds chunks on a separate thread.
- Does not draw hidden caves or entities.
- Spreads world decoration over time.
- Brings console-style menus when playing with a gamepad (3x3 and 2x2 crafting included).

It does not include the game:
1. Download the official BTA 8.0.1 (MultiMC/Prism zip).
2. Run BTA-Movil-Installer.jar; it creates the files for your launcher.
Full steps in SHARING.md.

Requirements:
- Android 7+, 64-bit, OpenGL ES 3.2.
- A phone with 6 GB of RAM is recommended.
- Java 17, Krypton Wrapper renderer.
- Tested on a Mali-G615.

Download: (link to the v1.0.0 release)
Known issues: see "Known issues and troubleshooting" in the README.
```

### Requirements (to post wherever you publish)

- Android 7 or higher, 64-bit.
- A GPU with OpenGL ES 3.2 (check with DevCheck or GPU Info).
- A phone with 6 GB of RAM is recommended.
- Game memory: 2048-4096 MB.
- Amethyst or Zalith, with Java 17 and the Krypton Wrapper renderer.
- An official copy of BTA 8.0.1 (each player downloads their own).
- Tested on a Mali-G615.

### Never upload (neither to the repository nor to the release)

- `BTA-Movil.jar`
- `BTA-Movil-1.0.0-Amethyst.zip`
- `BTA-Movil-1.0.0-Zalith.zip`
- `bta.jar` (nor the zip `bta.v8.0.1.mmc.zip`)

Why:

- All of them contain the code of Better than Adventure: `BTA-Movil.jar` is generated from the `bta.jar`,
  and the zips include it.
- BTA cannot be redistributed.
- What you can share is the installer: it applies the changes to the copy each player downloads.

If someone asks for "the ready-made jar", send them to the installer.

## B) For the player

### 0. Before: check your phone

- Install DevCheck or GPU Info and look at the OpenGL ES version. It must say OpenGL ES 3.2. If your phone
  does not reach 3.2, it is not supported.
- Also check that Android is 64-bit and version 7 or higher.

### 1. Download the official BTA 8.0.1

- Go to the official Better than Adventure page.
- Download version 8.0.1 for MultiMC/Prism: `bta.v8.0.1.mmc.zip`
- Do not use copies from other sites, another BTA version, or the server file.

### 2. Run the installer

On the phone (Amethyst or Zalith):

1. Save `BTA-Movil-Installer.jar` in Downloads.
2. In the launcher use the "run .jar" option.
3. Pick `BTA-Movil-Installer.jar`.
4. When asked, choose the `bta.v8.0.1.mmc.zip` you downloaded (or the `bta.jar` inside it).

On a PC (with Java 17):

1. Run: `java -jar BTA-Movil-Installer.jar`
2. Copy the generated zips to the phone.

The installer creates these two files:

- `BTA-Movil-1.0.0-Amethyst.zip`
- `BTA-Movil-1.0.0-Zalith.zip`

They are only for you: do not share them.

### 3. Install in your launcher

Amethyst:

1. Unzip `BTA-Movil-1.0.0-Amethyst.zip` inside `.minecraft`.
2. Accept replacing. It changes `versions/BTA-Movil/BTA-Movil.jar` and its `.json`, and leaves
   `README.txt`, `recommended-options.txt` and `BTA-Movil-controls.json` in `.minecraft`; your worlds and
   your `options.txt` are not touched.
3. Pick the version BTA-Movil.

Zalith:

1. Import `BTA-Movil-1.0.0-Zalith.zip` as a modpack.
2. Use the version name BTA-Movil.
3. If you already have worlds, copy your `saves` folder.

In both: Krypton Wrapper renderer, Java 17 and 2048-4096 MB of memory.

### 4. If the installer says the bta.jar is not valid

It means the file is not exactly the official BTA 8.0.1. Usual causes:

- It is another BTA version.
- The download was incomplete or damaged.
- It is the server jar and not the client one.
- It is an already modified jar: with mods, or the `BTA-Movil.jar` of a previous install.
- It was downloaded from an unofficial site.

Fix:

1. Delete the file.
2. Download `bta.v8.0.1.mmc.zip` again from the official page.
3. Run the installer again.
4. If it still fails on the phone, try the installer on a PC with: `java -jar BTA-Movil-Installer.jar`

### 5. If something goes wrong while playing

- If it starts black or closes at start (for example with another renderer), try the Java argument
  `-Dbta.gles=false`.
- If with a gamepad connected you cannot use the classic 3x3 table (labels, copying maps, chain mail), turn
  off Options > Controls > Console Style (Legacy) > Console Crafting.
- The other options and arguments are in the README.
