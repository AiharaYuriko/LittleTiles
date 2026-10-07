package team.creative.littletiles.common.math.face;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import team.creative.creativecore.common.util.math.base.Facing;
import team.creative.littletiles.common.block.entity.BETiles;
import team.creative.littletiles.common.math.box.LittleBox;

/** Prove complete coverage of this container's actual side, independent of appearance connections. */
public final class NeighborFaceCoverage {
    private NeighborFaceCoverage() {}
    public static boolean full(BETiles tiles, Facing face) {
        var rectangles = new ArrayList<BoundaryCoverage.Rect>();
        try {
            for (var pair : tiles.allTiles()) {
                if (!pair.value.doesProvideSolidFace()) continue;
                double count = pair.key.getGrid().count;
                for (var box : pair.value) {
                    if (box.getClass() != LittleBox.class || !box.isFaceAtEdge(pair.key.getGrid(), face)) continue;
                    if (box.getMin(face.one()) <= 0 && box.getMin(face.two()) <= 0
                            && box.getMax(face.one()) >= count && box.getMax(face.two()) >= count) return true;
                    rectangles.add(new BoundaryCoverage.Rect(box.getMin(face.one()) / count, box.getMin(face.two()) / count,
                        box.getMax(face.one()) / count, box.getMax(face.two()) / count));
                }
            }
            return BoundaryCoverage.full(rectangles);
        } catch (ConcurrentModificationException changed) {
            return false; // Render workers must not acquire locks on adjacent entities.
        }
    }
}
