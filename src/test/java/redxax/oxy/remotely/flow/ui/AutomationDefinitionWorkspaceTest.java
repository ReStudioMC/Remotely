package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.AutomationDefinitionDraft;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.flow.data.FlowDataType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationDefinitionWorkspaceTest {
    @Test
    void refreshRequestsCoalesceWithoutInvalidatingThePublishedGeneration() {
        AutomationDefinitionDesignerScreen.RefreshGate gate = new AutomationDefinitionDesignerScreen.RefreshGate();
        long generation = gate.request();

        assertTrue(generation > 0L);
        assertEquals(0L, gate.request());
        assertEquals(0L, gate.request());
        assertTrue(gate.current(generation));
        assertTrue(gate.continueAfter(generation));
        assertFalse(gate.continueAfter(generation));
        assertTrue(gate.current(generation));
        assertTrue(gate.request() > generation);
        assertFalse(gate.current(generation));
    }

    @Test
    void serverExtensionTypesRemainValidAndExact() {
        FlowDataType extension = FlowDataType.serverType("my-ext:future-value", "Future Value", 0x123456,
            null, false, "my-ext");
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "future", "", null);
        variable.addProperty("valueType", "my-ext:future-value");

        AutomationDefinitionDesignerScreen.DefinitionValidation validation =
            AutomationDefinitionDesignerScreen.validateDefinition(AutomationDefinitionDraft.VARIABLE, "future",
                variable, List.of(FlowDataType.BOOLEAN, extension));

        assertTrue(validation.diagnostic().isBlank());
        assertEquals("my-ext:future-value", validation.document().get("valueType").getAsString());
    }

    @Test
    void scheduleTargetsKeepTypeIsolationAndRecoverTheExactSelectedId() {
        FlowManager.TypedResourceMembershipSnapshot membership = new FlowManager.TypedResourceMembershipSnapshot(
            "server", 1L, List.of(
                new FlowManager.ProjectResource("function", "shared_function", "Shared", "", 0),
                new FlowManager.ProjectResource("flow", "shared_flow", "Shared", "", 1),
                new FlowManager.ProjectResource("command", "shared_command", "Shared", "", 2)),
            Set.of("function", "flow", "command"));

        assertEquals(List.of("select_target", "shared_function"), AutomationDefinitionDesignerScreen.targetIds(
            membership, true, "function", ""));
        assertEquals(List.of("select_target", "shared_flow"), AutomationDefinitionDesignerScreen.targetIds(
            membership, true, "flow", ""));
        assertEquals(List.of("select_target", "missing_command"), AutomationDefinitionDesignerScreen.targetIds(
            membership, false, "command", "missing_command"));
    }

    @Test
    void incompleteMembershipNeverBecomesAnEmptyDefinitionListOrPartialTargetCatalog() {
        FlowManager.TypedResourceMembershipSnapshot incomplete = new FlowManager.TypedResourceMembershipSnapshot(
            "server", 1L, List.of(
                new FlowManager.ProjectResource(AutomationDefinitionDraft.TIMER, "timer", "Timer", "", 0),
                new FlowManager.ProjectResource("function", "partial", "Partial", "", 1)),
            Set.of(AutomationDefinitionDraft.VARIABLE));

        assertFalse(AutomationDefinitionDesignerScreen.completeType(incomplete, AutomationDefinitionDraft.TIMER));
        assertEquals(List.of("select_target"), AutomationDefinitionDesignerScreen.targetIds(
            incomplete, true, "function", ""));
    }

    @Test
    void onlyVariableEditingRequiresTheCanonicalValueCatalog() {
        assertTrue(AutomationDefinitionDesignerScreen.requiresValueCatalog(AutomationDefinitionDraft.VARIABLE));
        assertFalse(AutomationDefinitionDesignerScreen.requiresValueCatalog(AutomationDefinitionDraft.TIMER));
        assertFalse(AutomationDefinitionDesignerScreen.requiresValueCatalog(AutomationDefinitionDraft.SCHEDULE));

        JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "timer", "", null);
        assertTrue(AutomationDefinitionDesignerScreen.validateDefinition(AutomationDefinitionDraft.TIMER, "timer",
            timer, null).diagnostic().isBlank());
        JsonObject schedule = AutomationDefinitionDraft.create(AutomationDefinitionDraft.SCHEDULE, "schedule", "",
            new AutomationDefinitionDraft.Target("function", "target"));
        assertTrue(AutomationDefinitionDesignerScreen.validateDefinition(AutomationDefinitionDraft.SCHEDULE,
            "schedule", schedule, null).diagnostic().isBlank());
    }

    @Test
    void variableDefinitionsRemainListableUntilCanonicalValueTypesArrive() {
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "future", "", null);
        variable.addProperty("valueType", "my-ext:future-value");

        AutomationDefinitionDesignerScreen.DefinitionValidation pending =
            AutomationDefinitionDesignerScreen.validateDefinition(AutomationDefinitionDraft.VARIABLE, "future",
                variable, null);
        AutomationDefinitionDesignerScreen.DefinitionValidation rejected =
            AutomationDefinitionDesignerScreen.validateDefinition(AutomationDefinitionDraft.VARIABLE, "future",
                variable, List.of(FlowDataType.BOOLEAN));
        FlowDataType extension = FlowDataType.serverType("my-ext:future-value", "Future Value", 0x123456,
            null, false, "my-ext");
        AutomationDefinitionDesignerScreen.DefinitionValidation ready =
            AutomationDefinitionDesignerScreen.validateDefinition(AutomationDefinitionDraft.VARIABLE, "future",
                variable, List.of(FlowDataType.BOOLEAN, extension));

        assertTrue(pending.diagnostic().isBlank());
        assertFalse(rejected.diagnostic().isBlank());
        assertTrue(ready.diagnostic().isBlank());
        assertEquals("my-ext:future-value", ready.document().get("valueType").getAsString());
    }
}
