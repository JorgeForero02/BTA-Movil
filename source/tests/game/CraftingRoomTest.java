import net.minecraft.core.block.Blocks;
import net.minecraft.core.item.ItemStack;
import net.minecraft.core.item.Items;

/** LegacyCrafting.roomForOutput with BTA's real items: the console craft only starts when the result
 *  fits once the planned ingredients are taken (otherwise BTA's click into the inventory does nothing
 *  and the ingredients would be moved for nothing). */
public class CraftingRoomTest {
  static java.lang.reflect.Method ROOM;
  static boolean room(ItemStack[] inv, int[] src, ItemStack out) throws Exception { return (Boolean) ROOM.invoke(null, inv, src, out); }

  static ItemStack[] fullOfStone() {
    ItemStack[] inv = new ItemStack[36];
    for (int i = 0; i < inv.length; i++) inv[i] = new ItemStack(Blocks.COBBLE_STONE, 64);
    return inv;
  }

  public static void main(String[] a) throws Throwable {
    RealMeshesTest.boot();
    Items.init();
    ROOM = Class.forName("net.minecraft.client.gui.container.LegacyCrafting").getDeclaredMethod("roomForOutput", ItemStack[].class, int[].class, ItemStack.class);
    ROOM.setAccessible(true);
    ItemStack sticks = new ItemStack(Items.STICK, 4);
    boolean ok = true;
    StringBuilder failures = new StringBuilder();

    ItemStack[] inv = fullOfStone();
    if (room(inv, new int[0], sticks)) { ok = false; failures.append(" full-no-room"); }

    inv = fullOfStone(); inv[20] = null;
    if (!room(inv, new int[0], sticks)) { ok = false; failures.append(" free-slot"); }

    inv = fullOfStone(); inv[5] = new ItemStack(Items.STICK, 62);
    if (room(inv, new int[0], sticks)) { ok = false; failures.append(" stack-with-room-for-2"); }

    inv[6] = new ItemStack(Items.STICK, 62);
    if (!room(inv, new int[0], sticks)) { ok = false; failures.append(" two-stacks-add-up-to-4"); }

    inv = fullOfStone(); inv[9] = new ItemStack(Blocks.PLANKS_OAK, 1);
    if (!room(inv, new int[] { 9 }, sticks)) { ok = false; failures.append(" ingredient-empties-slot"); }

    try { if (!room(fullOfStone(), new int[0], null)) { ok = false; failures.append(" no-result"); } }
    catch (java.lang.reflect.InvocationTargetException e) { ok = false; failures.append(" no-result(" + e.getCause() + ")"); }

    System.out.println("CraftingRoomTest: " + (ok ? "OK (full, free slot, stacks with room, slot emptied by the ingredients)" : "FAIL:" + failures));
    if (!ok) System.exit(1);
  }
}
