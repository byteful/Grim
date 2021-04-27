package ac.grim.grimac.utils.collisions.blocks;

import ac.grim.grimac.utils.collisions.CollisionBox;
import ac.grim.grimac.utils.collisions.types.CollisionFactory;
import ac.grim.grimac.utils.collisions.types.SimpleCollisionBox;
import ac.grim.grimac.utils.data.ProtocolVersion;
import org.bukkit.block.data.BlockData;

// In 1.12, chests don't have data that say what type of chest they are, other than direction
public class DynamicChest implements CollisionFactory {
    @Override
    public CollisionBox fetch(ProtocolVersion version, byte data, int x, int y, int z) {
        return new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
    }

    @Override
    public CollisionBox fetch(ProtocolVersion version, BlockData block, int x, int y, int z) {
        return new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
    }
}