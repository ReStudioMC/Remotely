package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;

public final class ResourceDesigners {
    private ResourceDesigners() {
    }

    public static FocusedJsonResourceDesignerScreen create(StudioScreen owner, String typeId, String id,
                                                            JsonObject resource, String serverId, Object parent) {
        ReSyncResourceType type = ReSyncResourceType.byTypeId(typeId);
        if (type == null) {
            return new ManagedResourceDesignerScreen(owner, typeId, id, resource, serverId, parent);
        }
        return switch (type) {
            case RECIPE_DEFINITION -> new RecipeDesignerScreen(owner, id, resource, serverId, parent);
            case MOTD_PROFILE -> new MotdDesignerScreen(owner, id, resource, serverId, parent);
            case MESSAGE_RULE -> new MessageRuleDesignerScreen(owner, id, resource, serverId, parent);
            case TEXT_TEMPLATE -> new TextTemplateDesignerScreen(owner, id, resource, serverId, parent);
            case CHAT -> new ChatDesignerScreen(owner, id, resource, serverId, parent);
            case TRADE_PROFILE -> new TradeDesignerScreen(owner, id, resource, serverId, parent);
            case NPC_DEFINITION -> new NpcDesignerScreen(owner, id, resource, serverId, parent);
            case LOOT_TABLE -> new LootTableDesignerScreen(owner, id, resource, serverId, parent);
            default -> new ManagedResourceDesignerScreen(owner, typeId, id, resource, serverId, parent);
        };
    }
}
