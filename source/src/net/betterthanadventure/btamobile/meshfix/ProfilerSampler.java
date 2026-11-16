package net.betterthanadventure.btamobile.meshfix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Stack sampling of one Profiler probe, to see what inside it costs (diagnostics only, off by default).
 * -Dbta.prof=true -Dbta.prof.sample=decorate (any Profiler probe name) -Dbta.prof.sampleMs=2 (1..50).
 * While the main thread is inside the probed method, the "BTA-sampler" thread reads its stack every N ms
 * and counts, per Class.method above the probed frame, how many samples it appears in ("inclusive") and
 * which game frame is innermost ("self"). Every 10 s it prints "[BTA-sample]" lines.
 * The probed method is found by name in each sample, never by stack position: stacks read on the own
 * thread and from another thread may differ in hidden frames. A lost exit (an exception thrown by a callee
 * skips the patched exit) heals itself: after 50 ms of discarded samples the target is dropped.
 */
public final class ProfilerSampler {
  public static final int TARGET = resolve(System.getProperty("bta.prof.sample"));
  public static final boolean ON = Profiler.ON && TARGET > 0;
  private static final long INTERVAL_NS = Math.max(1, Math.min(50, Integer.getInteger("bta.prof.sampleMs", 2))) * 1_000_000L;
  private static final boolean REPORT = !"false".equalsIgnoreCase(System.getProperty("bta.prof.sampleReport"));
  private static final long REPORT_NS = 10_000_000_000L, HEAL_NS = 50_000_000L;

  /** The probed method on the thread that runs it. Immutable: published through one reference. */
  static final class Target {
    final Thread thread; final String cls, method;
    Target(Thread thread, String cls, String method) { this.thread = thread; this.cls = cls; this.method = method; }
  }

  private static final AtomicReference<Target> target = new AtomicReference<>();
  private static String cls, method;   // read once, at the first enter (main thread)
  private static volatile String name; // "Class.method" of the probed method, for the report
  private static int depth;            // main thread only
  private static Thread sampler;
  private static volatile boolean failed;

  private static final Object LOCK = new Object();
  private static int valid, discarded, intervals;
  private static long stackNs, stackMaxNs, intervalNs, intervalMaxNs, lastSample, streakStart, lastReport;
  private static Target streak;
  private static final Map<String, Integer> inclusive = new HashMap<>(), self = new HashMap<>();

  private ProfilerSampler() { }

  private static int resolve(String probeName) {
    if (probeName == null) return -1;
    for (int i = 1; i < Profiler.NAMES.length; i++) if (Profiler.NAMES[i].equals(probeName)) return i;
    System.out.println("[BTA-sample] unknown probe '" + probeName + "' (-Dbta.prof.sample): sampling off");
    return -1;
  }

  /** Profiler.enter of the sampled probe, on the main thread. */
  public static void enter() {
    if (!ON || failed) return;
    if (target.get() == null) {
      depth = 0;
      if (cls == null && !findCaller()) return;
      target.set(new Target(Thread.currentThread(), cls, method));
      if (sampler == null) {
        sampler = new Thread(ProfilerSampler::loop, "BTA-sampler");
        sampler.setDaemon(true);
        sampler.start();
        if (REPORT) Runtime.getRuntime().addShutdownHook(new Thread(() -> { // the last window, when the game closes
          synchronized (LOCK) { report(System.nanoTime()); }
        }, "BTA-sample-report"));
      }
      LockSupport.unpark(sampler);
    }
    depth++;
  }

  /** Profiler.exit of the sampled probe, on the main thread. */
  public static void exit() {
    if (!ON) return;
    if (depth > 0 && --depth == 0) target.set(null);
  }

  /** The probed method: first frame on this stack that is not Thread, Throwable, Profiler or ProfilerSampler. */
  private static boolean findCaller() {
    for (StackTraceElement frame : new Throwable().getStackTrace()) {
      String className = frame.getClassName();
      if (className.equals("java.lang.Thread") || className.equals("java.lang.Throwable") || className.equals(Profiler.class.getName())
          || className.equals(ProfilerSampler.class.getName())) continue;
      cls = className; method = frame.getMethodName();
      name = key(frame);
      return true;
    }
    return false;
  }

  private static void loop() {
    try {
      lastReport = System.nanoTime();
      while (true) {
        Target current = target.get();
        if (current == null) { // idle: no interval spans the gap; the window still gets reported on time
          synchronized (LOCK) {
            lastSample = 0;
            if (REPORT && System.nanoTime() - lastReport >= REPORT_NS) report(System.nanoTime());
          }
          if (REPORT) LockSupport.parkNanos(REPORT_NS); else LockSupport.park();
          continue;
        }
        LockSupport.parkNanos(INTERVAL_NS);
        if (target.get() != current) { synchronized (LOCK) { lastSample = 0; } continue; } // that call ended
        long before = System.nanoTime();
        StackTraceElement[] stack = current.thread.getStackTrace();
        long now = System.nanoTime(), took = now - before;
        synchronized (LOCK) {
          stackNs += took; if (took > stackMaxNs) stackMaxNs = took;
          if (lastSample != 0) {
            long gap = now - lastSample;
            intervalNs += gap; intervals++;
            if (gap > intervalMaxNs) intervalMaxNs = gap;
          }
          lastSample = now;
          if (target.get() == current && attribute(stack, current.cls, current.method, inclusive, self)) {
            valid++;
            streak = null;
          } else {
            discarded++;
            if (streak != current) { streak = current; streakStart = now; }
            else if (now - streakStart >= HEAL_NS) target.compareAndSet(current, null);
          }
          if (REPORT && now - lastReport >= REPORT_NS) report(now);
        }
      }
    } catch (Throwable e) {
      failed = true;
      target.set(null);
      System.out.println("[BTA-sample] sampling stopped: " + e);
    }
  }

  /**
   * Counts one sample: frames above the outermost occurrence of cls.method. Inclusive: each distinct
   * Class.method once; self: the innermost frame outside java.*, jdk.* and sun.* (the probed method itself
   * when nothing is above it). Returns false, touching nothing, when cls.method is not on the stack.
   */
  static boolean attribute(StackTraceElement[] stack, String cls, String method, Map<String, Integer> incl, Map<String, Integer> self) {
    int outermost = -1;
    for (int i = stack.length - 1; i >= 0; i--)
      if (stack[i].getClassName().equals(cls) && stack[i].getMethodName().equals(method)) { outermost = i; break; }
    if (outermost < 0) return false;
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < outermost; i++) {
      String key = key(stack[i]);
      if (seen.add(key)) incl.merge(key, 1, Integer::sum);
    }
    for (int i = 0; i <= outermost; i++) {
      String className = stack[i].getClassName();
      if (className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.")) continue;
      self.merge(key(stack[i]), 1, Integer::sum);
      break;
    }
    return true;
  }

  private static String key(StackTraceElement frame) {
    String className = frame.getClassName();
    return className.substring(className.lastIndexOf('.') + 1) + "." + frame.getMethodName();
  }

  /** "64% A.b, 22% C.d" — the n largest counts as shares of total. */
  static String top(Map<String, Integer> counts, int total, int n) {
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
    entries.sort((x, y) -> {
      int byCount = Integer.compare(y.getValue(), x.getValue());
      return byCount != 0 ? byCount : x.getKey().compareTo(y.getKey());
    });
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < Math.min(n, entries.size()); i++) {
      if (i > 0) out.append(", ");
      out.append(Math.round(100.0 * entries.get(i).getValue() / total)).append("% ").append(entries.get(i).getKey());
    }
    return out.toString();
  }

  /** Under LOCK. Prints and clears the window, if it has samples. */
  private static void report(long now) {
    lastReport = now;
    int samples = valid + discarded;
    if (samples == 0) return;
    String cost = String.format(java.util.Locale.ROOT, "interval %.1f ms real, getStackTrace avg %.2f ms max %.2f ms",
        intervals == 0 ? 0 : intervalNs / 1e6 / intervals, stackNs / 1e6 / samples, stackMaxNs / 1e6);
    System.out.println("[BTA-sample] " + name + ": " + valid + " valid, " + discarded + " discarded, " + cost);
    if (valid > 0) {
      System.out.println("[BTA-sample] inclusive: " + top(inclusive, valid, 12));
      System.out.println("[BTA-sample] self: " + top(self, valid, 8));
    }
    valid = discarded = intervals = 0;
    stackNs = stackMaxNs = intervalNs = intervalMaxNs = 0;
    inclusive.clear(); self.clear();
  }

  /** For the tests: a consistent copy of the counters. */
  static final class Snapshot {
    int valid, discarded, depth;
    long intervalMaxNs;
    boolean published;
    Map<String, Integer> inclusive, self;
  }

  static Snapshot snapshot() {
    Snapshot copy = new Snapshot();
    synchronized (LOCK) {
      copy.valid = valid; copy.discarded = discarded; copy.intervalMaxNs = intervalMaxNs;
      copy.inclusive = new HashMap<>(inclusive); copy.self = new HashMap<>(self);
    }
    copy.depth = depth;
    copy.published = target.get() != null;
    return copy;
  }
}
