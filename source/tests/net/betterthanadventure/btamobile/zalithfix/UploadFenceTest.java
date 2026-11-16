package net.betterthanadventure.btamobile.zalithfix;

/** BufferUpload's deferred frees: VBO space is given back only when the fence of its batch is done,
 *  oldest batch first; new/grown buffers stay pending until their fence. Pure bookkeeping, no GL. */
public class UploadFenceTest {
  public static void main(String[] a) throws Exception {
    boolean ok = true;
    int[] freed = new int[1];
    BufferUpload.Ranges.releaseAll(); freed[0] = 0;
    // frame 1 frees 3 sections, frame 2 frees 2 and a new buffer appears
    for (int k = 0; k < 3; k++) BufferUpload.Ranges.OPEN.release.add(() -> freed[0]++);
    BufferUpload.Ranges.close(11);
    for (int k = 0; k < 2; k++) BufferUpload.Ranges.OPEN.release.add(() -> freed[0]++);
    BufferUpload.Ranges.OPEN.add(7, 0, Long.MAX_VALUE);
    BufferUpload.Ranges.close(12);
    ok &= BufferUpload.Ranges.pending(7, 123, 456) && !BufferUpload.Ranges.pending(8, 0, 10);
    BufferUpload.Ranges.purge(s -> false, null);
    ok &= freed[0] == 0;                                       // GPU not done: nothing reused
    BufferUpload.Ranges.purge(s -> s == 12, null);
    ok &= freed[0] == 0;                                       // in order: 12 waits for 11
    BufferUpload.Ranges.purge(s -> s == 11, null);
    ok &= freed[0] == 3 && BufferUpload.Ranges.pending(7, 0, 1); // only frame 1 given back
    BufferUpload.Ranges.purge(s -> true, null);
    ok &= freed[0] == 5 && !BufferUpload.Ranges.pending(7, 0, 1);
    BufferUpload.Ranges.OPEN.release.add(() -> freed[0]++);
    BufferUpload.Ranges.releaseAll();
    ok &= freed[0] == 6 && BufferUpload.Ranges.FENCED.isEmpty();
    ok &= deferFree();
    System.out.println("Upload fences: " + (ok ? "OK (space is given back only when its fence completes, in order; new buffer pending)" : "FAIL"));
    if (!ok) System.exit(1);
  }

  /** deferFree on a real VertexBuffer (built without its constructor, so no GL): the removed Entry stays
   *  in vb.entries (BTA's allocator cannot reuse its space) until the fence of its batch completes. */
  static boolean deferFree() throws Exception {
    java.lang.reflect.Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
    sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
    net.minecraft.client.render.terrain.VertexBuffer vb = (net.minecraft.client.render.terrain.VertexBuffer) u.allocateInstance(net.minecraft.client.render.terrain.VertexBuffer.class);
    java.lang.reflect.Field ef = net.minecraft.client.render.terrain.VertexBuffer.class.getField("entries"); ef.setAccessible(true);
    ef.set(vb, new java.util.ArrayList<>());
    net.minecraft.client.render.terrain.VertexBuffer.Entry<?> e = (net.minecraft.client.render.terrain.VertexBuffer.Entry<?>) u.allocateInstance(net.minecraft.client.render.terrain.VertexBuffer.Entry.class);
    vb.entries.add(e);
    BufferUpload.Ranges.releaseAll();
    boolean ok = BufferUpload.deferFree(vb, e);               // deferred: BTA's removal is skipped
    ok &= vb.entries.contains(e);                              // space still taken
    ok &= !BufferUpload.deferFree(vb, null);                   // nothing to defer: BTA removes as usual
    BufferUpload.Ranges.close(21);
    BufferUpload.Ranges.purge(s -> false, null);
    ok &= vb.entries.contains(e);                              // fence pending: still taken
    BufferUpload.Ranges.purge(s -> s == 21, null);
    ok &= !vb.entries.contains(e);                             // fence done: given back
    if (!ok) System.out.println("deferFree: the VBO space does not wait for its fence");
    return ok;
  }
}
