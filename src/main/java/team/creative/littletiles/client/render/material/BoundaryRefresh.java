package team.creative.littletiles.client.render.material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import team.creative.littletiles.common.block.entity.BETiles;

/** Defer until loaded entities are installed in their chunk; coalesce packet bursts. */
public final class BoundaryRefresh {
    private static final Map<Level, Set<BlockPos>> PENDING = new HashMap<>();
    private BoundaryRefresh() {}

    public static synchronized void request(BETiles tiles) {
        Level level = tiles.getLevel();
        if (level != null && level.isClientSide)
            PENDING.computeIfAbsent(level, ignored -> new HashSet<>()).add(tiles.getBlockPos().immutable());
    }

    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;
        Set<BlockPos> changed;
        synchronized (BoundaryRefresh.class) {
            changed = PENDING.remove(level);
            PENDING.clear(); // Discard updates from disconnected worlds.
        }
        if (level == null || changed == null) return;
        Set<BlockPos> affected = new HashSet<>();
        for (BlockPos pos : changed) {
            affected.add(pos);
            for (Direction direction : Direction.values()) affected.add(pos.relative(direction));
        }
        for (BlockPos pos : affected) {
            if (!level.hasChunkAt(pos)) continue;
            // LT edits change boundary geometry even when no block state changes.
            // Refresh every material, and retain the neighbor-dirty indication
            // instead of merely dropping boxes that can reuse old face flags.
            if (level.getBlockEntity(pos) instanceof BETiles tiles)
                tiles.render.onNeighbourChanged();
            client.levelRenderer.setBlocksDirty(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1,
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }
}
