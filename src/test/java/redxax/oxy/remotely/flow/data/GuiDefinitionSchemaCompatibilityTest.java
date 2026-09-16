package redxax.oxy.remotely.flow.data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.resource.ResourcePayloadCodecs;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class GuiDefinitionSchemaCompatibilityTest {
    private static final Gson GSON = new Gson();

    @Test
    void defaultGuiMatchesServerSchemaCanonicalPayload() {
        GuiDefinition gui = defaultGui("gui");
        JsonObject actual = JsonParser.parseString(FlowSerializer.serializeGui(gui)).getAsJsonObject();
        JsonObject expected = JsonParser.parseString("""
            {
              "id":"gui",
              "enabled":true,
              "title":"Main Menu",
              "rows":3,
              "extendToPlayerInventory":false,
              "elements":[{
                "slots":[13],
                "visual":{
                  "material":"DIAMOND",
                  "lore":[],
                  "name":"<yellow>Main Button</yellow>",
                  "enchanted":false,
                  "itemFlags":[]
                },
                "flowId":"main_flow"
              }],
              "updateIntervalTicks":0
            }
            """).getAsJsonObject();

        assertEquals(expected, actual);
        assertEquals(canonicalHash(expected), canonicalHash(actual));
    }

    @Test
    void nonDefaultGuiFieldsSurviveRoundTripAndCopy() {
        GuiDefinition source = new GuiDefinition("rich", "Rich Menu", 6);
        source.setEnabled(false);
        source.setExtendToPlayerInventory(true);
        source.setClickSound("custom.click");
        source.setOpenFlowId("open_flow");
        source.setCloseFlowId("close_flow");
        source.setUpdateIntervalTicks(40);
        source.setUpdateFlowId("update_flow");

        Visual visual = new Visual("PLAYER_HEAD", "<gold>Title</gold>");
        visual.setModelData(17);
        visual.setPresetReference("preset");
        visual.setLore(List.of("<gray>Line one</gray>", "<gray>Line two</gray>"));
        visual.setEnchanted(true);
        visual.setItemFlags(List.of("HIDE_ATTRIBUTES", "HIDE_ENCHANTS"));
        visual.setHeadTexture("texture-value");

        GuiElement element = new GuiElement(List.of(1, 2), visual, "button_flow");
        element.setOpenGuiId("child_gui");
        element.setCommand("say hello");
        JsonObject action = new JsonObject();
        action.addProperty("type", "open");
        action.addProperty("target", "child_gui");
        element.setAction(action);
        source.getElements().add(element);

        GuiDefinition copy = source.copy();
        GuiDefinition roundTrip = FlowSerializer.deserializeGui(FlowSerializer.serializeGui(source));
        JsonObject sourceJson = JsonParser.parseString(FlowSerializer.serializeGui(source)).getAsJsonObject();

        assertEquals(sourceJson, JsonParser.parseString(FlowSerializer.serializeGui(copy)).getAsJsonObject());
        assertEquals(sourceJson, JsonParser.parseString(FlowSerializer.serializeGui(roundTrip)).getAsJsonObject());
        assertNotSame(source.getElements(), copy.getElements());
        assertNotSame(source.getElements().getFirst(), copy.getElements().getFirst());
        assertNotSame(source.getElements().getFirst().getVisual(), copy.getElements().getFirst().getVisual());
        assertNotSame(source.getElements().getFirst().getAction(), copy.getElements().getFirst().getAction());
        assertEquals("custom.click", roundTrip.getClickSound());
        assertEquals("open_flow", roundTrip.getOpenFlowId());
        assertEquals("close_flow", roundTrip.getCloseFlowId());
        assertEquals(40, roundTrip.getUpdateIntervalTicks());
        assertEquals("update_flow", roundTrip.getUpdateFlowId());
        assertEquals(true, roundTrip.getElements().getFirst().getVisual().isEnchanted());
        assertEquals(List.of("HIDE_ATTRIBUTES", "HIDE_ENCHANTS"),
            roundTrip.getElements().getFirst().getVisual().getItemFlags());
        assertEquals("texture-value", roundTrip.getElements().getFirst().getVisual().getHeadTexture());
    }

    private GuiDefinition defaultGui(String id) {
        GuiDefinition gui = new GuiDefinition();
        gui.setId(id);
        gui.setTitle("Main Menu");
        gui.setRows(3);
        Visual visual = new Visual("DIAMOND", "<yellow>Main Button</yellow>");
        GuiElement button = new GuiElement();
        button.getSlots().add(13);
        button.setVisual(visual);
        button.setFlowId("main_flow");
        gui.getElements().add(button);
        return gui;
    }

    private String canonicalHash(JsonObject payload) {
        Map<String, Object> value = GSON.fromJson(payload, Map.class);
        return ResourcePayloadCodecs.json().hashPayload(value).canonicalText();
    }
}
