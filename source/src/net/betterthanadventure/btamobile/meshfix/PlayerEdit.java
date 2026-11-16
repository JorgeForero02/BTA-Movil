package net.betterthanadventure.btamobile.meshfix;

/**
 * Where and when the player last broke or placed a block (main thread only).
 *
 * Same idea as vanilla Minecraft 1.18+ "Semi Blocking" (RenderSection.isDirtyFromPlayer, compileSync):
 * sections changed by the player's own actions are rebuilt synchronously in the same frame, the rest
 * stays on the workers. Without that, the sections touched by one block change could be uploaded in
 * different frames and the block just placed or broken would flash for a frame or two.
 *
 * Wiring (source/patchers/EditPatch.java): the start of PlayerController.destroyBlock,
 * useOrPlaceItemStackOnTile and placeItemStackOnTile calls {@link #at}. Those are the base class
 * methods that PlayerControllerSP/MP also go through (super calls), in single and multiplayer.
 * A section dirtied shortly after, close to that position, is rebuilt in sync (AsyncChunkRenderer).
 * Option "Instant Block Updates" (on by default); -Dbta.syncEdits=false.
 */
public final class PlayerEdit {
  private PlayerEdit() { }
  private static final boolean FORCED_OFF = "false".equalsIgnoreCase(System.getProperty("bta.syncEdits"));
  /** How long after the action a section dirtied nearby still counts as changed by the player. */
  static final long WINDOW_NS = 250_000_000L;
  /** Blocks around the clicked block: the placed block is a neighbour and its own neighbours change too. */
  static final int REACH = 2;
  private static int x, y, z;
  private static long when;
  private static boolean any;

  public static boolean enabled() { return !FORCED_OFF && OptionsSafe.get("syncEdits"); }

  /** Start of a player block action (arguments: the targeted block). */
  public static void at(net.minecraft.core.world.pos.TilePosc pos) {
    if (pos == null) return;
    try { set(pos.x(), pos.y(), pos.z(), System.nanoTime()); } catch (Throwable ignored) { }
  }

  static void set(int blockX, int blockY, int blockZ, long now) { x = blockX; y = blockY; z = blockZ; when = now; any = true; }

  /** True if a section with this box [x0, x0+sizeX) ... was dirtied by the player's last action. */
  public static boolean affects(int x0, int y0, int z0, int sizeX, int sizeY, int sizeZ) { return affects(x0, y0, z0, sizeX, sizeY, sizeZ, System.nanoTime()); }

  static boolean affects(int x0, int y0, int z0, int sizeX, int sizeY, int sizeZ, long now) {
    if (!any || now - when > WINDOW_NS || now - when < 0) return false;
    return x + REACH >= x0 && x - REACH < x0 + sizeX
        && y + REACH >= y0 && y - REACH < y0 + sizeY
        && z + REACH >= z0 && z - REACH < z0 + sizeZ;
  }

  /** World change: forget the last action. */
  public static void clear() { any = false; }
}
