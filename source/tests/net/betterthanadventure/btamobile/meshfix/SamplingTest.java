package net.betterthanadventure.btamobile.meshfix;

import java.util.HashMap;
import java.util.Map;

/**
 * ProfilerSampler: stack sampling of one Profiler probe (-Dbta.prof.sample). Run with
 * -Dbta.prof=true -Dbta.prof.sample=decorate -Dbta.prof.sampleMs=1 -Dbta.prof.sampleReport=false
 * (cases 1-4, fixed order), or with -Dbta.prof.sample=none (case 5, its own JVM).
 */
public class SamplingTest {
  static void check(boolean ok, String what) {
    if (!ok) { System.out.println("Sampling: FAIL " + what); System.exit(1); }
  }

  static void spin(long ms) {
    long end = System.nanoTime() + ms * 1_000_000L;
    while (System.nanoTime() < end) Thread.onSpinWait();
  }

  static void tree() { spin(30); }
  static void ore() { spin(10); }

  /** The probed method, like ChunkProviderThreaded.decorate$bta in the game. workMs 0 = tree + ore. */
  static void decorate(boolean leakExit, long workMs) {
    Profiler.enter(Profiler.DECORATE);
    if (leakExit) return; // an exception thrown by a callee skips the patched exit the same way
    if (workMs > 0) spin(workMs);
    else { tree(); ore(); }
    Profiler.exit(Profiler.DECORATE);
  }

  static StackTraceElement f(String cls, String method) { return new StackTraceElement(cls, method, null, -1); }

  static Thread sampler() {
    for (Thread t : Thread.getAllStackTraces().keySet()) if ("BTA-sampler".equals(t.getName())) return t;
    return null;
  }

  static boolean waitFor(java.util.function.BooleanSupplier c, long ms) {
    long end = System.nanoTime() + ms * 1_000_000L;
    while (!c.getAsBoolean()) {
      if (System.nanoTime() > end) return false;
      spin(1);
    }
    return true;
  }

  static void afterExit(String where) {
    ProfilerSampler.Snapshot s = ProfilerSampler.snapshot();
    check(!s.published && s.depth == 0, where + ": after exit, published target=" + s.published + " depth=" + s.depth);
  }

  public static void main(String[] a) {
    if ("none".equals(System.getProperty("bta.prof.sample"))) {
      ProfilerSampler.enter();
      ProfilerSampler.exit();
      check(!ProfilerSampler.ON, "unknown name: ON should be false");
      check(sampler() == null, "unknown name: the BTA-sampler thread exists");
      System.out.println("Sampling: OK (unknown name: off, no thread)");
      return;
    }
    check(ProfilerSampler.ON && ProfilerSampler.TARGET == Profiler.DECORATE, "should sample decorate");

    // 1. pure attribution
    String cp = "x.CP";
    Map<String, Integer> incl = new HashMap<>(), self = new HashMap<>();
    StackTraceElement[] s1 = {f("java.util.HashMap", "get"), f("a.World", "setBlock"), f("a.Tree", "place"), f("a.Tree", "place"),
        f("a.Decor", "decorate"), f(cp, "decorate$bta"), f(cp, "decorate"), f("x.Minecraft", "run")};
    check(ProfilerSampler.attribute(s1, cp, "decorate$bta", incl, self), "case 1: valid stack discarded");
    check(incl.equals(Map.of("HashMap.get", 1, "World.setBlock", 1, "Tree.place", 1, "Decor.decorate", 1)), "case 1: inclusive " + incl);
    check(self.equals(Map.of("World.setBlock", 1)), "case 1: self " + self);
    StackTraceElement[] absent = {f("a.World", "setBlock"), f("x.Minecraft", "run")};
    check(!ProfilerSampler.attribute(absent, cp, "decorate$bta", incl, self), "case 1: absent method not discarded");
    check(incl.size() == 4 && self.size() == 1, "case 1: a discarded sample changed the maps");
    Map<String, Integer> i2 = new HashMap<>(), p2 = new HashMap<>();
    StackTraceElement[] cascade = {f("a.World", "setBlock"), f(cp, "decorate$bta"), f("a.Feature", "place"), f(cp, "decorate$bta"),
        f(cp, "decorate"), f("x.Minecraft", "run")};
    check(ProfilerSampler.attribute(cascade, cp, "decorate$bta", i2, p2), "case 1: cascade discarded");
    check(i2.equals(Map.of("World.setBlock", 1, "CP.decorate$bta", 1, "Feature.place", 1)), "case 1: cascade " + i2);
    Map<String, Integer> i3 = new HashMap<>(), p3 = new HashMap<>();
    check(ProfilerSampler.attribute(new StackTraceElement[] {f(cp, "decorate$bta"), f("x.Minecraft", "run")}, cp, "decorate$bta", i3, p3), "case 1: lone root");
    check(i3.isEmpty() && p3.equals(Map.of("CP.decorate$bta", 1)), "case 1: lone root " + i3 + " " + p3);
    check(ProfilerSampler.top(Map.of("a", 3, "b", 1), 4, 2).equals("75% a, 25% b"), "case 1: top " + ProfilerSampler.top(Map.of("a", 3, "b", 1), 4, 2));

    Profiler.enter(Profiler.FRAME); // this thread becomes Profiler's main thread

    // 2. real thread, through Profiler and a lambda (hidden frames below the probed method, as in the game)
    Runnable run = () -> decorate(false, 0);
    long end = System.nanoTime() + 10_000_000_000L;
    while (ProfilerSampler.snapshot().valid < 200 && System.nanoTime() < end) {
      run.run();
      afterExit("case 2");
    }
    ProfilerSampler.Snapshot s = ProfilerSampler.snapshot();
    check(s.valid >= 200, "case 2: only " + s.valid + " valid samples in 10 s (discarded " + s.discarded + ")");
    double treePct = 100.0 * s.inclusive.getOrDefault("SamplingTest.tree", 0) / s.valid;
    double orePct = 100.0 * s.inclusive.getOrDefault("SamplingTest.ore", 0) / s.valid;
    check(Math.abs(treePct - 75) <= 15 && Math.abs(orePct - 25) <= 15,
        String.format(java.util.Locale.ROOT, "case 2: tree %.0f%% ore %.0f%% (expected 75/25 +-15)", treePct, orePct));
    Thread t = sampler();
    check(t != null, "case 2: the BTA-sampler thread does not exist");
    check(waitFor(() -> t.getState() == Thread.State.WAITING, 2000), "case 2: the thread does not park after exit (" + t.getState() + ")");
    int v0 = ProfilerSampler.snapshot().valid;
    spin(50);
    check(ProfilerSampler.snapshot().valid == v0, "case 2: samples counted outside decorate");
    // the measured interval only spans samples inside one probed call, never the gap between two calls
    decorate(false, 40);
    spin(120);
    decorate(false, 40);
    double maxInterval = ProfilerSampler.snapshot().intervalMaxNs / 1e6;
    check(maxInterval < 60, String.format(java.util.Locale.ROOT, "case 2: measured interval %.0f ms (counts the gap between calls)", maxInterval));

    // 3. another probe does not sample
    ProfilerSampler.Snapshot b = ProfilerSampler.snapshot();
    Profiler.enter(Profiler.ENTITIES);
    check(!ProfilerSampler.snapshot().published, "case 3: entities published a target");
    spin(20);
    Profiler.exit(Profiler.ENTITIES);
    ProfilerSampler.Snapshot c = ProfilerSampler.snapshot();
    check(c.valid == b.valid && c.discarded == b.discarded, "case 3: entities changed the counters");

    // 4. lost exit: self-healing, then sampling works again and stops at exit
    decorate(true, 0);
    check(ProfilerSampler.snapshot().depth == 1, "case 4: enter without exit did not leave depth 1"); // published may already be healed
    check(waitFor(() -> !ProfilerSampler.snapshot().published, 2000), "case 4: self-healing did not remove the target in 2 s");
    int before = ProfilerSampler.snapshot().valid;
    end = System.nanoTime() + 2_000_000_000L;
    while (ProfilerSampler.snapshot().valid - before < 10 && System.nanoTime() < end) {
      decorate(false, 40);
      afterExit("case 4");
    }
    int got = ProfilerSampler.snapshot().valid - before;
    check(got >= 10, "case 4: only " + got + " valid samples after self-healing");

    System.out.println(String.format(java.util.Locale.ROOT,
        "Sampling: OK (attribution, %d samples: tree %.0f%% ore %.0f%%, another probe does not sample, lost exit heals)", s.valid, treePct, orePct));
  }
}
