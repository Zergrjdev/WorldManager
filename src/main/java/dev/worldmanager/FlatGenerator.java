package dev.worldmanager;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One generator for every non-vanilla world this plugin makes:
 * <ul>
 *   <li>superflat worlds (the default layers, or any layers from a preset string),</li>
 *   <li>void worlds (no layers, optionally a small spawn platform).</li>
 * </ul>
 * It always uses a single biome and has a toggle for structure generation.
 */
final class FlatGenerator extends ChunkGenerator {

    /** The vanilla default superflat layers. */
    static final String DEFAULT_LAYERS = "minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block";

    private static final int MAX_TOTAL_HEIGHT = 384;
    /** Y of the spawn platform in void worlds (the player stands on PLATFORM_Y + 1). */
    private static final int PLATFORM_Y = 64;

    record Layer(Material block, int height) {
    }

    /** A parsed superflat preset: "layers;biome". */
    record Preset(String layersText, List<Layer> layers, String biome) {
    }

    private final Biome biome;
    private final boolean structures;
    private final List<Layer> layers;
    private final Material platform; // only used when there are no layers (void world); null = no platform
    private final int totalHeight;

    FlatGenerator(Biome biome, boolean structures, List<Layer> layers, Material platform) {
        this.biome = biome;
        this.structures = structures;
        this.layers = layers;
        this.platform = platform;
        this.totalHeight = layers.stream().mapToInt(Layer::height).sum();
    }

    // ------------------------------------------------------------------ preset parsing

    /**
     * Parses a preset such as {@code minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains}.
     * The biome part is optional (plains). Throws IllegalArgumentException with a readable message.
     */
    static Preset parsePreset(String text) {
        String[] parts = text.split(";");
        if (parts.length == 0 || parts.length > 3) {
            throw new IllegalArgumentException("A preset looks like: " + DEFAULT_LAYERS + ";minecraft:plains");
        }
        String layersText = parts[0].trim();
        List<Layer> layers = parseLayers(layersText);
        String biome = parts.length >= 2 && !parts[1].isBlank() ? parts[1].trim() : "minecraft:plains";
        return new Preset(layersText, layers, biome);
    }

    /** Parses "minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block" (bottom layer first). */
    static List<Layer> parseLayers(String text) {
        List<Layer> out = new ArrayList<>();
        int total = 0;
        for (String raw : text.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            int height = 1;
            String block = token;
            int star = token.indexOf('*');
            if (star >= 0) {
                try {
                    height = Integer.parseInt(token.substring(0, star).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad layer height in '" + token + "'.");
                }
                block = token.substring(star + 1).trim();
            }
            if (height < 1) throw new IllegalArgumentException("Layer heights must be at least 1 ('" + token + "').");
            Material material = Material.matchMaterial(block);
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("Unknown block '" + block + "'.");
            }
            total += height;
            if (total > MAX_TOTAL_HEIGHT) {
                throw new IllegalArgumentException("The layers are taller than " + MAX_TOTAL_HEIGHT + " blocks.");
            }
            out.add(new Layer(material, height));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("The preset has no layers.");
        return out;
    }

    // ------------------------------------------------------------------ generation

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData data) {
        int y = worldInfo.getMinHeight();
        int max = worldInfo.getMaxHeight();
        for (Layer layer : layers) {
            if (y >= max) break;
            int end = Math.min(y + layer.height(), max);
            data.setRegion(0, y, 0, 16, end, 16, layer.block());
            y = end;
        }
        if (layers.isEmpty() && platform != null) {
            // 3x3 platform around 0,0 (it can touch up to four chunks, so only place the blocks that fall in this one).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx >> 4) == chunkX && (dz >> 4) == chunkZ) {
                        data.setBlock(dx & 15, PLATFORM_Y, dz & 15, platform);
                    }
                }
            }
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new BiomeProvider() {
            @Override
            public Biome getBiome(WorldInfo info, int x, int y, int z) {
                return biome;
            }

            @Override
            public List<Biome> getBiomes(WorldInfo info) {
                return List.of(biome);
            }
        };
    }

    /** First free block above the ground, so structures don't float or sink. */
    @Override
    public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
        return groundY(worldInfo.getMinHeight());
    }

    @Override
    public boolean shouldGenerateStructures() {
        return structures;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, groundY(world.getMinHeight()), 0.5);
    }

    private int groundY(int minHeight) {
        return layers.isEmpty() ? PLATFORM_Y + 1 : minHeight + totalHeight;
    }
}
