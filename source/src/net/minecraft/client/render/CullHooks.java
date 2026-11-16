package net.minecraft.client.render;

import net.betterthanadventure.btamobile.meshfix.MobileOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.camera.ICamera;
import net.minecraft.client.render.tessellator.TessellatorGeneral;
import net.minecraft.client.render.terrain.CaveCuller;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.world.World;

/**
 * Entity culling (Sodium style): an entity or block entity is skipped when none of the terrain
 * sections it touches was reached by the cave-culling walk this frame, i.e. it is behind terrain.
 * Players, the player's mount and anything huge are always drawn. Only active while cave culling runs.
 */
public final class CullHooks {
  /** Replaces EntityRendererDispatcher.renderEntity in RenderGlobal.renderEntities' entity loop. */
  public static void renderEntity(EntityRendererDispatcher dispatcher, TessellatorGeneral tessellator, Entity entity, float partialTicks) {
    Minecraft minecraft = Minecraft.getMinecraft();
    if (MobileOptions.entityCulling() && !(entity instanceof Player) && !entity.ignoreFrustumCheck && CaveCuller.walkedFor(minecraft.activeCamera)) {
      boolean isPlayerMount = minecraft.thePlayer != null && (Object) minecraft.thePlayer.vehicle == (Object) entity;
      org.joml.primitives.AABBdc viewBox = null;
      try { viewBox = dispatcher.getRenderer(entity).entityViewBox(entity); } catch (Throwable ignored) { }
      if (viewBox == null) viewBox = entity.bb;
      if (!isPlayerMount && !CaveCuller.boxReached(viewBox.minX(), viewBox.minY(), viewBox.minZ(), viewBox.maxX(), viewBox.maxY(), viewBox.maxZ())) {
        RenderGlobal renderGlobal = minecraft.renderGlobal;
        if (renderGlobal != null) { renderGlobal.renderedEntities--; renderGlobal.culledEntities++; }
        return;
      }
    }
    dispatcher.renderEntity(tessellator, entity, partialTicks);
  }

  /** Replaces TileEntityRenderDispatcher.renderTileEntity(tessellator, camera, world, tileEntity, partialTicks) in renderEntities. */
  public static void renderTileEntity(TileEntityRenderDispatcher dispatcher, TessellatorGeneral tessellator, ICamera camera, World world, TileEntity tileEntity, float partialTicks) {
    if (MobileOptions.entityCulling() && tileEntity != null && tileEntity.tilePos != null && CaveCuller.walkedFor(camera)) {
      // 3 blocks of margin: some block-entity models (flags, statues, signs) stick out of their block
      int x = tileEntity.tilePos.x, y = tileEntity.tilePos.y, z = tileEntity.tilePos.z;
      if (!CaveCuller.boxReached(x - 3, y - 3, z - 3, x + 4, y + 4, z + 4)) return;
    }
    dispatcher.renderTileEntity(tessellator, camera, world, tileEntity, partialTicks);
  }
}
