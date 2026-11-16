package net.betterthanadventure.btamobile.meshfix;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Optional memory report (-Dbta.memReport=true, off by default): two minutes after the game starts
 * drawing, and then every ten minutes, prints the 40 classes that take the most heap to the log.
 * It is the measurement step before deduplicating anything (FerriteCore style): only what this
 * shows as large and repeated is worth sharing. The histogram forces a full GC, so expect a hitch
 * when it runs; never leave it on for normal play.
 */
public final class MemReport {
  public static final boolean ON = "true".equalsIgnoreCase(System.getProperty("bta.memReport"));
  private static final long FIRST_NS = 120_000_000_000L, EVERY_NS = 600_000_000_000L;
  private static long start, next;
  private static volatile boolean running;

  /** Main thread, once per frame (from Profiler.enter(FRAME)); returns immediately when off. */
  public static void tick() {
    long now = System.nanoTime();
    if (start == 0) { start = now; next = now + FIRST_NS; return; }
    if (now < next || running) return;
    next = now + EVERY_NS;
    running = true;
    Thread t = new Thread(MemReport::report, "BTA-MemReport");
    t.setDaemon(true);
    t.start();
  }

  static void report() {
    try {
      MBeanServer server = ManagementFactory.getPlatformMBeanServer();
      ObjectName diagnostics = new ObjectName("com.sun.management:type=DiagnosticCommand");
      String histogram = (String) server.invoke(diagnostics, "gcClassHistogram", new Object[]{new String[0]}, new String[]{String[].class.getName()});
      String[] lines = histogram.split("\n");
      StringBuilder out = new StringBuilder("[BTA-mem] heap histogram (top 40):\n");
      for (int i = 0; i < Math.min(lines.length, 43); i++) out.append(lines[i]).append('\n');
      if (lines.length > 0) out.append(lines[lines.length - 1]).append('\n'); // totals
      Runtime runtime = Runtime.getRuntime();
      out.append("[BTA-mem] used ").append((runtime.totalMemory() - runtime.freeMemory()) >> 20).append(" MB of ").append(runtime.maxMemory() >> 20)
         .append(" MB; pooled mesh buffers ").append(BytePool.pooledBytes() >> 10).append(" KB");
      System.out.println(out);
    } catch (Throwable t) {
      System.out.println("[BTA-mem] histogram not available on this Java runtime: " + t);
    } finally {
      running = false;
    }
  }
}
