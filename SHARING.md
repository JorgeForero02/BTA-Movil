# Sharing BTA-Movil 1.0.0

BTA-Movil is a port of Better than Adventure 8.0.1 to Android, made by JorgeForero02.

The installer doesn't include the game. Each player brings their own official copy of BTA 8.0.1 and the
installer adapts it. That's why the installer can be shared, but the patched game can't.

## A) For the author

### What to upload

Only `BTA-Movil-Installer.jar`, as a release file.

Before uploading it, open it and make sure none of the files under "Never upload" below ended up inside.

### Where

- **GitHub Releases (recommended):** create a release with the tag `v1.0.0` and attach the installer.
- **Modrinth:** read their content rules first. Upload only the installer, and link the official BTA page
  instead of uploading anything from BTA.
- **The official BTA community (Discord or forum):** post in the projects or mods channel with the text
  below. Link the release instead of attaching the file, so people always get the latest version.

Follow each site's rules and make it clear that this is an unofficial port.

### Post text (copy and paste)

```
BTA-Movil 1.0.0 - Better than Adventure 8.0.1 on Android

An unofficial port of BTA 8.0.1 for Amethyst and Zalith, by JorgeForero02.
- Renders directly with OpenGL ES.
- Builds chunks on a separate thread.
- Skips caves and entities you can't see.
- Spreads world decoration out over time.
- Console-style menus when you play with a controller, 3x3 and 2x2 crafting included.

The game isn't included:
1. Download the official BTA 8.0.1 (the MultiMC/Prism zip).
2. Run BTA-Movil-Installer.jar and it creates the files for your launcher.
Full steps in SHARING.md.

Requirements:
- Android 7+, 64-bit, OpenGL ES 3.2.
- A phone with 6 GB of RAM is recommended.
- Java 17 and the Krypton Wrapper renderer.
- Tested on a Mali-G615.

Download: (link to the v1.0.0 release)
Known issues: see "Known issues and troubleshooting" in the README.
```

### Requirements (to post wherever you publish)

- Android 7 or newer, 64-bit.
- A GPU with OpenGL ES 3.2 (you can check it with DevCheck or GPU Info).
- A phone with 6 GB of RAM is recommended.
- 2048-4096 MB of memory for the game.
- Amethyst or Zalith, with Java 17 and the Krypton Wrapper renderer.
- An official copy of BTA 8.0.1 (each player downloads their own).
- Tested on a Mali-G615.

### Never upload (not to the repository and not to the release)

- `BTA-Movil.jar`
- `BTA-Movil-1.0.0-Amethyst.zip`
- `BTA-Movil-1.0.0-Zalith.zip`
- `bta.jar` (or the `bta.v8.0.1.mmc.zip` it comes in)

All of these contain Better than Adventure's code. `BTA-Movil.jar` is built from `bta.jar`, and the zips
include it. BTA can't be redistributed, but the installer can, because it applies the changes to the copy
each player downloads.

If someone asks you for "the ready-made jar", point them to the installer.

## B) For the player

### 0. Check your phone first

- Install DevCheck or GPU Info and look at the OpenGL ES version. It has to say OpenGL ES 3.2. If your
  phone doesn't reach 3.2, it isn't supported.
- Also make sure Android is 64-bit and version 7 or newer.

### 1. Download the official BTA 8.0.1

- Go to the official Better than Adventure page.
- Download version 8.0.1 for MultiMC/Prism: `bta.v8.0.1.mmc.zip`
- Don't use copies from other sites, a different BTA version or the server file.

### 2. Run the installer

On your phone (Amethyst or Zalith):

1. Save `BTA-Movil-Installer.jar` to Downloads.
2. Open the launcher's "run .jar" option.
3. Pick `BTA-Movil-Installer.jar`.
4. When it asks, choose the `bta.v8.0.1.mmc.zip` you downloaded (or the `bta.jar` inside it).

On Amethyst, with "Also install into this .minecraft folder" ticked (it is by default), the installer
installs the BTA-Movil version for you.

On a PC (with Java 17):

1. Run `java -jar BTA-Movil-Installer.jar`
2. Copy the zips it creates to your phone.

Either way, the installer creates these two files:

- `BTA-Movil-1.0.0-Amethyst.zip`
- `BTA-Movil-1.0.0-Zalith.zip`

They're just for you, so please don't share them. The Zalith one is the modpack you'll import.

### 3. Set it up in your launcher

Amethyst:

- If you ran the installer on your phone, go to Install a new version > Installed > BTA-Movil, and you're done.
- If you ran it on a PC (or unticked the install option):
  1. Unzip `BTA-Movil-1.0.0-Amethyst.zip` into `.minecraft` and accept replacing the files. That updates
     `versions/BTA-Movil/BTA-Movil.jar` and its `.json` and puts `README.txt`, `recommended-options.txt`
     and `BTA-Movil-controls.json` in `.minecraft`. Your worlds and your `options.txt` aren't touched.
  2. Go to Install a new version > Installed > BTA-Movil.

Zalith:

1. Use "Import modpack (MMC)" with `BTA-Movil-1.0.0-Zalith.zip`.
2. Name the version BTA-Movil.
3. If you already have worlds, copy your `saves` folder over.

Either way, use the Krypton Wrapper renderer, Java 17 and 2048-4096 MB of memory.

### 4. If the installer says your bta.jar isn't valid

That means the file isn't exactly the official BTA 8.0.1. It's usually one of these:

- A different BTA version.
- An incomplete or broken download.
- The server jar instead of the client.
- A jar that was already modified, either with mods or the `BTA-Movil.jar` from an earlier install.
- A download from an unofficial site.

To fix it:

1. Delete the file.
2. Download `bta.v8.0.1.mmc.zip` again from the official page.
3. Run the installer again.
4. If it still fails on your phone, try it on a PC with `java -jar BTA-Movil-Installer.jar`

### 5. If something goes wrong while playing

- If the screen stays black or the game closes on startup (for example with a different renderer), try
  the Java argument `-Dbta.gles=false`.
- If you have a controller connected and can't use the classic 3x3 table (for labels, copying maps or
  chain mail), turn off Options > Controls > Console Style (Legacy) > Console Crafting.
- The rest of the options and arguments are in the README.
