package net.betterthanadventure.btamobile.zalithfix;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import org.lwjgl.opengl.GL41;
import net.betterthanadventure.btamobile.glesfix.GlesMode;
/**
 * glMultiDrawArrays does not exist in core OpenGL ES, and MobileGlues 1.x exports it as an
 * empty stub. In those cases issue the sub-draws one by one; otherwise keep the native call.
 *
 * Loop path: ranges that sit next to each other in the VBO (first[i] + count[i] == first[j]) are
 * drawn with a single glDrawArrays. The vertices drawn are exactly the same; only the order of the
 * ranges can change (sorted by VBO position instead of BTA's HashSet order, which has no meaning
 * either). The merged plan is cached per IntBuffer and rebuilt only when its contents change
 * (RenderList refills its buffers only when the visible set changes). Option "Merge Draw Calls"
 * (instant); -Dbta.mergeDraws=false forces it off.
 */
public final class MultiDraw {
  private static final boolean MERGE_FORCED_OFF = "false".equalsIgnoreCase(System.getProperty("bta.mergeDraws"));
  /** Options > Video > BTA Movil > Merge Draw Calls (instant); -Dbta.mergeDraws=false forces it off. */
  static boolean merge() { return !MERGE_FORCED_OFF && net.betterthanadventure.btamobile.meshfix.OptionsSafe.get("mergeDraws"); }
  private static int mode = -1; // -1 unknown, 0 native, 1 loop
  /** Ranges requested and draw calls issued in the loop path (for tests and F3-style stats). */
  public static long rangesIn, drawsOut;

  private static boolean needsLoop() {
    if (mode < 0) {
      String version = null;
      try { version = GL41.glGetString(0x1F02); } catch (Throwable ignored) {}
      if (version == null && !GlesMode.enabled) return true; // unknown yet: loop is always correct, decide on a later call
      boolean loop = GlesMode.enabled || (version.contains("MobileGlues 1.") || version.startsWith("OpenGL ES"));
      mode = loop ? 1 : 0;
      System.out.println("[BTA-mobile-fix] GL_VERSION=" + version + " -> multidraw " + (loop ? "loop" : "native")
          + (loop ? ", contiguous ranges merged when \"Merge Draw Calls\" is on" : ""));
    }
    return mode == 1;
  }

  /** Cached merge plan for one RenderList buffer pair. Main (render) thread only. */
  static final class Plan {
    int[] first = new int[0], count = new int[0]; // input copy, to detect changes
    int inRangeCount = -1;
    int[] outFirst = new int[0], outCount = new int[0];
    int outRangeCount;
    long lastUse;
  }
  private static long calls;
  private static final IdentityHashMap<IntBuffer, Plan> PLANS = new IdentityHashMap<>();

  public static void glMultiDrawArrays(int drawMode, IntBuffer first, IntBuffer count) {
    int rangeCount = first.remaining(), firstPosition = first.position(), countPosition = count.position();
    // water contact faces are drawn apart whatever the driver (also where glMultiDrawArrays is native)
    if (net.betterthanadventure.btamobile.meshfix.FluidContact.active() && contactDraw(drawMode, first, count, rangeCount, firstPosition, countPosition)) { rangesIn += rangeCount; return; }
    if (!needsLoop()) { GL41.glMultiDrawArrays(drawMode, first, count); return; }
    rangesIn += rangeCount;
    if (!merge()) {
      for (int i = 0; i < rangeCount; i++) {
        int rangeVertexCount = count.get(countPosition + i);
        if (rangeVertexCount > 0) { GL41.glDrawArrays(drawMode, first.get(firstPosition + i), rangeVertexCount); drawsOut++; }
      }
      return;
    }
    Plan mergePlan = plan(first, count);
    for (int i = 0; i < mergePlan.outRangeCount; i++) GL41.glDrawArrays(drawMode, mergePlan.outFirst[i], mergePlan.outCount[i]);
    drawsOut += mergePlan.outRangeCount;
  }

  private static final int[] SIZES = new int[2];
  private static int[] inFirst = new int[0], inCount = new int[0], contactFirst = new int[0], contactCount = new int[0], restFirst = new int[0], restCount = new int[0],
      mergedFirst = new int[0], mergedCount = new int[0];

  /**
   * Translucent terrain with water contact faces (meshfix.FluidContact): in the contact walk, only the contact
   * prefixes of the ranges, in color (also in the depth pre-pass), blended as translucent terrain, without writing
   * depth and with polygon offset -1, -1 (always "drawn": nothing else is in that walk). Afterwards, only the rest of
   * each range (merged when "Merge Draw Calls" is on). Returns false (draw as usual) when no range has contact faces
   * outside the contact walk.
   */
  private static boolean contactDraw(int drawMode, IntBuffer first, IntBuffer count, int rangeCount, int firstPosition, int countPosition) {
    boolean walk = net.betterthanadventure.btamobile.meshfix.FluidContact.phase() == net.betterthanadventure.btamobile.meshfix.FluidContact.CONTACT;
    if (inFirst.length < rangeCount) { inFirst = new int[rangeCount]; inCount = new int[rangeCount]; contactFirst = new int[rangeCount]; contactCount = new int[rangeCount]; restFirst = new int[rangeCount]; restCount = new int[rangeCount]; mergedFirst = new int[rangeCount]; mergedCount = new int[rangeCount]; }
    for (int i = 0; i < rangeCount; i++) { inFirst[i] = first.get(firstPosition + i); inCount[i] = count.get(countPosition + i); }
    int[] sizes = SIZES;
    if (!contactRanges(inFirst, inCount, rangeCount, net.betterthanadventure.btamobile.meshfix.FluidContact::at, contactFirst, contactCount, restFirst, restCount, sizes)) return walk;
    if (walk || !net.betterthanadventure.btamobile.meshfix.FluidContact.contactDone()) { // (not done yet: only if the walk hook is missing; then contact first here)
      boolean depthPrePass = net.betterthanadventure.btamobile.meshfix.FluidContact.depthOnly();
      boolean depthMask = net.minecraft.client.render.renderer.GLRenderer.getDepthMask(); // BTA's cached state, no glGet; all restored after
      boolean offsetEnabled = net.minecraft.client.render.renderer.GLRenderer.isStateEnabled(net.minecraft.client.render.renderer.State.POLYGON_OFFSET_FILL);
      boolean blendEnabled = net.minecraft.client.render.renderer.GLRenderer.isStateEnabled(net.minecraft.client.render.renderer.State.BLEND); // the depth pre-pass need not blend; these faces must
      net.minecraft.client.render.renderer.BlendFactor srcFactor = net.minecraft.client.render.renderer.GLRenderer.getBlendSFactor(), dstFactor = net.minecraft.client.render.renderer.GLRenderer.getBlendDFactor();
      if (depthPrePass) net.minecraft.client.render.renderer.GLRenderer.setColorMask(true, true, true, true);
      if (!blendEnabled) net.minecraft.client.render.renderer.GLRenderer.enableState(net.minecraft.client.render.renderer.State.BLEND);
      net.minecraft.client.render.renderer.GLRenderer.setBlendFunc(net.minecraft.client.render.renderer.BlendFactor.SRC_ALPHA, net.minecraft.client.render.renderer.BlendFactor.ONE_MINUS_SRC_ALPHA);
      net.minecraft.client.render.renderer.GLRenderer.setDepthMask(false);
      net.minecraft.client.render.renderer.GLRenderer.setPolygonOffset(-1f, -1f);
      if (!offsetEnabled) net.minecraft.client.render.renderer.GLRenderer.enableState(net.minecraft.client.render.renderer.State.POLYGON_OFFSET_FILL);
      try {
        for (int i = 0; i < sizes[0]; i++) GL41.glDrawArrays(drawMode, contactFirst[i], contactCount[i]);
        drawsOut += sizes[0];
      } finally {
        if (!offsetEnabled) net.minecraft.client.render.renderer.GLRenderer.disableState(net.minecraft.client.render.renderer.State.POLYGON_OFFSET_FILL);
        net.minecraft.client.render.renderer.GLRenderer.setPolygonOffset(0f, 0f);
        net.minecraft.client.render.renderer.GLRenderer.setDepthMask(depthMask);
        if (srcFactor != null && dstFactor != null) net.minecraft.client.render.renderer.GLRenderer.setBlendFunc(srcFactor, dstFactor);
        if (!blendEnabled) net.minecraft.client.render.renderer.GLRenderer.disableState(net.minecraft.client.render.renderer.State.BLEND);
        if (depthPrePass) net.minecraft.client.render.renderer.GLRenderer.setColorMask(false, false, false, false);
      }
      if (walk) return true;
    }
    if (merge()) {
      int mergedRangeCount = merge(restFirst, restCount, sizes[1], mergedFirst, mergedCount);
      for (int i = 0; i < mergedRangeCount; i++) GL41.glDrawArrays(drawMode, mergedFirst[i], mergedCount[i]);
      drawsOut += mergedRangeCount;
    } else {
      for (int i = 0; i < sizes[1]; i++) GL41.glDrawArrays(drawMode, restFirst[i], restCount[i]);
      drawsOut += sizes[1];
    }
    return true;
  }

  /**
   * Splits ranges into contact prefixes (the first min(count, contact(first, count)) vertices of each range) and the
   * rest. Empty pieces are dropped. sizes[0] / sizes[1] = number of contact / rest ranges. Returns false (nothing
   * written) when no range has contact vertices.
   */
  public static boolean contactRanges(int[] first, int[] count, int rangeCount, java.util.function.IntBinaryOperator contact,
      int[] outContactFirst, int[] outContactCount, int[] outRestFirst, int[] outRestCount, int[] sizes) {
    boolean anyContact = false;
    for (int i = 0; i < rangeCount && !anyContact; i++) anyContact = count[i] > 0 && contact.applyAsInt(first[i], count[i]) > 0;
    if (!anyContact) return false;
    int contactRangeCount = 0, restRangeCount = 0;
    for (int i = 0; i < rangeCount; i++) {
      int start = first[i], length = count[i];
      if (length <= 0) continue;
      int contactVertices = Math.max(0, Math.min(length, contact.applyAsInt(start, length)));
      if (contactVertices > 0) { outContactFirst[contactRangeCount] = start; outContactCount[contactRangeCount] = contactVertices; contactRangeCount++; }
      if (length - contactVertices > 0) { outRestFirst[restRangeCount] = start + contactVertices; outRestCount[restRangeCount] = length - contactVertices; restRangeCount++; }
    }
    sizes[0] = contactRangeCount; sizes[1] = restRangeCount;
    return true;
  }

  /** Returns the (cached) merged plan for these buffers. Package-visible logic is in {@link #merge}. */
  static Plan plan(IntBuffer first, IntBuffer count) {
    int rangeCount = first.remaining(), firstPosition = first.position(), countPosition = count.position();
    if ((++calls & 4095) == 0) PLANS.values().removeIf(candidate -> calls - candidate.lastUse > 4096); // lists replaced or gone (world / distance change)
    Plan mergePlan = PLANS.get(first);
    if (mergePlan == null) {
      if (PLANS.size() > 1024) PLANS.clear(); // safety cap
      mergePlan = new Plan();
      PLANS.put(first, mergePlan);
    }
    mergePlan.lastUse = calls;
    boolean unchanged = mergePlan.inRangeCount == rangeCount;
    for (int i = 0; unchanged && i < rangeCount; i++)
      unchanged = mergePlan.first[i] == first.get(firstPosition + i) && mergePlan.count[i] == count.get(countPosition + i);
    if (unchanged) return mergePlan;
    if (mergePlan.first.length < rangeCount) { mergePlan.first = new int[rangeCount]; mergePlan.count = new int[rangeCount]; mergePlan.outFirst = new int[rangeCount]; mergePlan.outCount = new int[rangeCount]; }
    for (int i = 0; i < rangeCount; i++) { mergePlan.first[i] = first.get(firstPosition + i); mergePlan.count[i] = count.get(countPosition + i); }
    mergePlan.inRangeCount = rangeCount;
    mergePlan.outRangeCount = merge(mergePlan.first, mergePlan.count, rangeCount, mergePlan.outFirst, mergePlan.outCount);
    return mergePlan;
  }

  private static long[] KEYS = new long[256]; // reused: render thread (and single-threaded tests) only

  /**
   * Sorts the first rangeCount ranges by start and joins ranges that touch exactly. Empty ranges are dropped.
   * Overlapping ranges (never produced by BTA's VertexBuffer) are kept separate, so nothing is ever
   * drawn twice or skipped. Returns the number of output ranges.
   */
  public static int merge(int[] first, int[] count, int rangeCount, int[] outFirst, int[] outCount) {
    long[] keys = KEYS;
    if (keys.length < rangeCount) KEYS = keys = new long[Math.max(rangeCount, keys.length * 2)];
    int keyCount = 0;
    for (int i = 0; i < rangeCount; i++) if (count[i] > 0) keys[keyCount++] = ((long) first[i] << 32) | (i & 0xFFFFFFFFL);
    Arrays.sort(keys, 0, keyCount);
    int outSize = 0;
    for (int j = 0; j < keyCount; j++) {
      int i = (int) keys[j];
      int start = first[i], length = count[i];
      if (outSize > 0 && outFirst[outSize - 1] + outCount[outSize - 1] == start && (long) outCount[outSize - 1] + length <= Integer.MAX_VALUE) {
        outCount[outSize - 1] += length;
      } else {
        outFirst[outSize] = start; outCount[outSize] = length; outSize++;
      }
    }
    return outSize;
  }
}
