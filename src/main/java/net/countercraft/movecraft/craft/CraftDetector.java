package net.countercraft.movecraft.craft;

import com.mewin.WGCustomFlags.WGCustomFlagsPlugin;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.countercraft.movecraft.Movecraft;
import net.countercraft.movecraft.config.Settings;
import net.countercraft.movecraft.localisation.I18nSupport;
import net.countercraft.movecraft.utils.MovecraftLocation;
import net.countercraft.movecraft.utils.TownyUtils;
import net.countercraft.movecraft.utils.TownyWorldHeightLimits;
import net.countercraft.movecraft.utils.datastructures.FastIntStack;
import net.minecraft.server.v1_10_R1.*;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.v1_10_R1.CraftWorld;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class CraftDetector {
    private static final String ERROR_COLOR = ChatColor.RED.toString() + ChatColor.BOLD;
    private static final String BLOCK_NAME_COLOR = ChatColor.WHITE.toString() + ChatColor.BOLD;

    private boolean[] blocks;
    private int totalBlocksCount;
    private int minX;
    private int minY;
    private int minZ;
    private int sizeX;
    private int sizeY;
    private int sizeZ;
    private double dynamicFlyBlockSpeedMultiplier;

    // We divide the space surrounding the starting point in cuboids of CUBOID_SIZE_X x CUBOID_SIZE_Y x CUBOID_SIZE_Z.
    // The starting point is located in the column at the center of the cuboid that sits in the middle of the detection
    // space, with a height that matches the Y coordinate of the starting point.

    // Positions are encoded as 12 bits unsigned values for X and Z coordinates and as a 8 bits unsigned value for Y.
    // This effectively limits the maximum size of the detection space to 4096x256x4096 blocks with the starting point
    // at the center.

    // Size cuboids used to divide the detection space.
    private static final int CUBOID_BITS_X = 5;
    private static final int CUBOID_SIZE_X = 1 << CUBOID_BITS_X;

    private static final int CUBOID_BITS_Z = 5;
    private static final int CUBOID_SIZE_Z = 1 << CUBOID_BITS_Z;

    // Number of cuboids used. It should be an odd number given that the starting cuboid is at the center.
    private static final int CUBOID_COUNT_X = 21;
    private static final int CUBOID_COUNT_Z = 21;

    // At best, this allows for a craft of length = 20x32+15-1=654 blocks.
    // At worst (sign at an extreme position), it allows for a length of 10x32+15-1=334 blocks.
    // "It ought to be enough for anybody..." ;-)

    // Size of the detection space.
    private static final int SPACE_SIZE_X = CUBOID_SIZE_X * CUBOID_COUNT_X;
    private static final int SPACE_SIZE_Y = 256;
    private static final int SPACE_SIZE_Z = CUBOID_SIZE_Z * CUBOID_COUNT_Z;

    private static final int SPACE_HALF_SIZE_X = SPACE_SIZE_X / 2;
    private static final int SPACE_HALF_SIZE_Y = SPACE_SIZE_Y / 2;
    private static final int SPACE_HALF_SIZE_Z = SPACE_SIZE_Z / 2;

    // As the world height is fixed (256 blocks), we use 16 cuboids of 16 blocks each to represent a complete chunk.
    private static final int CUBOID_BITS_Y = 4;
    private static final int CUBOID_SIZE_Y = 1 << CUBOID_BITS_Y;
    private static final int CUBOID_COUNT_Y = SPACE_SIZE_Y / CUBOID_SIZE_Y;

    // Possible states stored in the detection space.
    private static final byte NOT_VISITED = 0;
    private static final byte NOT_CRAFT_BLOCK = 1;
    private static final byte CRAFT_BLOCK = 2;


    private static class IntCounter {
        int value;
        float minPercentageOrCount = Float.NaN;
        float maxPercentageOrCount = Float.NaN;
    }

    public boolean[] getBlocks() {
        return blocks;
    }

    public int getBlocksCount() {
        return totalBlocksCount;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getMinZ() {
        return minZ;
    }

    public int getSizeX() {
        return sizeX;
    }

    public int getSizeY() {
        return sizeY;
    }

    public int getSizeZ() {
        return sizeZ;
    }

    public double getDynamicFlyBlockSpeedMultiplier() {
        return dynamicFlyBlockSpeedMultiplier;
    }

    private int calculateCuboidIndex(int x, int y, int z) {
        return x + CUBOID_COUNT_X * (z + CUBOID_COUNT_Z * y);
    }

    private int calculateBlockIndex(int x, int y, int z) {
        return x + CUBOID_SIZE_X * (z + CUBOID_SIZE_Z * y);
    }

    private HashSet<IBlockData> convertBlockListToSet(Integer[] blockList) {
        final HashSet<IBlockData> blockSet = new HashSet<>(blockList.length);
        for (int value : blockList) {
            if (value > 10_000) {
                final int blockID = (value - 10_000) >> 4;
                final int blockMetaData = (value - 10_000) & 15;
                blockSet.add(Block.getByCombinedId(blockID | (blockMetaData << 12)));
            } else {
                final Block block = Block.getById(value);
                // block.t() returns the BlockStateList of block.
                // block.t().a() returns all the possible IBlockData in that BlockStateList.
                blockSet.addAll(block.t().a());
            }
        }
        return blockSet;
    }

    public boolean detect(Craft craft, MovecraftLocation startPoint, Player player, Player notificationPlayer) {
        final CraftType type = craft.getType();

        // TODO: The ID:DATA -> IBlockList conversion may be done once in the CraftType when the definition file is read.

        final HashMap<IBlockData, IntCounter> blockTypeCount = new HashMap<>();
        for (Map.Entry<ArrayList<Integer>, ArrayList<Double>> entry : type.getFlyBlocks().entrySet()) {
            final IntCounter counter = new IntCounter();
            counter.minPercentageOrCount = entry.getValue().get(0).floatValue();
            counter.maxPercentageOrCount = entry.getValue().get(1).floatValue();

            final ArrayList<Integer> idList = entry.getKey();
            for (int blockId : idList) {
                if (blockId >= 10_000) {
                    int blockType = (blockId - 10_000) >> 4;
                    int blockMetaData = (blockId - 10_000) & 15;
                    blockTypeCount.put(Block.getByCombinedId(blockType | (blockMetaData << 12)), counter);
                } else {
                    Block block = Block.getById(blockId);
                    for (IBlockData blockData : block.t().a()) {
                        blockTypeCount.put(blockData, counter);
                    }
                }
            }
        }

        HashSet<IBlockData> allowedBlocks = convertBlockListToSet(type.getAllowedBlocks());
        HashSet<IBlockData> forbiddenBlocks = convertBlockListToSet(type.getForbiddenBlocks());

        HashSet<String> forbiddenSignStrings = new HashSet<>(2 * type.getForbiddenSignStrings().length);
        for (String s : type.getForbiddenSignStrings()) {
            if (s != null)
                forbiddenSignStrings.add(s.toLowerCase());
        }

        // Real start of detection code

        final IBlockData WATER = Blocks.WATER.getBlockData();
        final IBlockData FLOWING_WATER = Blocks.FLOWING_WATER.getBlockData();
        final IBlockData WALL_SIGN = Blocks.WALL_SIGN.getBlockData();
        final IBlockData STANDING_SIGN = Blocks.STANDING_SIGN.getBlockData();
        final IBlockData CHEST = Blocks.CHEST.getBlockData();
        final IBlockData TRAPPED_CHEST = Blocks.TRAPPED_CHEST.getBlockData();

        final HashSet<IBlockData> dynamicFlyBlocks = new HashSet<>();
        if (type.getDynamicFlyBlockSpeedFactor() != 0.0) {
            dynamicFlyBlocks.addAll(Block.getById(type.getDynamicFlyBlock()).t().a());
        }

        final CraftWorld craftWorld = (CraftWorld) craft.getW();
        final net.minecraft.server.v1_10_R1.World world = craftWorld.getHandle();
        final IChunkProvider chunkProvider = world.getChunkProvider();

        byte[][] detectionSpace = new byte[CUBOID_COUNT_X * CUBOID_COUNT_Y * CUBOID_COUNT_Z][];

        final int worldStartX = startPoint.getX();
        final int worldStartY = startPoint.getY();
        final int worldStartZ = startPoint.getZ();

        final int startCuboidX = SPACE_HALF_SIZE_X >> CUBOID_BITS_X;
        final int startCuboidY = worldStartY >> CUBOID_BITS_Y;
        final int startCuboidZ = SPACE_HALF_SIZE_Z >> CUBOID_BITS_Z;
        detectionSpace[calculateCuboidIndex(startCuboidX, startCuboidY, startCuboidZ)] = new byte[CUBOID_SIZE_X * CUBOID_SIZE_Y * CUBOID_SIZE_Z];

        FastIntStack stack = new FastIntStack(1024);

        final int minSize = type.getMinSize();
        final int maxSize = type.getMaxSize();

        int minX = SPACE_HALF_SIZE_X;
        int maxX = SPACE_HALF_SIZE_X;
        int minY = worldStartY;
        int maxY = worldStartY;
        int minZ = SPACE_HALF_SIZE_Z;
        int maxZ = SPACE_HALF_SIZE_Z;
        int totalBlocksCount = 0;

        final int startPosition = SPACE_HALF_SIZE_X | (SPACE_HALF_SIZE_Z << 12) | (worldStartY << 24);
        stack.push(startPosition);

        int foundDynamicFlyBlock = 0;
        boolean waterContact = false;
        boolean fail = false;
        do {
            final int pos = stack.pop();

            final int px = pos & 0xfff;
            final int pz = (pos >>> 12) & 0xfff;
            final int py = (pos >>> 24) & 0xff;

            final int cuboidX = px >> CUBOID_BITS_X;
            final int cuboidY = py >> CUBOID_BITS_Y;
            final int cuboidZ = pz >> CUBOID_BITS_Z;

            if ((cuboidX < 0) || (cuboidX >= CUBOID_COUNT_X) || (cuboidY < 0) || (cuboidY >= CUBOID_COUNT_Y) ||
                    (cuboidZ < 0) || (cuboidZ >= CUBOID_COUNT_Z)) {
                notificationPlayer.sendMessage(ERROR_COLOR + "Detection - Blocks are too far away!");
                fail = true;
                break;
            }

            final int blockX = px & (CUBOID_SIZE_X - 1);
            final int blockY = py & (CUBOID_SIZE_Y - 1);
            final int blockZ = pz & (CUBOID_SIZE_Z - 1);

            final int cuboidIndex = calculateCuboidIndex(cuboidX, cuboidY, cuboidZ);
            byte[] cuboid = detectionSpace[cuboidIndex];
            if (cuboid == null) {
                cuboid = detectionSpace[cuboidIndex] = new byte[CUBOID_SIZE_X * CUBOID_SIZE_Y * CUBOID_SIZE_Z];
            }

            final int blockIndex = calculateBlockIndex(blockX, blockY, blockZ);
            if (cuboid[blockIndex] == NOT_VISITED) {
                final int x = worldStartX + px - SPACE_HALF_SIZE_X;
                final int z = worldStartZ + pz - SPACE_HALF_SIZE_Z;

                final IBlockData blockData = chunkProvider.getChunkAt(x >> 4, z >> 4).a(x, py, z);
                if (blockData == WATER || blockData == FLOWING_WATER) {
                    waterContact = true;
                } else if (blockData == STANDING_SIGN || blockData == WALL_SIGN) {
                    TileEntitySign sign = (TileEntitySign) world.getTileEntity(new BlockPosition(x, py, z));
                    String[] lines = new String[] { sign.lines[0].toPlainText().toLowerCase(),
                            sign.lines[1].toPlainText().toLowerCase(),
                            sign.lines[2].toPlainText().toLowerCase(),
                            sign.lines[3].toPlainText().toLowerCase() };
                    if (lines[0].equals("pilot:") && player != null) {
                        String playerName = player.getName().toLowerCase();
                        if (!lines[1].equals(playerName) && !lines[2].equals(playerName)
                                && !lines[3].equals(playerName) && !player.hasPermission("movecraft.bypasslock")) {
                            notificationPlayer.sendMessage(ERROR_COLOR + I18nSupport.getInternationalisedString(
                                    "Not one of the registered pilots on this craft"));
                            fail = true;
                        }
                    }
                    for (int i = 0; i < 4; i++) {
                        if (forbiddenSignStrings.contains(lines[i])) {
                            notificationPlayer.sendMessage(ERROR_COLOR + I18nSupport.getInternationalisedString(
                                    "Detection - Forbidden sign string found"));
                        }
                    }
                }

                if (forbiddenBlocks.contains(blockData)) {
                    notificationPlayer.sendMessage(I18nSupport.getInternationalisedString(
                            ERROR_COLOR + "Detection - Forbidden block found"));
                    fail = true;
                    break;
                } else if (!allowedBlocks.contains(blockData)) {
                    cuboid[blockIndex] = NOT_CRAFT_BLOCK;
                } else {
                    if (blockData == CHEST || blockData == TRAPPED_CHEST) {
                        if (chunkProvider.getChunkAt((x - 1) >> 4, z >> 4).a(x - 1, py, z) == blockData ||
                                chunkProvider.getChunkAt((x + 1) >> 4, z >> 4).a(x + 1, py, z) == blockData ||
                                chunkProvider.getChunkAt(x >> 4, (z - 1) >> 4).a(x, py, z - 1) == blockData ||
                                chunkProvider.getChunkAt(x >> 4, (z + 1) >> 4).a(x, py, z + 1) == blockData) {
                            notificationPlayer.sendMessage(ERROR_COLOR +
                                    I18nSupport.getInternationalisedString("Detection - ERROR: Double chest found"));
                            fail = true;
                            break;
                        }
                    }

                    if (dynamicFlyBlocks.contains(blockData)) {
                        ++foundDynamicFlyBlock;
                    }

                    cuboid[blockIndex] = CRAFT_BLOCK;
                    ++totalBlocksCount;
                    final IntCounter counter = blockTypeCount.get(blockData);
                    if (counter != null) {
                        ++counter.value;
                    }

                    if (px < minX) minX = px;
                    if (px > maxX) maxX = px;
                    if (py < minY) minY = py;
                    if (py > maxY) maxY = py;
                    if (pz < minZ) minZ = pz;
                    if (pz > maxZ) maxZ = pz;

                    if (totalBlocksCount > maxSize) {
                        notificationPlayer.sendMessage(ERROR_COLOR + String.format(
                                I18nSupport.getInternationalisedString("Detection - Craft too large"), maxSize));
                        fail = true;
                        break;
                    }

                    // 1 increment in X => 1       = 0x1
                    // 1 increment in Z => 1 << 12 = 0x1_000
                    // 1 increment in Y => 1 << 24 = 0x1_000_000

                    stack.push(pos - 1 - 0x1_000_000          ); // detect(px - 1, py - 1, pz);
                    stack.push(pos - 1                        ); // detect(px - 1, py    , pz);
                    stack.push(pos - 1 + 0x1_000_000          ); // detect(px - 1, py + 1, pz);
                    stack.push(pos + 1 - 0x1_000_000          ); // detect(px + 1, py - 1, pz);
                    stack.push(pos + 1                        ); // detect(px + 1, py    , pz);
                    stack.push(pos + 1 + 0x1_000_000          ); // detect(px + 1, py + 1, pz);
                    stack.push(pos     - 0x1_000_000 - 0x1_000); // detect(px, py - 1, pz - 1);
                    stack.push(pos                   - 0x1_000); // detect(px, py    , pz - 1);
                    stack.push(pos     + 0x1_000_000 - 0x1_000); // detect(px, py + 1, pz - 1);
                    stack.push(pos     - 0x1_000_000 + 0x1_000); // detect(px, py - 1, pz + 1);
                    stack.push(pos                   + 0x1_000); // detect(px, py    , pz + 1);
                    stack.push(pos     + 0x1_000_000 + 0x1_000); // detect(px, py + 1, pz + 1);
                    stack.push(pos     - 0x1_000_000          ); // detect(px, py - 1, pz);
                    stack.push(pos     + 0x1_000_000          ); // detect(px, py + 1, pz);
                }
            }
        } while (!fail && !stack.isEmpty());

        if (fail) {
            return false;
        }

        if (totalBlocksCount < minSize) {
            notificationPlayer.sendMessage(ERROR_COLOR + String.format(
                    I18nSupport.getInternationalisedString("Detection - Craft too small"), minSize));
            return false;
        } else if (totalBlocksCount > maxSize) {
            notificationPlayer.sendMessage(ERROR_COLOR + String.format(
                    I18nSupport.getInternationalisedString("Detection - Craft too large"), maxSize));
            return false;
        }

        if (type.getRequireWaterContact() && !waterContact) {
            notificationPlayer.sendMessage(ERROR_COLOR + I18nSupport.getInternationalisedString(
                    "Detection - Failed - Water contact required but not found"));
        }

        final String notEnoughFlyBlock = ERROR_COLOR + I18nSupport.getInternationalisedString("Not enough flyblock") +
                ": " + BLOCK_NAME_COLOR;
        final String tooMuchFlyBlock = ERROR_COLOR + I18nSupport.getInternationalisedString("Too much flyblock") +
                ": " + BLOCK_NAME_COLOR;

        final HashSet<IntCounter> processedCounters = new HashSet<>();
        boolean areAllRequirementsOk = true;

        for (final HashMap.Entry<IBlockData, IntCounter> entry : blockTypeCount.entrySet()) {
            final IntCounter counter = entry.getValue();
            if (counter != null && processedCounters.add(counter)) {
                if (!Float.isNaN(counter.minPercentageOrCount)) {
                    if (counter.minPercentageOrCount < 10_000) {
                        // counter.minPercentageOrCount has the percentage of blocks required.
                        final int numberOfBlocksRequired = (int) Math.ceil(totalBlocksCount * counter.minPercentageOrCount / 100.0);
                        if (counter.value < numberOfBlocksRequired) {
                            notificationPlayer.sendMessage(notEnoughFlyBlock + entry.getKey().getBlock().getName() +
                                    ChatColor.RESET + String.format(": %d < %d (%.2f%% of %d)",
                                    counter.value, numberOfBlocksRequired, counter.minPercentageOrCount, totalBlocksCount));
                            areAllRequirementsOk = false;
                            continue;
                        }
                    } else {
                        // counter.minPercentageOrCount has the number of blocks required (exceeded in 10,000).
                        final int numberOfBlocksRequired = (int) counter.minPercentageOrCount - 10_000;
                        if (counter.value < numberOfBlocksRequired) {
                            notificationPlayer.sendMessage(notEnoughFlyBlock + entry.getKey().getBlock().getName() +
                                    ChatColor.RESET + String.format(": %d < %d", counter.value, numberOfBlocksRequired));
                            areAllRequirementsOk = false;
                            continue;
                        }
                    }
                }

                if (!Float.isNaN(counter.maxPercentageOrCount)) {
                    if (counter.maxPercentageOrCount < 10_000) {
                        // counter.maxPercentageOrCount has the percentage of blocks required.
                        final int numberOfBlocksRequired = (int) Math.floor(totalBlocksCount * counter.maxPercentageOrCount / 100.0);
                        if (counter.value > numberOfBlocksRequired) {
                            notificationPlayer.sendMessage(tooMuchFlyBlock + entry.getKey().getBlock().getName() +
                                    ChatColor.RESET + String.format(": %d > %d (%.2f%% of %d)",
                                    counter.value, numberOfBlocksRequired, counter.maxPercentageOrCount, totalBlocksCount));
                            areAllRequirementsOk = false;
                        }
                    } else {
                        // counter.maxPercentageOrCount has the number of blocks required (exceeded in 10,000).
                        final int numberOfBlocksRequired = (int) counter.maxPercentageOrCount - 10_000;
                        if (counter.value > numberOfBlocksRequired) {
                            notificationPlayer.sendMessage(tooMuchFlyBlock + entry.getKey().getBlock().getName() +
                                    ChatColor.RESET + String.format(": %d > %d", counter.value, numberOfBlocksRequired));
                            areAllRequirementsOk = false;
                        }
                    }
                }
            }
        }

        if (!areAllRequirementsOk) {
            return false;
        }

        final int sizeX = maxX - minX + 1;
        final int sizeY = maxY - minY + 1;
        final int sizeZ = maxZ - minZ + 1;

        // TODO: Make this more efficient (lots of redundant calculations).
        final boolean[] detected = new boolean[sizeX * sizeZ * sizeY];
        int p = 0;
        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                for (int x = minX; x <= maxX; ++x) {
                    final int cuboidX = x >> CUBOID_BITS_X;
                    final int cuboidY = y >> CUBOID_BITS_Y;
                    final int cuboidZ = z >> CUBOID_BITS_Z;
                    final int cuboidIndex = calculateCuboidIndex(cuboidX, cuboidY, cuboidZ);
                    final byte[] cuboid = detectionSpace[cuboidIndex];
                    if (cuboid != null) {
                        final int blockX = x & (CUBOID_SIZE_X - 1);
                        final int blockY = y & (CUBOID_SIZE_Y - 1);
                        final int blockZ = z & (CUBOID_SIZE_Z - 1);
                        final int blockIndex = calculateBlockIndex(blockX, blockY, blockZ);
                        detected[p] = cuboid[blockIndex] == CRAFT_BLOCK;
                    }
                    ++p;
                }
            }
        }

        final int worldMinX = worldStartX + minX - SPACE_HALF_SIZE_X;
        final int worldMinY = minY;
        final int worldMinZ = worldStartZ + minZ - SPACE_HALF_SIZE_Z;

        final int worldMaxX = worldMinX + sizeX - 1;
        final int worldMaxY = worldMinY + sizeX - 1;
        final int worldMaxZ = worldMinZ + sizeX - 1;

        // TODO: Test this new version with the WorldGuard plugin installed and configured!
        final WorldGuardPlugin worldGuardPlugin = Movecraft.getInstance().getWorldGuardPlugin();
        final WGCustomFlagsPlugin wgCustomFlagsPlugin = Movecraft.getInstance().getWGCustomFlagsPlugin();
        if (worldGuardPlugin != null && wgCustomFlagsPlugin != null && Settings.WGCustomFlagsUsePilotFlag) {
            System.out.println("Starting WorldGuard validation...");
            final LocalPlayer localPlayer = worldGuardPlugin.wrapPlayer(notificationPlayer);
            final RegionManager regionManager = worldGuardPlugin.getRegionManager(craftWorld);
            for (ProtectedRegion region : regionManager.getRegions().values()) {
                final BlockVector minimumPoint = region.getMinimumPoint();
                final BlockVector maximumPoint = region.getMaximumPoint();
                // Does the region intersect with the bounding box of the craft?
                if (!(minimumPoint.getX() > worldMaxX || maximumPoint.getX() < worldMinX
                        || minimumPoint.getY() > worldMaxY || maximumPoint.getY() < worldMinY
                        || minimumPoint.getZ() > worldMaxZ || maximumPoint.getZ() < worldMinZ)) {
                    // Yes, but test only if this region has a "PILOT" restriction.
                    if (regionManager.getApplicableRegions(region).getFlag(Movecraft.FLAG_PILOT, localPlayer) != StateFlag.State.ALLOW) {
                        // Ok, now look if there really is a block inside the region...
                        for (int y = 0; y < sizeY; ++y) {
                            for (int z = 0; z < sizeZ; ++z) {
                                for (int x = 0; x < sizeX; ++x, ++p) {
                                    if (detected[p] && region.contains(worldMinX + x, worldMinY + y, worldMinZ + z)) {
                                        notificationPlayer.sendMessage(ERROR_COLOR +
                                                I18nSupport.getInternationalisedString("WGCustomFlags - Detection Failed"));
                                        return false;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        final Towny townyPlugin = Movecraft.getInstance().getTownyPlugin();
        if (townyPlugin != null && Settings.TownyBlockMoveOnSwitchPerm) {
            final TownyWorld townyWorld = TownyUtils.getTownyWorld(craftWorld);
            if (townyWorld != null && townyWorld.isUsingTowny()) {
                final TownyWorldHeightLimits worldLimits = TownyUtils.getWorldLimits(craftWorld);
            }
        }

        this.blocks = detected;
        this.totalBlocksCount = totalBlocksCount;
        this.minX = worldMinX;
        this.minY = worldMinY;
        this.minZ = worldMinZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;

        if(type.getDynamicFlyBlockSpeedFactor() != 0.0) {
            double ratio = (double)foundDynamicFlyBlock / totalBlocksCount;
            final IntCounter counter = blockTypeCount.get(dynamicFlyBlocks.iterator().next());
            if (counter != null) {
                if (counter.minPercentageOrCount < 10_000.0) {
                    ratio -= counter.minPercentageOrCount / 100.0;
                } else {
                    ratio -= (counter.minPercentageOrCount - 10_000.0) / totalBlocksCount;
                }
            }
            this.dynamicFlyBlockSpeedMultiplier = ratio * type.getDynamicFlyBlockSpeedFactor();
        }

        return true;
    }
}
