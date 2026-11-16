package net.minecraft.client.render.block.model;

import net.betterthanadventure.btamobile.meshfix.Mesher;
import net.minecraft.client.render.LightingCache;
import net.minecraft.core.world.pos.TilePos;
import org.useless.dragonfly.models.block.mojang.StaticBlockModelMojang;

/**
 * Block models keep per-block scratch state in static fields shared by every thread. Reads of
 * those fields are redirected here (MeshPatch) so the mesher thread gets its own copies and never
 * mixes its positions with what the main thread is drawing at the same time:
 *  - BlockModelStandard.renderBlocks (AO colours, override texture, its lighting cache);
 *  - StaticBlockModelMojang.LIGHTING_CACHE (dragonfly JSON models: its queryPos was overwritten by
 *    the main thread, sending the worker to a far chunk -> ArrayIndexOutOfBounds in ChunkCache);
 *  - BlockModelGenericPortal's static queryPos;
 *  - per-model scratch objects, written before each use: StaticBlockModelMojang's vertPos/faceVec/vertUV
 *    and the queryPos of BlockModelGeneric, BlockModelGenericLayer and BlockModelGenericSlab;
 *  - BlockModelFluid's scratch vectors (br, bl, tr, a, b, norm) and its Random (re-seeded per block),
 *    so several mesher workers can draw fluids at once.
 */
public final class MesherRenderBlocks {
  private static final ThreadLocal<RenderBlocks> WORKER = ThreadLocal.withInitial(RenderBlocks::new);
  private static final ThreadLocal<LightingCache> WORKER_LIGHT = ThreadLocal.withInitial(LightingCache::new);
  private static final ThreadLocal<TilePos> WORKER_PORTAL_POS = ThreadLocal.withInitial(TilePos::new);
  private static final LightingCache MAIN_LIGHT = new LightingCache();
  private static final TilePos MAIN_PORTAL_POS = new TilePos();

  public static RenderBlocks renderBlocks() {
    return Mesher.isWorker() ? WORKER.get() : BlockModelStandard.renderBlocks;
  }

  /** Replaces reads of StaticBlockModelMojang.LIGHTING_CACHE. */
  public static LightingCache lightingCache() {
    return Mesher.isWorker() ? WORKER_LIGHT.get() : MAIN_LIGHT;
  }

  /** Replaces reads of BlockModelGenericPortal's static queryPos. */
  public static TilePos portalQueryPos() {
    return Mesher.isWorker() ? WORKER_PORTAL_POS.get() : MAIN_PORTAL_POS;
  }

  private static final ThreadLocal<org.joml.Vector3d> W_VERT_POS = ThreadLocal.withInitial(org.joml.Vector3d::new);
  private static final ThreadLocal<org.joml.Vector3f> W_FACE_VEC = ThreadLocal.withInitial(org.joml.Vector3f::new);
  private static final ThreadLocal<org.joml.Vector2f> W_VERT_UV = ThreadLocal.withInitial(org.joml.Vector2f::new);

  public static org.joml.Vector3d vertPos(StaticBlockModelMojang model) { return Mesher.isWorker() ? W_VERT_POS.get() : model.vertPos; }
  public static org.joml.Vector3f faceVec(StaticBlockModelMojang model) { return Mesher.isWorker() ? W_FACE_VEC.get() : model.faceVec; }
  public static org.joml.Vector2f vertUV(StaticBlockModelMojang model) { return Mesher.isWorker() ? W_VERT_UV.get() : model.vertUV; }

  private static final ThreadLocal<TilePos> W_GENERIC_POS = ThreadLocal.withInitial(TilePos::new);
  private static final ThreadLocal<TilePos> W_LAYER_POS = ThreadLocal.withInitial(TilePos::new);
  private static final ThreadLocal<TilePos> W_SLAB_POS = ThreadLocal.withInitial(TilePos::new);

  public static TilePos genericQueryPos(net.minecraft.client.render.block.model.generic.BlockModelGeneric<?> model) { return Mesher.isWorker() ? W_GENERIC_POS.get() : model.queryPos; }
  public static TilePos layerQueryPos(net.minecraft.client.render.block.model.generic.BlockModelGenericLayer<?> model) { return Mesher.isWorker() ? W_LAYER_POS.get() : model.queryPos; }
  public static TilePos slabQueryPos(net.minecraft.client.render.block.model.generic.BlockModelGenericSlab<?> model) { return Mesher.isWorker() ? W_SLAB_POS.get() : model.queryPos; }
  // BlockModelFluid scratch vectors (one copy per mesher thread; the main thread keeps the model's own).
  private static final ThreadLocal<org.joml.Vector3d> W_FLUID_BR = ThreadLocal.withInitial(org.joml.Vector3d::new);
  private static final ThreadLocal<org.joml.Vector3d> W_FLUID_BL = ThreadLocal.withInitial(org.joml.Vector3d::new);
  private static final ThreadLocal<org.joml.Vector3d> W_FLUID_TR = ThreadLocal.withInitial(org.joml.Vector3d::new);
  private static final ThreadLocal<org.joml.Vector3d> W_FLUID_A = ThreadLocal.withInitial(org.joml.Vector3d::new);
  private static final ThreadLocal<org.joml.Vector3d> W_FLUID_B = ThreadLocal.withInitial(org.joml.Vector3d::new);
  private static final ThreadLocal<org.joml.Vector3d> W_FLUID_NORM = ThreadLocal.withInitial(org.joml.Vector3d::new);
  public static org.joml.Vector3d fluidBr(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_BR.get() : model.br; }
  public static org.joml.Vector3d fluidBl(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_BL.get() : model.bl; }
  public static org.joml.Vector3d fluidTr(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_TR.get() : model.tr; }
  public static org.joml.Vector3d fluidA(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_A.get() : model.a; }
  public static org.joml.Vector3d fluidB(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_B.get() : model.b; }
  public static org.joml.Vector3d fluidNorm(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_NORM.get() : model.norm; }
  /** BlockModelFluid.random is re-seeded per block before use, so a per-thread Random gives the same result. */
  private static final ThreadLocal<java.util.Random> W_FLUID_RANDOM = ThreadLocal.withInitial(java.util.Random::new);
  public static java.util.Random fluidRandom(BlockModelFluid<?> model) { return Mesher.isWorker() ? W_FLUID_RANDOM.get() : model.random; }
}
