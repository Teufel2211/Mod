package core.map.client;

import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Map;

public final class MinimapHud implements net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback {
    private static final Identifier TEX_ID = Identifier.of("core", "minimap");

    private NativeImageBackedTexture texture;
    private NativeImage image;
    private int size;
    private int cursorX;

    public void tick(MinecraftClient client) {
        MinimapConfig cfg = MinimapConfig.get();
        if (!cfg.enabled) return;
        if (client.player == null || client.world == null) return;

        ensureTexture(cfg.sizePx);
        updateColumns(client, cfg);
        texture.upload();
    }

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        MinimapConfig cfg = MinimapConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();
        if (!cfg.enabled) return;
        if (client.player == null || client.world == null) return;

        ensureTexture(cfg.sizePx);

        int x = 8;
        int y = 8;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, TEX_ID, x, y, 0, 0, size, size, size, size);

        if (cfg.showInfo) {
            drawInfo(context, client, x, y + size + 4);
        }
    }

    private void ensureTexture(int targetSize) {
        if (texture != null && image != null && size == targetSize) return;

        size = targetSize;
        cursorX = 0;
        image = new NativeImage(size, size, true);
        // Initialize fully transparent.
        for (int px = 0; px < size; px++) {
            for (int py = 0; py < size; py++) {
                image.setColorArgb(px, py, 0x00000000);
            }
        }
        texture = new NativeImageBackedTexture(() -> "core/minimap", image);
        MinecraftClient.getInstance().getTextureManager().registerTexture(TEX_ID, texture);
    }

    private void updateColumns(MinecraftClient client, MinimapConfig cfg) {
        PlayerEntity player = client.player;
        World world = client.world;

        int half = size / 2;
        int columns = Math.min(cfg.updateColumnsPerTick, size);
        int blocksPerPx = Math.max(1, cfg.blocksPerPixel);

        for (int cx = 0; cx < columns; cx++) {
            int px = (cursorX + cx) % size;
            int dx = px - half;
            for (int py = 0; py < size; py++) {
                int dz = py - half;

                int sampleX;
                int sampleZ;
                if (cfg.rotate) {
                    float yawRad = (float) Math.toRadians(player.getYaw());
                    float dirX = -MathHelper.sin(yawRad);
                    float dirZ = MathHelper.cos(yawRad);
                    float rightX = -dirZ;
                    float rightZ = dirX;
                    float backX = -dirX;
                    float backZ = -dirZ;
                    float worldOffX = (dx * rightX + dz * backX) * blocksPerPx;
                    float worldOffZ = (dx * rightZ + dz * backZ) * blocksPerPx;
                    sampleX = MathHelper.floor(player.getX() + worldOffX);
                    sampleZ = MathHelper.floor(player.getZ() + worldOffZ);
                } else {
                    sampleX = MathHelper.floor(player.getX()) + dx * blocksPerPx;
                    sampleZ = MathHelper.floor(player.getZ()) + dz * blocksPerPx;
                }

                int argb = sampleColor(world, sampleX, sampleZ);

                if (cfg.circle) {
                    int r = half;
                    int dist2 = dx * dx + dz * dz;
                    if (dist2 > r * r) {
                        argb = 0x00000000;
                    }
                }

                image.setColorArgb(px, py, argb);
            }
        }
        cursorX = (cursorX + columns) % size;

        if (cfg.showWaypoints) {
            overlayWaypoints(client, cfg);
        }
    }

    private int sampleColor(World world, int x, int z) {
        BlockPos top = world.getTopPosition(Heightmap.Type.WORLD_SURFACE, new BlockPos(x, 0, z));
        if (top == null) return 0x00000000;

        // getTopPosition returns the first air block above the top surface; sample below.
        BlockPos samplePos = top.down();
        if (!world.isChunkLoaded(samplePos.getX() >> 4, samplePos.getZ() >> 4)) {
            return 0x00000000;
        }
        var state = world.getBlockState(samplePos);
        MapColor mapColor = state.getMapColor(world, samplePos);
        int rgb = mapColor.getRenderColor(MapColor.Brightness.NORMAL) & 0x00FFFFFF;
        return 0xFF000000 | rgb;
    }

    private void overlayWaypoints(MinecraftClient client, MinimapConfig cfg) {
        PlayerEntity player = client.player;
        World world = client.world;
        RegistryKey<World> dimKey = world.getRegistryKey();
        String currentDim = dimKey.getValue().toString();

        int half = size / 2;
        int blocksPerPx = Math.max(1, cfg.blocksPerPixel);

        for (ClientWaypoint wp : ClientWaypoints.snapshot().values()) {
            if (wp.dimension != null && !wp.dimension.isEmpty() && !wp.dimension.equals(currentDim)) continue;

            double dWorldX = wp.pos.getX() + 0.5 - player.getX();
            double dWorldZ = wp.pos.getZ() + 0.5 - player.getZ();

            double mapDx;
            double mapDz;
            if (cfg.rotate) {
                float yawRad = (float) Math.toRadians(player.getYaw());
                float dirX = -MathHelper.sin(yawRad);
                float dirZ = MathHelper.cos(yawRad);
                float rightX = -dirZ;
                float rightZ = dirX;
                float backX = -dirX;
                float backZ = -dirZ;
                mapDx = dWorldX * rightX + dWorldZ * rightZ;
                mapDz = dWorldX * backX + dWorldZ * backZ;
            } else {
                mapDx = dWorldX;
                mapDz = dWorldZ;
            }

            int px = half + (int) Math.round(mapDx / blocksPerPx);
            int py = half + (int) Math.round(mapDz / blocksPerPx);

            if (px < 0 || py < 0 || px >= size || py >= size) continue;
            int color = 0xFFFF0000;
            String type = (wp.type != null) ? wp.type.toLowerCase() : "";
            if (type.contains("home")) color = 0xFF00FF00;
            else if (type.contains("base")) color = 0xFF00A0FF;
            else if (type.contains("farm")) color = 0xFFFFFF00;
            else if (type.contains("mine")) color = 0xFFB0B0B0;
            else if (type.contains("portal")) color = 0xFFB000FF;

            drawDot(px, py, color);
        }
    }

    private void drawDot(int cx, int cy, int argb) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                int x = cx + ox;
                int y = cy + oy;
                if (x < 0 || y < 0 || x >= size || y >= size) continue;
                image.setColorArgb(x, y, argb);
            }
        }
    }

    private void drawInfo(DrawContext context, MinecraftClient client, int x, int y) {
        PlayerEntity player = client.player;
        if (player == null) return;

        int px = MathHelper.floor(player.getX());
        int py = MathHelper.floor(player.getY());
        int pz = MathHelper.floor(player.getZ());
        String facing = player.getHorizontalFacing().asString().toUpperCase();

        String biomeName = "?";
        if (client.world != null) {
            biomeName = client.world.getBiome(player.getBlockPos()).getKey()
                .map(key -> key.getValue().toString())
                .orElse("?");
        }

        long time = (client.world != null) ? (client.world.getTimeOfDay() % 24000L) : 0L;
        int hour = (int) ((time / 1000L + 6) % 24);
        int minute = (int) (60 * (time % 1000L) / 1000L);

        context.drawTextWithShadow(client.textRenderer, Text.literal("XYZ: " + px + " / " + py + " / " + pz), x, y, 0xFFFFFF);
        context.drawTextWithShadow(client.textRenderer, Text.literal("Facing: " + facing), x, y + 10, 0xFFFFFF);
        context.drawTextWithShadow(client.textRenderer, Text.literal("Biome: " + biomeName), x, y + 20, 0xFFFFFF);
        context.drawTextWithShadow(client.textRenderer, Text.literal(String.format("Time: %02d:%02d", hour, minute)), x, y + 30, 0xFFFFFF);
    }
}
