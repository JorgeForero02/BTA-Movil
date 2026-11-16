package net.betterthanadventure.btamobile.meshfix;

import net.minecraft.core.block.Block;
import net.minecraft.core.block.material.Material;
import net.minecraft.core.block.material.Materials;
import net.minecraft.core.world.WorldSource;
import net.minecraft.core.world.pos.TilePosc;

/**
 * Where water faces sit when they touch a block (BlockModelFluid, source/patchers/FluidPatch.java).
 *
 * BTA draws a water face against ice, glass, tinted glass, leaves... 0.0001 of a block from that block's face
 * (sides), 0.001 (bottom) or on exactly the same plane (top face, when water lies over a neighbouring column and a
 * block sits on top), and against permaice (opaque, ice material) too. Two faces that close fight in the depth test,
 * and the gap the GPU can tell apart shrinks with the square of the distance: diagonal triangles that flicker,
 * worse further away (worst on Mali; also on PC, from below).
 *
 * Here, only for water and only where a face touches a full block (not air, not a fluid, cube shaped):
 *  - the face is pulled {@link #GAP} (0.005, chosen on the phone) into the water: sides ({@link #side}), bottom
 *    ({@link #bottom}), and the top face's corners under any full block ({@link #topMax});
 *  - its edges are moved so the water stays closed, never overlapping a face on the same plane (the translucent
 *    water would blend twice there, a darker line): each end of a pulled-in face, looking at the cell next to it
 *    along that edge ({@link #rule}),
 *      air: as BTA;
 *      water whose face on that side is pulled in too: as BTA (the two faces continue each other);
 *      water whose face is not (or none): stretched by the gap, covering the step between both (behind the other
 *        face, if it has one, there is a 0.005 strip with two layers: preferred to an empty slit);
 *      a full see-through block: shortened by the gap, to meet the pulled-in face that turns the corner there;
 *      anything else: as BTA.
 *    For side faces that is their two ends ({@link #stretchEnd}), bottom ({@link #sideBottom}) and top
 *    ({@link #sideTop}); for the bottom face its four edges ({@link #bottomFace}). A side face that is not pulled in
 *    but stands on a pulled-up bottom face ends on it instead of hanging below it;
 *  - against an opaque block of ice material (permaice) the side and bottom faces are not drawn ({@link #hidden}),
 *    as against stone. The top face is always kept (BTA draws it under any block).
 * Water against air, lava and other fluids stay as in BTA. It still gives way very far away: the GPU's limit.
 *
 * Option "Water Edge Fix (restart)": off = 0.0001, everything as in BTA (FluidContact goes off too).
 * -Dbta.fluidGap=N sets the gap in blocks over the option (0.0001 = as in BTA; clamped to [0.0001, 0.25]).
 * Called on the meshing threads: reads the world snapshot only.
 */
public final class FluidInset {
  private FluidInset() { }
  public static final double GAP = read();
  private static final double BTA_SIDE = 1.0E-4;
  private static final float BTA_BOTTOM = 0.001f;
  static final boolean ON = GAP > BTA_SIDE;
  /** {@link #rule} results. */
  static final int KEEP = 0, EXTEND = 1, SHORTEN = 2;

  // side index of BlockModelFluid's loop: 0 = -z, 1 = +z, 2 = -x, 3 = +x
  private static int offsetX(int side) { return side == 2 ? -1 : side == 3 ? 1 : 0; }
  private static int offsetZ(int side) { return side == 0 ? -1 : side == 1 ? 1 : 0; }

  /** Offset into the water of side face {@code side}. */
  public static double side(WorldSource world, TilePosc pos, int side) {
    return pulledIn(world, pos.x(), pos.y(), pos.z(), offsetX(side), 0, offsetZ(side)) ? GAP : BTA_SIDE;
  }

  /** How far one end of side face {@code side} goes past the block edge ({@code dir} -1: low end, +1: high end of its
   *  width; along x for sides 0/1, along z for sides 2/3). BTA: 0.0001. */
  public static double stretchEnd(WorldSource world, TilePosc pos, int side, int dir) {
    int x = pos.x(), y = pos.y(), z = pos.z(), towardX = offsetX(side), towardZ = offsetZ(side);
    if (!pulledIn(world, x, y, z, towardX, 0, towardZ)) return BTA_SIDE;
    int edgeX = side < 2 ? dir : 0, edgeZ = side < 2 ? 0 : dir;
    switch (rule(world, x, y, z, towardX, 0, towardZ, edgeX, 0, edgeZ)) {
      case EXTEND: return GAP;
      case SHORTEN: return -GAP;
      default: return BTA_SIDE;
    }
  }

  /** y of the two bottom vertices of side face {@code side}. */
  public static double sideBottom(double y, WorldSource world, TilePosc pos, int side) {
    int x = pos.x(), py = pos.y(), z = pos.z(), towardX = offsetX(side), towardZ = offsetZ(side);
    // a face that is not pulled in (against air) still ends where the pulled-up bottom face is, not under it
    if (!pulledIn(world, x, py, z, towardX, 0, towardZ)) return pulledIn(world, x, py, z, 0, -1, 0) ? y + GAP : y;
    switch (rule(world, x, py, z, towardX, 0, towardZ, 0, -1, 0)) {
      case EXTEND: return y - GAP;
      case SHORTEN: return y + GAP; // meets the bottom face, pulled up over the block below
      default: return y;
    }
  }

  /** Height (above y) of the two top vertices of side face {@code side}: only continued past a full height into more
   *  water above whose face is not pulled in (never above an open surface or under a block). */
  public static double sideTop(double height, WorldSource world, TilePosc pos, int side) {
    int x = pos.x(), y = pos.y(), z = pos.z(), towardX = offsetX(side), towardZ = offsetZ(side);
    if (height < 0.999 || !pulledIn(world, x, y, z, towardX, 0, towardZ)) return height;
    return rule(world, x, y, z, towardX, 0, towardZ, 0, 1, 0) == EXTEND ? height + GAP : height;
  }

  /** Offset up into the water of the bottom face. */
  public static float bottom(WorldSource world, TilePosc pos) {
    return pulledIn(world, pos.x(), pos.y(), pos.z(), 0, -1, 0) ? (float) GAP : BTA_BOTTOM;
  }

  /** Highest a top corner may go: 1 - gap under a full block (see-through or not: the top face is drawn under any
   *  block, on its bottom face's plane when water lies over a neighbouring column), else no limit. */
  public static float topMax(WorldSource world, TilePosc pos, Block<?> fluid) {
    if (!ON) return Float.MAX_VALUE;
    try {
      if (world.getBlockMaterial(pos.x(), pos.y(), pos.z()) != Materials.WATER) return Float.MAX_VALUE;
      Block<?> block = world.getBlockType(new net.minecraft.core.world.pos.TilePos(pos.x(), pos.y() + 1, pos.z()));
      Material material = block == null ? null : block.getMaterial();
      return material != null && material != Materials.AIR && !material.isLiquid() && block.isCubeShaped() ? (float) (1.0 - GAP) : Float.MAX_VALUE;
    } catch (RuntimeException ignored) { return Float.MAX_VALUE; }
  }

  /** Start of BlockModelFluid.shouldSideBeRendered: no side or bottom face against an opaque block of ice material. */
  public static boolean hidden(WorldSource world, TilePosc neighbour, net.minecraft.core.util.helper.Side side, Block<?> fluid) {
    if (!ON || side == net.minecraft.core.util.helper.Side.TOP) return false;
    try {
      if (fluid == null || fluid.getMaterial() != Materials.WATER) return false;
      Block<?> block = world.getBlockType(neighbour);
      return block != null && block.getMaterial() == Materials.ICE && block.isSolidRender();
    } catch (RuntimeException ignored) { return false; }
  }

  /** Replaces RenderBlocks.renderBottomFace in BlockModelFluid.render: a pulled-up bottom face gets its four edges
   *  moved by {@link #rule} afterwards (vertex x / z are floats at offsets 0 / 8 in both vertex formats). */
  public static void bottomFace(net.minecraft.client.render.block.model.RenderBlocks renderBlocks, net.minecraft.client.render.tessellator.TessellatorGeneral tess,
      org.joml.primitives.AABBdc box, double x, double y, double z, net.minecraft.client.render.texture.stitcher.IconCoordinate icon,
      WorldSource world, TilePosc pos) {
    int px = pos.x(), py = pos.y(), pz = pos.z();
    if (!(tess instanceof net.minecraft.client.render.tessellator.TessellatorTerrainImpl) || !pulledIn(world, px, py, pz, 0, -1, 0)) {
      renderBlocks.renderBottomFace(tess, box, x, y, z, icon);
      return;
    }
    float xLo = delta(rule(world, px, py, pz, 0, -1, 0, -1, 0, 0)), xHi = delta(rule(world, px, py, pz, 0, -1, 0, 1, 0, 0));
    float zLo = delta(rule(world, px, py, pz, 0, -1, 0, 0, 0, -1)), zHi = delta(rule(world, px, py, pz, 0, -1, 0, 0, 0, 1));
    net.minecraft.client.render.tessellator.TessellatorTerrainImpl terrainTess = (net.minecraft.client.render.tessellator.TessellatorTerrainImpl) tess;
    int firstVertex = terrainTess.data.vertexCount, startPos = terrainTess.data.getBuffer().position();
    renderBlocks.renderBottomFace(tess, box, x, y, z, icon);
    if (xLo == 0 && xHi == 0 && zLo == 0 && zHi == 0) return;
    java.nio.ByteBuffer live = terrainTess.data.getBuffer();
    int endVertex = terrainTess.data.vertexCount, bytes = live.position() - startPos;
    if (endVertex <= firstVertex || bytes <= 0 || bytes % (endVertex - firstVertex) != 0) return;
    int stride = bytes / (endVertex - firstVertex);
    if (stride < 12) return;
    java.nio.ByteBuffer buf = live.duplicate().order(live.order());
    float centerX = 0, centerZ = 0;
    for (int vertex = firstVertex; vertex < endVertex; vertex++) { centerX += buf.getFloat(startPos + (vertex - firstVertex) * stride); centerZ += buf.getFloat(startPos + (vertex - firstVertex) * stride + 8); }
    centerX /= (endVertex - firstVertex); centerZ /= (endVertex - firstVertex);
    for (int vertex = firstVertex; vertex < endVertex; vertex++) {
      int vertexPos = startPos + (vertex - firstVertex) * stride;
      float vx = buf.getFloat(vertexPos), vz = buf.getFloat(vertexPos + 8);
      buf.putFloat(vertexPos, vx < centerX ? vx - xLo : vx + xHi);
      buf.putFloat(vertexPos + 8, vz < centerZ ? vz - zLo : vz + zHi);
    }
  }

  private static float delta(int result) { return result == EXTEND ? (float) GAP : result == SHORTEN ? (float) -GAP : 0f; }

  /** Is the face of the water at (x, y, z) facing (towardX, towardY, towardZ) pulled in? Water only, touching a full block. */
  static boolean pulledIn(WorldSource world, int x, int y, int z, int towardX, int towardY, int towardZ) {
    if (!ON) return false;
    try {
      return world.getBlockMaterial(x, y, z) == Materials.WATER && touches(world, x + towardX, y + towardY, z + towardZ);
    } catch (RuntimeException ignored) { return false; }
  }

  /**
   * Edge of a pulled-in face of the water at (x, y, z) facing (towardX, towardY, towardZ), at the side (edgeX, edgeY, edgeZ) of the face:
   * what to do, looking at the cell W next to it there (see the class comment).
   */
  static int rule(WorldSource world, int x, int y, int z, int towardX, int towardY, int towardZ, int edgeX, int edgeY, int edgeZ) {
    try {
      int nextX = x + edgeX, nextY = y + edgeY, nextZ = z + edgeZ;
      Material material = world.getBlockMaterial(nextX, nextY, nextZ);
      if (material == null || material == Materials.AIR) return KEEP;
      if (material == Materials.WATER) return touches(world, nextX + towardX, nextY + towardY, nextZ + towardZ) ? KEEP : EXTEND;
      if (material.isLiquid()) return KEEP;
      return touches(world, nextX, nextY, nextZ) ? SHORTEN : KEEP;
    } catch (RuntimeException ignored) { return KEEP; }
  }

  /** A full see-through block that water draws a face against: cube shaped, not air, fluid or opaque. */
  static boolean touches(WorldSource world, int x, int y, int z) {
    Block<?> block = world.getBlockType(new net.minecraft.core.world.pos.TilePos(x, y, z));
    if (block == null) return false;
    Material material = block.getMaterial();
    return material != null && material != Materials.AIR && !material.isLiquid() && block.isCubeShaped() && !block.isSolidRender();
  }

  private static double read() { return gap(System.getProperty("bta.fluidGap"), OptionsSafe.get("fluidEdges")); }

  /** Pure (the option-switch test): the gap for a -Dbta.fluidGap value (or null) and the menu option. */
  static double gap(String property, boolean option) {
    double value = option ? 0.005 : BTA_SIDE;
    try {
      if (property != null) value = Double.parseDouble(property.trim());
    } catch (RuntimeException ignored) { }
    if (!(value >= BTA_SIDE)) value = BTA_SIDE; // also NaN
    if (value > 0.25) value = 0.25;
    return value;
  }
}
