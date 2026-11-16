package net.betterthanadventure.btamobile.zalithfix;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL41;

/**
 * Terrain upload through glMapBufferRange(WRITE | INVALIDATE_RANGE | UNSYNCHRONIZED) instead of
 * glBufferSubData, which on mobile drivers can stall waiting for the GPU.
 *
 * UNSYNCHRONIZED tells the driver not to wait for draws still in flight, so the caller must not write
 * where the GPU may still read (OpenGL wiki, "Buffer Object Streaming": unsynchronized mapping must be
 * paired with fences). BTA's terrain allocator (VertexBuffer, first fit) hands the space of a freed
 * section to the next upload, often in the same frame, while the previous frames still draw from it:
 * for a frame the GPU could read half-written vertices (a block flashing missing or see-through).
 *
 * So the space is only reused once the GPU is done with it (deferred deletion with fences):
 *  - VertexBuffer.removeEntry does not give the space back right away ({@link #deferFree}); it is
 *    queued, and once per frame ({@link #frame}, before any terrain upload) one glFenceSync covers
 *    everything queued since the last one. When that fence completes (checked without waiting:
 *    glClientWaitSync timeout 0), every frame that could draw that space is finished and the Entry is
 *    removed from VertexBuffer.entries, the list BTA's allocator reads;
 *  - glBufferData (new or grown buffer, filled by glCopyBufferSubData on the GPU) marks the whole
 *    buffer pending until its fence; an upload there uses BTA's original glBufferSubData;
 *  - every other upload stays unsynchronized.
 * (A first version gave the space back at once and used glBufferSubData for uploads landing on it:
 * on Mali that stalled 100-300 ms each time.)
 *
 * Failures: if fences are not available the mapped path is turned off (BTA's glBufferSubData for
 * everything). A mapping error (null / GL error) falls back for that upload; after 3 the mapped path
 * is turned off for the session.
 */
public final class BufferUpload {
  private static final int MAP_WRITE = 0x2, MAP_INVALIDATE_RANGE = 0x4, MAP_UNSYNCHRONIZED = 0x20;
  private static final int ARRAY = 0x8892, COPY_READ = 0x8F36, COPY_WRITE = 0x8F37;
  private static final int SYNC_GPU_COMMANDS_COMPLETE = 0x9117, ALREADY_SIGNALED = 0x911A, CONDITION_SATISFIED = 0x911C, WAIT_FAILED = 0x911D;
  private static final boolean FORCED_OFF = "false".equalsIgnoreCase(System.getProperty("bta.mappedUpload"));
  private static boolean disabled = FORCED_OFF;
  private static int failures;
  /** Uploads done unsynchronized / with glBufferSubData because of a pending range (stats, tests). */
  public static long mapped, fenced;

  // ---------------------------------------------------------------- bound buffers (VertexBuffer only)
  private static int boundArray, boundCopyRead, boundCopyWrite;

  public static void glBindBuffer(int target, int buffer) {
    GL41.glBindBuffer(target, buffer);
    if (target == ARRAY) boundArray = buffer; else if (target == COPY_READ) boundCopyRead = buffer; else if (target == COPY_WRITE) boundCopyWrite = buffer;
  }

  private static int bound(int target) {
    return target == ARRAY ? boundArray : target == COPY_READ ? boundCopyRead : target == COPY_WRITE ? boundCopyWrite : -1;
  }

  /** New storage (constructor, expandVBO): the GPU fills it later with glCopyBufferSubData, so the whole buffer is pending. */
  public static void glBufferData(int target, long size, int usage) {
    GL41.glBufferData(target, size, usage);
    int boundBuffer = bound(target);
    if (boundBuffer > 0 && !disabled) Ranges.OPEN.add(boundBuffer, 0, Long.MAX_VALUE);
  }

  // ---------------------------------------------------------------- deferred frees and fences

  /** One batch per fence: buffers whose whole storage is pending (new / grown) and the VBO space
   *  waiting to be given back to BTA's allocator. Main (GL) thread only. */
  static final class Ranges {
    static Ranges OPEN = new Ranges();      // since the last fence
    static final java.util.ArrayDeque<Ranges> FENCED = new java.util.ArrayDeque<>();
    int[] vbo = new int[4];
    long[] from = new long[4], to = new long[4];
    int rangeCount;
    final java.util.ArrayList<Runnable> release = new java.util.ArrayList<>();
    long sync, closedAt;

    void add(int buffer, long rangeFrom, long rangeTo) {
      if (rangeCount == vbo.length) { vbo = java.util.Arrays.copyOf(vbo, rangeCount * 2); from = java.util.Arrays.copyOf(from, rangeCount * 2); to = java.util.Arrays.copyOf(to, rangeCount * 2); }
      vbo[rangeCount] = buffer; from[rangeCount] = rangeFrom; to[rangeCount] = rangeTo; rangeCount++;
    }

    boolean overlaps(int buffer, long rangeFrom, long rangeTo) {
      for (int i = 0; i < rangeCount; i++) if (vbo[i] == buffer && from[i] < rangeTo && rangeFrom < to[i]) return true;
      return false;
    }

    boolean empty() { return rangeCount == 0 && release.isEmpty(); }

    static boolean pending(int buffer, long rangeFrom, long rangeTo) {
      if (OPEN.overlaps(buffer, rangeFrom, rangeTo)) return true;
      for (Ranges batch : FENCED) if (batch.overlaps(buffer, rangeFrom, rangeTo)) return true;
      return false;
    }

    /** Closes the open batch under this fence (tests pass any non-zero id). */
    static void close(long sync) {
      Ranges batch = OPEN;
      batch.sync = sync;
      batch.closedAt = System.nanoTime();
      FENCED.addLast(batch);
      OPEN = new Ranges();
    }

    /** Releases, oldest first, every batch whose fence is done; stops at the first one still pending
     *  (fences complete in order). Returns the number of space releases done. */
    static int purge(java.util.function.LongPredicate done, java.util.function.LongConsumer delete) {
      int releaseCount = 0;
      while (!FENCED.isEmpty()) {
        Ranges batch = FENCED.peekFirst();
        if (!done.test(batch.sync)) break;
        FENCED.removeFirst();
        if (delete != null) delete.accept(batch.sync);
        for (Runnable action : batch.release) { try { action.run(); } catch (Throwable ignored) { } releaseCount++; }
      }
      return releaseCount;
    }

    /** Gives everything back now (fences gone: mapping turned off, or world change). */
    static void releaseAll() {
      for (Ranges batch : FENCED) for (Runnable action : batch.release) { try { action.run(); } catch (Throwable ignored) { } }
      for (Runnable action : OPEN.release) { try { action.run(); } catch (Throwable ignored) { } }
      FENCED.clear();
      OPEN = new Ranges();
    }
  }

  /**
   * Replaces the start of VertexBuffer.removeEntry: the space of a removed section is NOT given back
   * to BTA's allocator yet (its Entry stays in VertexBuffer.entries, which only the allocator reads;
   * RenderList has already stopped drawing it). It is given back once a fence placed after the last
   * frame that could draw it has completed. So a new upload never lands where the GPU may still read,
   * and every upload can stay unsynchronized (deferred deletion, as in Vulkan engines / the OpenGL
   * wiki's fenced streaming). Returns true when deferred (the original removal is skipped).
   */
  public static boolean deferFree(net.minecraft.client.render.terrain.VertexBuffer vertexBuffer, net.minecraft.client.render.terrain.VertexBuffer.Entry<?> entry) {
    net.betterthanadventure.btamobile.meshfix.FluidContact.removed(entry); // its contact faces, if any, are no longer drawn
    if (entry == null || vertexBuffer == null || disabled) return false;
    Ranges.OPEN.release.add(() -> vertexBuffer.entries.remove(entry));
    return true;
  }

  /** Closes the open batch with a fence and gives back the space of batches whose fence completed. */
  private static void fenceAndPurge() {
    if (!Ranges.OPEN.empty()) {
      long sync = GL41.glFenceSync(SYNC_GPU_COMMANDS_COMPLETE, 0);
      if (sync == 0L) throw new IllegalStateException("glFenceSync returned 0");
      Ranges.close(sync);
    }
    Ranges.purge(sync -> {
      int status = GL41.glClientWaitSync(sync, 0, 0L);
      if (status == WAIT_FAILED) { drainErrors("upload fence wait failed"); throw new IllegalStateException("glClientWaitSync failed"); } // never release on error
      return status == ALREADY_SIGNALED || status == CONDITION_SATISFIED;
    }, GL41::glDeleteSync);
    // a fence still pending 3 s after it was placed (checked once frames run again) never completes:
    // the VBO space behind it would stay taken, so give up on the mapped path instead
    Ranges oldest = Ranges.FENCED.peekFirst();
    if (oldest != null && System.nanoTime() - oldest.closedAt > 3_000_000_000L && Ranges.FENCED.size() > 8)
      throw new IllegalStateException("fences do not complete (" + Ranges.FENCED.size() + " pending)");
  }

  /** Once per frame, before BTA's terrain updates (AsyncChunkRenderer.drainCompleted): fences what
   *  the last frame freed and returns completed space to the allocator before anything is allocated. */
  public static void frame() {
    if (releasePending) { releasePending = false; clear(); } // mapping was turned off during an upload: give the space back here
    if (disabled) return;
    try { fenceAndPurge(); } catch (Throwable error) { disable("fences not available: " + error); }
  }

  /** Set when the mapped path is turned off; the deferred space is given back at the next frame(),
   *  never in the middle of VertexBuffer.insertAtIndex (its list index was computed before the upload). */
  private static boolean releasePending;

  /** Mapping turned off: give back all deferred space and forget pending buffers. */
  private static void clear() {
    try { for (Ranges batch : Ranges.FENCED) GL41.glDeleteSync(batch.sync); } catch (Throwable ignored) { }
    Ranges.releaseAll();
  }

  private static void disable(String why) {
    disabled = true;
    releasePending = true; // from then on every upload is glBufferSubData, which the driver orders itself
    System.out.println("[BTA-mobile-fix] mapped upload disabled: " + why + " (using glBufferSubData)");
  }

  private static void drainErrors(String where) {
    try { for (int i = 0; i < 8; i++) { int errorCode = GL41.glGetError(); if (errorCode == 0) break; net.betterthanadventure.btamobile.glesfix.GlErrors.record(errorCode, where); } } catch (Throwable ignored) { }
  }

  // ---------------------------------------------------------------- upload

  public static void glBufferSubData(int target, long offset, ByteBuffer data) {
    if (!net.betterthanadventure.btamobile.meshfix.Profiler.ON) { upload(target, offset, data); return; }
    int size = data.remaining();
    long startNanos = System.nanoTime();
    int path = upload(target, offset, data);
    net.betterthanadventure.btamobile.meshfix.Profiler.upload(path, System.nanoTime() - startNanos, size);
  }

  /** Sections whose VBO space is waiting for its fence, for the diagnostic summary. */
  public static int pendingRanges() {
    int count = Ranges.OPEN.release.size();
    for (Ranges batch : Ranges.FENCED) count += batch.release.size();
    return count;
  }

  /** Returns 0 = mapped (unsynchronized), 1 = glBufferSubData because of a pending range, 2 = glBufferSubData otherwise. */
  private static int upload(int target, long offset, ByteBuffer data) {
    int size = data.remaining();
    int path = 2;
    if (!disabled && size > 0) {
      boolean safe;
      try {
        // Only checks: space is given back in frame(), never here (this runs inside VertexBuffer.insertAtIndex,
        // between choosing the list index and inserting the new Entry)
        int boundBuffer = bound(target);
        safe = boundBuffer > 0 && !Ranges.pending(boundBuffer, offset, offset + size);
      } catch (Throwable error) {
        disable("fences not available: " + error);
        safe = false;
      }
      if (!safe) { fenced++; path = 1; }
      else {
        boolean isMapped = false;
        try {
          ByteBuffer mapping = GL41.glMapBufferRange(target, offset, size, MAP_WRITE | MAP_INVALIDATE_RANGE | MAP_UNSYNCHRONIZED);
          if (mapping != null) {
            isMapped = true;
            mapping.put(data.duplicate());
            isMapped = false;
            if (GL41.glUnmapBuffer(target)) { mapped++; return 0; }
            // unmap failed: contents undefined, upload again below (rare)
          } else {
            drainErrors("read when a mapped upload failed (may include earlier errors)"); // the failed map left an error flag: do not let BTA's next glGetError report it
            if (++failures >= 3) disable("glMapBufferRange returned null 3 times");
          }
        } catch (Throwable error) {
          if (isMapped) { try { GL41.glUnmapBuffer(target); } catch (Throwable ignored) { } }
          drainErrors("read when a mapped upload failed (may include earlier errors)");
          if (++failures >= 3) disable(error.toString());
        }
      }
    }
    GL41.glBufferSubData(target, offset, data);
    return path;
  }
}
