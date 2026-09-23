package redxax.oxy.remotely.flow.ui.studio;

import redxax.oxy.remotely.flow.data.FlowJson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import redxax.oxy.remotely.flow.data.GuiDefinition;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import redxax.oxy.remotely.flow.data.TabDefinition;

import java.util.Map;

public final class ReSyncCollaborationDocuments {
    private ReSyncCollaborationDocuments() {
    }

    public static JsonObject from(GuiDefinition value) {
        return value != null ? FlowJson.gui(value) : null;
    }

    public static JsonObject from(ScoreboardDefinition value) {
        return value != null ? FlowJson.scoreboard(value) : null;
    }

    public static JsonObject from(TabDefinition value) {
        return value != null ? FlowJson.tab(value) : null;
    }

    public static JsonObject from(JsonObject value) {
        return value != null ? value.deepCopy() : null;
    }

    public static GuiDefinition gui(JsonObject document) {
        return document != null ? FlowJson.gui(document) : null;
    }

    public static ScoreboardDefinition scoreboard(JsonObject document) {
        return document != null ? FlowJson.scoreboard(document) : null;
    }

    public static TabDefinition tab(JsonObject document) {
        return document != null ? FlowJson.tab(document) : null;
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
