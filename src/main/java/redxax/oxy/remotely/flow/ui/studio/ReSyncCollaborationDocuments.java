package redxax.oxy.remotely.flow.ui.studio;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;

import java.util.Map;

public final class ReSyncCollaborationDocuments {
    private static final Gson GSON = new Gson();

    private ReSyncCollaborationDocuments() {
    }

    public static JsonObject from(Object value) {
        JsonElement json = GSON.toJsonTree(value);
        return json != null && json.isJsonObject() ? json.getAsJsonObject() : null;
    }

    public static <T> T to(JsonObject document, Class<T> type) {
        return document != null ? GSON.fromJson(document, type) : null;
    }

    public static void copy(JsonObject target, JsonObject source) {
        if (target == null || source == null) {
            return;
        }
        target.keySet().clear();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    public static void copy(GuiDefinition target, GuiDefinition source) {
        if (target == null || source == null) {
            return;
        }
        target.setId(source.getId());
        target.setEnabled(source.isEnabled());
        target.setTitle(source.getTitle());
        target.setRows(source.getRows());
        target.setExtendToPlayerInventory(source.isExtendToPlayerInventory());
        target.setElements(source.getElements());
    }

    public static void copy(ScoreboardDefinition target, ScoreboardDefinition source) {
        if (target == null || source == null) {
            return;
        }
        target.setId(source.getId());
        target.setEnabled(source.isEnabled());
        target.setTitle(source.getTitle());
        target.setObjectiveId(source.getObjectiveId());
        target.setDisplaySlot(source.getDisplaySlot());
        target.setLines(source.getLines());
    }

    public static void copy(TabDefinition target, TabDefinition source) {
        if (target == null || source == null) {
            return;
        }
        target.setId(source.getId());
        target.setEnabled(source.isEnabled());
        target.setHeader(source.getHeader());
        target.setEntryFormat(source.getEntryFormat());
        target.setFooter(source.getFooter());
    }
}
