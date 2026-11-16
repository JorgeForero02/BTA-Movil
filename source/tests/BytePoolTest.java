import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.betterthanadventure.btamobile.meshfix.BytePool;

/** BytePool with 8 threads: never hands the same array to two users at once, honors sizes and the memory cap. */
public class BytePoolTest {
  public static void main(String[] a) throws Exception {
    int threads = 8, rounds = 200_000;
    Set<byte[]> inUse = ConcurrentHashMap.newKeySet();
    AtomicInteger failures = new AtomicInteger();
    ExecutorService ex = Executors.newFixedThreadPool(threads);
    List<Future<?>> fs = new ArrayList<>();
    for (int h = 0; h < threads; h++) {
      final int seed = h;
      fs.add(ex.submit(() -> {
        Random r = new Random(seed);
        for (int i = 0; i < rounds; i++) {
          int n = r.nextInt(300_000);
          byte[] b = BytePool.take(n);
          if (b.length < n) failures.incrementAndGet();
          if (!inUse.add(b)) failures.incrementAndGet(); // the same array in two hands
          b[0] = (byte) seed; if (n > 0) b[n - 1] = (byte) seed;
          if (b[0] != (byte) seed) failures.incrementAndGet();
          inUse.remove(b);
          BytePool.give(b);
        }
      }));
    }
    for (Future<?> f : fs) f.get();
    ex.shutdown();
    boolean ok = failures.get() == 0 && BytePool.pooledBytes() <= BytePool.MAX_POOLED && BytePool.pooledBytes() >= 0;
    BytePool.give(new byte[1000]); BytePool.give(new byte[5000]); BytePool.give(null); // foreign arrays: ignored
    BytePool.clear();
    ok &= BytePool.pooledBytes() == 0;
    System.out.println((ok ? "BytePoolTest: OK" : "BytePoolTest: FAILED (" + failures.get() + ")") + " - reused " + BytePool.reused.get() + ", new " + BytePool.allocated.get());
    if (!ok) System.exit(1);
  }
}
