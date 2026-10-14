package redxax.oxy.remotely.data.player.management;

import redxax.oxy.remotely.data.playerdata.PlayerItem;

import java.util.List;
import java.util.UUID;

public record PlayerOperation(String operationId, Type type, UUID playerId, String text, boolean flag, long baseRevision, List<InventoryEdit> inventoryEdits) {
    public enum Type {
        KICK,
        BAN,
        UNBAN,
        OP,
        DEOP,
        WHITELIST,
        UNWHITELIST,
        COMMAND,
        INVENTORY_EDIT
    }

    public record InventoryEdit(String slot, PlayerItem item) {
    }
}
