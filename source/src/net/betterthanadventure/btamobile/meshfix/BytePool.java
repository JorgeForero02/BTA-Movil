package net.betterthanadventure.btamobile.meshfix;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reuses the byte arrays that carry finished chunk geometry from the mesher workers to the main
 * thread, so meshing does not allocate two new arrays per chunk (less GC on phones).
 * Arrays come in power-of-two sizes from 4 KB; at most {@link #MAX_POOLED} bytes are kept, the
 * rest is left to the GC. Thread-safe. Disable with -Dbta.bytePool=false.
 */
public final class BytePool {
  public static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("bta.bytePool"));
  public static final long MAX_POOLED = Math.max(0, Integer.getInteger("bta.bytePool.mb", 8)) * 1024L * 1024L;
  private static final int MIN_SHIFT = 12, MAX_SHIFT = 26; // 4 KB .. 64 MB
  @SuppressWarnings("unchecked")
  private static final ConcurrentLinkedQueue<byte[]>[] BUCKETS = new ConcurrentLinkedQueue[MAX_SHIFT + 1];
  static { for (int shift = MIN_SHIFT; shift <= MAX_SHIFT; shift++) BUCKETS[shift] = new ConcurrentLinkedQueue<>(); }
  private static final AtomicLong pooled = new AtomicLong();
  /** For tests and stats: arrays handed out from the pool vs newly allocated. */
  public static final AtomicLong reused = new AtomicLong(), allocated = new AtomicLong();

  static int shiftFor(int length) {
    int shift = 32 - Integer.numberOfLeadingZeros(Math.max(1, length) - 1); // ceil(log2(length))
    return Math.max(MIN_SHIFT, shift);
  }

  /** An array of at least {@code length} bytes. Callers must track the real length themselves. */
  public static byte[] take(int length) {
    if (length < 0) throw new IllegalArgumentException("length < 0");
    int shift = shiftFor(length);
    if (!ENABLED || shift > MAX_SHIFT) { allocated.incrementAndGet(); return new byte[length]; }
    byte[] array = BUCKETS[shift].poll();
    if (array != null) { pooled.addAndGet(-array.length); reused.incrementAndGet(); return array; }
    allocated.incrementAndGet();
    return new byte[1 << shift];
  }

  /** Returns an array obtained from {@link #take}. Never use it again afterwards. Null is ignored. */
  public static void give(byte[] array) {
    if (array == null || !ENABLED) return;
    int length = array.length;
    if (length < (1 << MIN_SHIFT) || Integer.bitCount(length) != 1) return; // not one of ours
    int shift = Integer.numberOfTrailingZeros(length);
    if (shift > MAX_SHIFT) return;
    if (pooled.addAndGet(length) > MAX_POOLED) { pooled.addAndGet(-length); return; } // pool full: let the GC have it
    BUCKETS[shift].offer(array);
  }

  /** Drops everything (world unload). */
  public static void clear() {
    for (int shift = MIN_SHIFT; shift <= MAX_SHIFT; shift++) {
      byte[] array;
      while ((array = BUCKETS[shift].poll()) != null) pooled.addAndGet(-array.length);
    }
  }

  public static long pooledBytes() { return pooled.get(); }
}
