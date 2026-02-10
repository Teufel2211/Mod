package core.map;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.Map;

public class MapUtilities {

    /**
     * Get biome information for map overlay
     */
    public static Map<BlockPos, String> getBiomeOverlay(ServerWorld world, int startX, int startZ, int endX, int endZ) {
        Map<BlockPos, String> biomes = new HashMap<>();

        for (int x = startX; x <= endX; x += 16) { // Sample every chunk
            for (int z = startZ; z <= endZ; z += 16) {
                BlockPos pos = new BlockPos(x, 64, z); // Sample at sea level
                Biome biome = world.getBiome(pos).value();
                String biomeName = biome.toString(); // Simplified biome name
                biomes.put(new BlockPos(x, 0, z), biomeName);
            }
        }

        return biomes;
    }

    /**
     * Get cave mapping data (simplified - marks areas with low light levels)
     */
    public static Map<BlockPos, Integer> getCaveOverlay(ServerWorld world, int startX, int startY, int startZ, int endX, int endY, int endZ) {
        Map<BlockPos, Integer> caves = new HashMap<>();

        for (int x = startX; x <= endX; x += 4) { // Sample every 4 blocks for performance
            for (int z = startZ; z <= endZ; z += 4) {
                for (int y = startY; y <= endY; y += 4) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                        int lightLevel = world.getLightLevel(pos);
                        if (lightLevel < 8) { // Consider low light areas as potential caves
                            caves.put(pos, lightLevel);
                        }
                    }
                }
            }
        }

        return caves;
    }

    /**
     * Get heightmap data for terrain visualization
     */
    public static Map<BlockPos, Integer> getHeightMap(ServerWorld world, int startX, int startZ, int endX, int endZ) {
        Map<BlockPos, Integer> heights = new HashMap<>();

        for (int x = startX; x <= endX; x += 4) {
            for (int z = startZ; z <= endZ; z += 4) {
                BlockPos pos = world.getTopPosition(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, new BlockPos(x, 0, z));
                heights.put(new BlockPos(x, 0, z), pos.getY());
            }
        }

        return heights;
    }

    /**
     * Get ore deposits for mining map overlay
     */
    public static Map<BlockPos, String> getOreOverlay(ServerWorld world, int startX, int startY, int startZ, int endX, int endY, int endZ) {
        Map<BlockPos, String> ores = new HashMap<>();

        for (int x = startX; x <= endX; x += 2) {
            for (int z = startZ; z <= endZ; z += 2) {
                for (int y = startY; y <= endY; y += 2) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                        var block = world.getBlockState(pos).getBlock();
                        String blockName = block.getTranslationKey();

                        // Check for valuable ores
                        if (blockName.contains("diamond") || blockName.contains("emerald") ||
                            blockName.contains("gold") || blockName.contains("iron") ||
                            blockName.contains("coal") || blockName.contains("redstone") ||
                            blockName.contains("lapis")) {
                            ores.put(pos, blockName);
                        }
                    }
                }
            }
        }

        return ores;
    }

    /**
     * Get spawn points and POI data
     */
    public static Map<String, BlockPos> getPointsOfInterest(ServerWorld world) {
        Map<String, BlockPos> pois = new HashMap<>();

        // World spawn
        pois.put("world_spawn", world.getSpawnPoint().getPos());
        // Additional POIs can be added via structure lookups if needed.

        return pois;
    }
}
