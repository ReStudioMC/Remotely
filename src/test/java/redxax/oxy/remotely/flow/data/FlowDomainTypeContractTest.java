package redxax.oxy.remotely.flow.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDomainTypeContractTest {
    private static final List<String> REQUIRED_DOMAIN_TYPES = List.of(
        "permission", "permission_group", "permission_track", "permission_context",
        "instant", "duration",
        "component", "named_text_color", "rgb_color", "text_decoration", "formatting_policy",
        "item", "item_definition", "recipe_ingredient_definition", "recipe_definition", "recipe_condition",
        "gui_definition", "gui_session", "gui_element", "gui_event",
        "dialog_definition", "dialog_result", "dialog_event",
        "scoreboard_definition", "sidebar_session", "scoreboard_line", "display_slot",
        "tab_definition", "tab_application",
        "npc_definition", "npc_handle", "npc_event",
        "trade_profile", "trade_definition", "merchant", "trade_session",
        "loot_table_definition", "loot_context", "generated_loot",
        "advancement", "advancement_criterion", "advancement_tree_definition", "advancement_progress",
        "custom_content_definition", "placed_content",
        "item_attribute", "item_component", "item_modifier", "schema_value",
        "world", "location", "region", "structure", "worldgen_project", "worldgen_job", "scheduled_task",
        "player", "player_identity", "offline_player_dossier", "entity", "tracked_player_state",
        "network_node", "network_route", "network_scope", "network_variable", "network_snapshot", "network_transfer_result"
    );

    @Test
    void offlineRegistryRecognizesEveryRequiredDomainType() {
        for (String typeId : REQUIRED_DOMAIN_TYPES) {
            FlowDataType type = FlowDataType.fromString(typeId);
            assertTrue(type.isResolved(), typeId);
            assertEquals("resync:" + typeId, type.getCanonicalId(), typeId);
        }
    }

    @Test
    void functionBoundaryGenericsNormalizeWithoutSemanticLoss() {
        FlowGraph.FunctionParameter parameter = new FlowGraph.FunctionParameter("values", FlowDataType.LIST);

        assertEquals("list<any>", parameter.getTypeRef().toString());
        parameter.setTypeRef(FlowTypeRef.parse("map<string,list<player>>"));
        assertEquals("map<string,list<player>>", parameter.getTypeRef().toString());
    }

    @Test
    void editorRecognizesGenericTypeVariables() {
        FlowTypeRef variable = FlowTypeRef.parse("type:t");

        assertTrue(variable.isResolved());
        assertTrue(variable.isTypeVariable());
        assertEquals("t", variable.getTypeVariableName());
        assertEquals("list<type:t>", FlowTypeRef.parse("list<type:t>").toString());
    }

    @Test
    void resourceReferencesResolveNominalIdentitiesWithoutResolvingOrdinaryValues() {
        assertTrue(FlowTypeRef.parse("resource_reference<player>").isResolved());
        FlowTypeRef resource = FlowTypeRef.parse("resource_reference<extension:future>");
        assertTrue(resource.isResolved());
        assertEquals("resource_reference<extension:future>", resource.toString());
        assertFalse(FlowTypeRef.parse("extension:future").isResolved());
        assertFalse(FlowTypeRef.parse("list<extension:future>").isResolved());
        assertTrue(resource.isAssignableFrom(FlowTypeRef.parse(resource.toString())));
        FlowTypeRef otherOwner = FlowTypeRef.parse("resource_reference<other:future>");
        assertTrue(otherOwner.isResolved());
        assertFalse(resource.isAssignableFrom(otherOwner));
        assertFalse(otherOwner.isAssignableFrom(resource));
        for (String invalid : List.of("resource_reference<bad_owner:future>", "resource_reference<extension:>",
            "resource_reference<extension:future:extra>", "resource_reference<extension:future,builtin:gui>",
            "resource_reference<list<player>>", "resource_reference<extension:future<player>>")) {
            assertFalse(FlowTypeRef.parse(invalid).isResolved(), invalid);
        }
    }
}
