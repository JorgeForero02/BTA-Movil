package net.betterthanadventure.btamobile.meshfix;

import net.minecraft.core.block.Block;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.world.WorldSource;
import net.minecraft.core.world.pos.TilePosc;

/**
 * Water faces that touch a full see-through block (ice, glass, tinted glass...: the faces FluidInset pulls in):
 * "contact faces", drawn in a pass of their own.
 *
 * Why: with "Translucent Depth Check" BTA first writes the depth of every translucent face and then draws only the
 * nearest one per pixel. A water face in front of an ice face (seen from inside the water) is nearer, so the ice
 * face behind it was never drawn: ice fully under water looked invisible (also in BTA). And a water face drawn
 * close to a block face fights it in the depth test further away.
 *
 * So, without sorting faces:
 *  - the section mesher (AsyncChunkRenderer.Job) writes the contact faces first in the translucent geometry of
 *    each section ({@link #mode}: only contact faces, then everything else) and remembers how many vertices they
 *    take ({@link #uploaded});
 *  - when the translucent pass draws, its first walk over the regions ({@link #renderAllLists}) is done twice: first
 *    only those vertices of every section of every region (zalithfix.MultiDraw), in color, blended, without writing
 *    depth and pulled a little toward the camera (glPolygonOffset -1, -1), then the rest. With "Translucent
 *    Depth Check" that happens in its depth pre-pass, so they are tested only against solid terrain
 *    (seen through ice from outside, the water behind it is tinted, at any distance), and they take no part in
 *    the "nearest translucent face" depth: the ice face behind them (seen from inside the water) is drawn. Against
 *    glass (solid pass) the offset makes the water face win at any distance. Then the rest is drawn as in BTA.
 *    The ice or glass face is blended over the water tint (not under it): a slightly weaker tint, never a fight.
 * Water against air, partial blocks, opaque blocks and other fluids, and lava, stay as in BTA. Sections meshed by
 * BTA's own code (fallback) keep BTA's order: there all faces are drawn as in BTA.
 *
 * -Dbta.fluidContact=false turns it off; it is also off with -Dbta.fluidGap=0.0001 (everything as in BTA).
 */
public final class FluidContact {
  private FluidContact() { }
  public static final boolean ON = FluidInset.ON && !"false".equalsIgnoreCase(System.getProperty("bta.fluidContact"));

  // ---------------------------------------------------------------- meshing (worker threads)
  public static final int ALL = 0, ONLY_CONTACT = 1, NO_CONTACT = 2;
  private static final ThreadLocal<int[]> MODE = ThreadLocal.withInitial(() -> new int[1]);

  /** Which water faces BlockModelFluid.render draws on this thread (ALL outside the section mesher). */
  public static void mode(int faceMode) { MODE.get()[0] = faceMode; }

  /**
   * In BlockModelFluid.shouldSideBeRendered(world, box, neighbour, side), after FluidInset.hidden: 0 = do not draw
   * this face, -1 = let BTA decide.
   */
  public static int filter(WorldSource world, TilePosc neighbour, net.minecraft.core.util.helper.Side side, Block<?> fluid) {
    if (!ON) return -1;
    int mode = MODE.get()[0];
    if (mode == ALL) return -1;
    boolean contact = contact(world, neighbour, fluid);
    if (mode == ONLY_CONTACT) return contact ? -1 : 0;
    return contact ? 0 : -1;
  }

  /** The water face toward {@code neighbour} touches a full see-through block (the faces FluidInset pulls in). */
  static boolean contact(WorldSource world, TilePosc neighbour, Block<?> fluid) {
    try {
      return fluid != null && fluid.getMaterial() == Materials.WATER && FluidInset.touches(world, neighbour.x(), neighbour.y(), neighbour.z());
    } catch (RuntimeException ignored) { return false; }
  }

  // ---------------------------------------------------------------- drawing (main thread)
  /**
   * First vertex of a section's translucent range -> (vertex count of that range << 32 | contact vertices at its
   * start). The count is checked when drawing, so a stale key can never apply to a different range. Open
   * addressing over long keys, no boxing (looked up for every visible range every frame).
   */
  private static long[] keys = new long[256], values = new long[256];
  private static Object[] entries = new Object[256]; // the Entry each key was stored for: only its own removal clears it
  private static int size;
  private static final long FREE = Long.MIN_VALUE;
  static { java.util.Arrays.fill(keys, FREE); }
  /** The terrain renderer (and so the VBO) the stored ranges belong to: a new one (render distance change, F3+A)
   *  starts over, its vertex numbers mean other ranges. */
  private static Object owner;

  /** A section's translucent geometry was uploaded to {@code terrain}'s VBO: its first {@code contact} vertices
   *  are contact faces. */
  public static void uploaded(Object terrain, net.minecraft.client.render.terrain.VertexBuffer.Entry<?> entry, int contact, int vertices) {
    if (terrain != owner) { clear(); owner = terrain; }
    if (entry == null) return;
    int first = first(entry);
    if (first < 0) return;
    if (contact > 0 && contact <= vertices) put(first, ((long) vertices << 32) | contact, entry); else remove(first);
  }

  /** VertexBuffer.removeEntry: that range is gone (its space may hold another section later). Only the entry the key
   *  was stored for clears it (not a solid range, nor a late removal from a previous renderer, at the same place). */
  public static void removed(net.minecraft.client.render.terrain.VertexBuffer.Entry<?> entry) {
    if (entry == null || size == 0) return;
    int first = first(entry);
    if (first < 0) return;
    int index = slot(first);
    if (keys[index] != FREE && entries[index] == entry) remove(first);
  }

  public static void clear() {
    java.util.Arrays.fill(keys, FREE);
    java.util.Arrays.fill(entries, null);
    size = 0;
    owner = null; // do not keep the old world's renderer alive
  }

  private static int first(net.minecraft.client.render.terrain.VertexBuffer.Entry<?> entry) {
    try {
      int vertexSize = entry.config.getVertexSize();
      return vertexSize > 0 ? (int) (entry.position / vertexSize) : -1;
    } catch (RuntimeException ignored) { return -1; }
  }

  /** Contact vertices at the start of the range [first, first + count) (0 if none or if it is not the range stored). */
  public static int at(int first, int count) {
    if (size == 0) return 0;
    int index = slot(first);
    if (keys[index] == FREE) return 0;
    long packed = values[index];
    return (int) (packed >>> 32) == count ? (int) packed : 0;
  }

  private static int slot(long key) {
    int mask = keys.length - 1, index = (int) (key * 0x9E3779B97F4A7C15L >>> 40) & mask;
    while (keys[index] != FREE && keys[index] != key) index = (index + 1) & mask;
    return index;
  }

  private static void put(long key, long value, Object entry) {
    if ((size + 1) * 2 > keys.length) grow();
    int index = slot(key);
    if (keys[index] == FREE) size++;
    keys[index] = key;
    values[index] = value;
    entries[index] = entry;
  }

  private static void remove(long key) {
    int index = slot(key);
    if (keys[index] == FREE) return;
    keys[index] = FREE;
    entries[index] = null;
    size--;
    int mask = keys.length - 1;
    for (int j = (index + 1) & mask; ; j = (j + 1) & mask) { // re-insert the rest of the cluster
      long nextKey = keys[j];
      if (nextKey == FREE) break;
      long nextValue = values[j];
      Object nextEntry = entries[j];
      keys[j] = FREE;
      entries[j] = null;
      size--;
      put(nextKey, nextValue, nextEntry);
    }
  }

  private static void grow() {
    long[] oldKeys = keys, oldValues = values;
    Object[] oldEntries = entries;
    keys = new long[oldKeys.length * 2];
    values = new long[oldKeys.length * 2];
    entries = new Object[oldKeys.length * 2];
    java.util.Arrays.fill(keys, FREE);
    size = 0;
    for (int index = 0; index < oldKeys.length; index++) if (oldKeys[index] != FREE) put(oldKeys[index], oldValues[index], oldEntries[index]);
  }

  static int size() { return size; }

  /** Set by the hooks in TerrainRendererMultiDraw.renderTranslucentTerrain. */
  private static boolean translucent, depthOnly, contactDone;
  /** {@link #CONTACT}: the walk that draws only the contact faces; {@link #NORMAL}: any other drawing. */
  public static final int NORMAL = 0, CONTACT = 1;
  private static int phase = NORMAL;

  public static void beginTranslucent(Object terrain) {
    translucent = terrain == owner; // ranges of another renderer: nothing stored applies
    depthOnly = false;
    contactDone = false;
    phase = NORMAL;
  }
  public static void endTranslucent() { translucent = false; depthOnly = false; contactDone = false; phase = NORMAL; }

  /** Start of every frame: a translucent pass cut short by an exception never leaks into the next one. */
  public static void frameStart() { translucent = false; depthOnly = false; contactDone = false; phase = NORMAL; }

  /** Replaces GLRenderer.setColorMask in renderTranslucentTerrain: color mask off = depth pre-pass. */
  public static void colorMask(boolean r, boolean g, boolean b, boolean a) {
    net.minecraft.client.render.renderer.GLRenderer.setColorMask(r, g, b, a);
    depthOnly = !(r || g || b || a);
  }

  /**
   * Replaces the renderAllLists(pass) calls of renderTranslucentTerrain; the first one that runs (the depth pre-pass,
   * or the color pass when there is none) does this: when some section has contact faces, the regions are walked twice, the first time drawing only
   * the contact faces of all of them (so none is tested against another region's translucent depth), the second
   * time everything else. Later walks of this pass (the color pass after the pre-pass) skip the contact faces.
   */
  public static void renderAllLists(net.minecraft.client.render.terrain.TerrainRendererMultiDraw terrain, int pass) {
    if (!active() || contactDone) { terrain.renderAllLists(pass); return; }
    phase = CONTACT;
    try { terrain.renderAllLists(pass); } finally { phase = NORMAL; }
    contactDone = true;
    terrain.renderAllLists(pass);
  }

  /** True while drawing the translucent terrain and some section has contact faces. */
  public static boolean active() { return ON && translucent && size > 0; }
  public static boolean depthOnly() { return depthOnly; }
  public static int phase() { return phase; }
  /** The contact faces were already drawn in this translucent pass (later draws leave them out). */
  public static boolean contactDone() { return contactDone; }
}
