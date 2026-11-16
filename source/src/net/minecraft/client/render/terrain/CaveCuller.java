package net.minecraft.client.render.terrain;

import net.betterthanadventure.btamobile.meshfix.MobileOptions;
import net.betterthanadventure.btamobile.meshfix.VisGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.RenderGlobal;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.client.render.culling.CameraFrustum;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePos;

/**
 * Cave (section occlusion) culling, replacing RenderGlobal.cull when enabled.
 *
 * Each section stores which of its faces are connected through non-opaque blocks (VisGraph, computed
 * on the mesher thread). A breadth-first walk starts at the camera's section and only steps
 * outward (never back towards the camera) through connected faces; sections it cannot reach are
 * hidden. Design follows Sodium 0.6's OcclusionCuller:
 *  - incoming directions of all parents are merged (FIFO BFS = layer by layer);
 *  - "angle mask": no straight pass through a section along an axis that is not the dominant view axis;
 *  - the 26 sections around the camera are always kept;
 *  - sections without data yet count as fully connected (never hides something wrongly).
 * The walk does not use the view frustum, so its result only depends on the camera block and the
 * section data: it is recomputed when those change, and turning the camera never causes popping.
 * The frustum test is done every frame on top of it.
 */
public final class CaveCuller {
  private static final double ANGLE_MARGIN = 2.0; // blocks; walk is reused while the camera stays in one block
  private static boolean dirty = true;
  private static int token = 1;
  private static boolean active;      // last frame used occlusion results
  private static boolean allReached;  // last walk could not run (camera in block / outside world): nothing hidden
  private static ChunkRenderer[] lastArray;
  private static int lastCameraBlockX = Integer.MIN_VALUE, lastCameraBlockY, lastCameraBlockZ, framesSinceWalk;
  private static int[] queue = new int[0];
  private static byte[] incoming = new byte[0];
  private static int gridSizeX, gridSizeY, gridSizeZ;
  private static ChunkRenderer[] sectionGrid;
  private static final TilePos cameraBlockPos = new TilePos(0, 0, 0);
  private static boolean failed;          // an unexpected error happened: stay on BTA's own culling
  private static ICamera lastCamera;      // camera the last walk was made for (entity hooks check it)

  public static void markDirty() { dirty = true; }

  /** Replaces RenderGlobal.cull(frustum, pt) in WorldRenderer. */
  public static void cull(RenderGlobal renderGlobal, CameraFrustum frustum, float partialTicks) {
    net.betterthanadventure.btamobile.meshfix.Profiler.enter(net.betterthanadventure.btamobile.meshfix.Profiler.CAVE_CULL);
    try { cullSections(renderGlobal, frustum, partialTicks); }
    finally { net.betterthanadventure.btamobile.meshfix.Profiler.exit(net.betterthanadventure.btamobile.meshfix.Profiler.CAVE_CULL); }
  }

  private static void cullSections(RenderGlobal renderGlobal, CameraFrustum frustum, float partialTicks) {
    ChunkRenderer[] sections = renderGlobal.chunkRenderers;
    if (failed && sections != lastArray) failed = false; // new world / render distance: give it another chance
    if (failed || !net.betterthanadventure.btamobile.meshfix.Mesher.enabled() || !MobileOptions.caveCulling() || sections == null || sections.length == 0 || !(sections[0] instanceof AsyncChunkRenderer)) {
      active = false;
      if (!failed) { sectionGrid = null; lastArray = null; }
      lastCamera = null;
      renderGlobal.cull(frustum, partialTicks);
      return;
    }
    Minecraft minecraft = Minecraft.getMinecraft();
    ICamera camera = minecraft.activeCamera;
    World world = minecraft.currentWorld;
    if (camera == null || world == null) { active = false; sectionGrid = null; lastArray = null; renderGlobal.cull(frustum, partialTicks); return; }
    try {
      double cameraX = camera.getX(partialTicks), cameraY = camera.getY(partialTicks), cameraZ = camera.getZ(partialTicks);
      int blockX = floor(cameraX), blockY = floor(cameraY), blockZ = floor(cameraZ);
      framesSinceWalk++;
      if (dirty || sections != lastArray || blockX != lastCameraBlockX || blockY != lastCameraBlockY || blockZ != lastCameraBlockZ || framesSinceWalk >= 30
          || renderGlobal.xChunks != gridSizeX || renderGlobal.yChunks != gridSizeY || renderGlobal.zChunks != gridSizeZ) {
        dirty = false;
        lastArray = sections; lastCameraBlockX = blockX; lastCameraBlockY = blockY; lastCameraBlockZ = blockZ; framesSinceWalk = 0;
        boolean cameraInOpaque = false;
        if (blockY >= 0 && blockY < world.getHeightBlocks()) {
          cameraBlockPos.x = blockX; cameraBlockPos.y = blockY; cameraBlockPos.z = blockZ;
          try { cameraInOpaque = world.getBlockType(cameraBlockPos).isSolidRender(); } catch (Throwable ignored) { }
        }
        walk(renderGlobal.chunkRenderers, renderGlobal.xChunks, renderGlobal.yChunks, renderGlobal.zChunks, cameraInOpaque, cameraX, cameraY, cameraZ, blockX, blockY, blockZ);
      }
      int occluded = 0;
      for (ChunkRenderer section : sections) {
        AsyncChunkRenderer asyncSection = (AsyncChunkRenderer) section;
        if (asyncSection.isEmpty()) continue;                    // nothing to draw (BTA skips these too)
        if (!allReached && asyncSection.reachedToken != token) { // behind terrain: no frustum test needed
          occluded++;
          asyncSection.setCullVisible(false);
          continue;
        }
        asyncSection.setCullVisible(asyncSection.inFrustum(frustum, partialTicks));
      }
      renderGlobal.occludedChunks = occluded;
      lastCamera = camera;
      active = true;
    } catch (Throwable error) {
      // never break rendering: fall back to BTA's frustum culling
      active = false;
      failed = true;
      System.out.println("[BTA-movil] cave culling disabled after an error:");
      error.printStackTrace(System.out);
      lastArray = sections; sectionGrid = null; lastCamera = null;
      try {
        for (ChunkRenderer section : sections)
          if (section instanceof AsyncChunkRenderer) { AsyncChunkRenderer asyncSection = (AsyncChunkRenderer) section; asyncSection.setCullVisible(asyncSection.inFrustum(frustum, partialTicks)); }
      } catch (Throwable fallbackError) {
        renderGlobal.cull(frustum, partialTicks);
      }
    }
  }

  private static int floor(double value) { int truncated = (int) value; return value < truncated ? truncated - 1 : truncated; }

  private static int indexOf(int sectionX, int sectionY, int sectionZ) {
    if (sectionY < 0 || sectionY >= gridSizeY) return -1;
    int x = Math.floorMod(sectionX, gridSizeX), z = Math.floorMod(sectionZ, gridSizeZ);
    int index = (z * gridSizeY + sectionY) * gridSizeX + x;
    if (index < 0 || index >= sectionGrid.length) return -1;
    AsyncChunkRenderer section = (AsyncChunkRenderer) sectionGrid[index];
    if (section.sectionX() != sectionX || section.sectionY() != sectionY || section.sectionZ() != sectionZ) return -1; // not in the loaded grid
    return index;
  }

  static void walk(ChunkRenderer[] renderers, int xChunks, int yChunks, int zChunks, boolean cameraInOpaque,
                   double cameraX, double cameraY, double cameraZ, int blockX, int blockY, int blockZ) {
    sectionGrid = renderers;
    gridSizeX = xChunks; gridSizeY = yChunks; gridSizeZ = zChunks;
    int sectionCount = sectionGrid.length;
    if (queue.length < sectionCount) { queue = new int[sectionCount]; incoming = new byte[sectionCount]; }
    token++;
    if (token == 0) token = 1;
    allReached = false;

    int originX = blockX >> 4, originY = blockY >> 4, originZ = blockZ >> 4;
    int origin = indexOf(originX, originY, originZ);
    if (origin < 0 || cameraInOpaque) { allReached = true; return; } // above/below world, inside a block, grid moving

    int head = 0, tail = 0;
    ((AsyncChunkRenderer) sectionGrid[origin]).reachedToken = token;
    incoming[origin] = 0x3F;
    queue[tail++] = origin;
    while (head < tail) {
      int index = queue[head++];
      AsyncChunkRenderer section = (AsyncChunkRenderer) sectionGrid[index];
      int sx = section.sectionX(), sy = section.sectionY(), sz = section.sectionZ();
      int exits;
      if (index == origin) {
        exits = 0x3F;
      } else {
        long visibility = angleMask(section.visData, cameraX, cameraY, cameraZ, sx, sy, sz);
        exits = VisGraph.connections(visibility, incoming[index]);
      }
      // outward only (relative to the camera section)
      if (sx < originX) exits &= ~(1 << VisGraph.EAST); else if (sx > originX) exits &= ~(1 << VisGraph.WEST);
      if (sy < originY) exits &= ~(1 << VisGraph.UP); else if (sy > originY) exits &= ~(1 << VisGraph.DOWN);
      if (sz < originZ) exits &= ~(1 << VisGraph.SOUTH); else if (sz > originZ) exits &= ~(1 << VisGraph.NORTH);
      for (int direction = 0; direction < 6; direction++) {
        if ((exits & (1 << direction)) == 0) continue;
        int nx = sx, ny = sy, nz = sz;
        switch (direction) {
          case VisGraph.DOWN: ny--; break;
          case VisGraph.UP: ny++; break;
          case VisGraph.NORTH: nz--; break;
          case VisGraph.SOUTH: nz++; break;
          case VisGraph.WEST: nx--; break;
          default: nx++; break;
        }
        int neighborIndex = indexOf(nx, ny, nz);
        if (neighborIndex < 0) continue;
        AsyncChunkRenderer neighbor = (AsyncChunkRenderer) sectionGrid[neighborIndex];
        if (neighbor.reachedToken != token) {
          neighbor.reachedToken = token;
          incoming[neighborIndex] = 0;
          queue[tail++] = neighborIndex;
        }
        incoming[neighborIndex] |= (byte) (1 << (direction ^ 1));
      }
    }
    // the 26 sections around the camera are always drawn (blocks/models sticking out, camera near a face)
    for (int dx = -1; dx <= 1; dx++)
      for (int dy = -1; dy <= 1; dy++)
        for (int dz = -1; dz <= 1; dz++) {
          int neighborIndex = indexOf(originX + dx, originY + dy, originZ + dz);
          if (neighborIndex >= 0) ((AsyncChunkRenderer) sectionGrid[neighborIndex]).reachedToken = token;
        }
  }

  /** Sodium's angle rule with a safety margin: a straight pass along an axis needs that axis to be (about) dominant. */
  private static long angleMask(long visibility, double cameraX, double cameraY, double cameraZ, int sx, int sy, int sz) {
    double dx = Math.abs(cameraX - (sx * 16 + 8)), dy = Math.abs(cameraY - (sy * 16 + 8)), dz = Math.abs(cameraZ - (sz * 16 + 8));
    if (dx > dy + ANGLE_MARGIN || dz > dy + ANGLE_MARGIN) visibility &= ~pair(VisGraph.DOWN, VisGraph.UP);
    if (dx > dz + ANGLE_MARGIN || dy > dz + ANGLE_MARGIN) visibility &= ~pair(VisGraph.NORTH, VisGraph.SOUTH);
    if (dy > dx + ANGLE_MARGIN || dz > dx + ANGLE_MARGIN) visibility &= ~pair(VisGraph.WEST, VisGraph.EAST);
    return visibility;
  }

  private static long pair(int faceA, int faceB) { return (1L << (faceA * 6 + faceB)) | (1L << (faceB * 6 + faceA)); }

  // ---------------------------------------------------------------- entity culling

  /** True if the last walk belongs to the camera currently rendering (entity hooks only cull then). */
  public static boolean walkedFor(ICamera camera) { return active && camera != null && camera == lastCamera; }

  /** True if any section overlapped by the box (grown by 0.5) was reached by the last walk. */
  public static boolean boxReached(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    if (!active || allReached || sectionGrid == null) return true;
    if (maxX - minX > 32 || maxY - minY > 32 || maxZ - minZ > 32) return true; // huge things: always draw
    int x0 = floor(minX - 0.5) >> 4, x1 = floor(maxX + 0.5) >> 4;
    int y0 = floor(minY - 0.5) >> 4, y1 = floor(maxY + 0.5) >> 4;
    int z0 = floor(minZ - 0.5) >> 4, z1 = floor(maxZ + 0.5) >> 4;
    for (int sx = x0; sx <= x1; sx++)
      for (int sy = y0; sy <= y1; sy++)
        for (int sz = z0; sz <= z1; sz++) {
          if (sy < 0 || sy >= gridSizeY) return true; // outside world height
          int index = indexOf(sx, sy, sz);
          if (index < 0) return true;                 // unknown area: draw
          if (((AsyncChunkRenderer) sectionGrid[index]).reachedToken == token) return true;
        }
    return false;
  }
}
