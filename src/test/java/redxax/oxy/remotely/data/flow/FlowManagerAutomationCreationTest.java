package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerAutomationCreationTest {
    private static final ServerId SERVER = ServerId.parseCanonicalText("11111111-1111-4111-8111-111111111111");

    @Test
    void variableCreationReplaysThePreparedValuesWithoutReplacingThemWithDefaults() {
        String template = """
            {"id":"greeting","displayName":"Greeting","valueType":"string","scope":"server",
             "persistent":true,"defaultValue":"Welcome","extension":{"revision":"9007199254740993"}}
            """;
        JsonObject created = FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.VARIABLE_DEFINITION,
            "greeting", template);

        assertNotNull(created);
        assertEquals("Welcome", created.get("defaultValue").getAsString());
        assertTrue(created.get("persistent").getAsBoolean());
        assertEquals("9007199254740993", created.getAsJsonObject("extension").get("revision").getAsString());
        created.addProperty("defaultValue", "Changed After Admission");
        JsonObject recovered = FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.VARIABLE_DEFINITION,
            "greeting", template);
        assertNotNull(recovered);
        assertEquals("Welcome", recovered.get("defaultValue").getAsString());
    }

    @Test
    void timerCreationRetainsDurationUnitAndTickSettings() {
        String template = """
            {"id":"countdown","displayName":"Countdown","scope":"server","persistent":false,
             "defaultDuration":2.5,"defaultUnit":"minutes","tickInterval":0.25}
            """;
        JsonObject created = FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.TIMER_DEFINITION,
            "countdown", template);

        assertNotNull(created);
        assertEquals(2.5D, created.get("defaultDuration").getAsDouble());
        assertEquals("minutes", created.get("defaultUnit").getAsString());
        assertEquals(0.25D, created.get("tickInterval").getAsDouble());
    }

    @Test
    void preparedExtensionTypesKeepTheirExactIdentityThroughCreationReplay() {
        for (String type : new String[]{"extension:future", "my-ext:future-value", "list<my-ext:future-value>"}) {
            JsonObject template = new JsonObject();
            template.addProperty("id", "custom");
            template.addProperty("valueType", type);
            template.addProperty("description", "Exact Published Type");
            JsonObject created = FlowManager.buildAutomationResourceForCreation(
                ReSyncResourceType.VARIABLE_DEFINITION, "custom", template.toString());

            assertNotNull(created);
            assertEquals(type, created.get("valueType").getAsString());
            assertEquals("Exact Published Type", created.get("description").getAsString());
            assertEquals(created, FlowManager.buildAutomationResourceForCreation(
                ReSyncResourceType.VARIABLE_DEFINITION, "custom", template.toString()));
        }
    }

    @Test
    void scheduleCreationRequiresAnOwnedTypedTargetAndPreservesItsTiming() {
        String template = """
            {"id":"announce","target":{"type":{"ownerId":"restudio.resync","localId":"function"},
             "id":"broadcast"},"timingMode":"repeating","timing":{"duration":5,"unit":"minutes",
             "initialDelay":2},"scope":"server","persistent":true,"overlapPolicy":"queue"}
            """;
        JsonObject created = FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.SCHEDULE_DEFINITION,
            "announce", template);

        assertNotNull(created);
        assertEquals("broadcast", created.getAsJsonObject("target").get("id").getAsString());
        assertEquals("function", created.getAsJsonObject("target").getAsJsonObject("type").get("localId").getAsString());
        assertEquals(5D, created.getAsJsonObject("timing").get("duration").getAsDouble());
        assertEquals("queue", created.get("overlapPolicy").getAsString());
        assertNull(FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.SCHEDULE_DEFINITION,
            "announce", template.replace("restudio.resync", "other.owner")));
        assertNull(FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.SCHEDULE_DEFINITION,
            "announce", template.replace("broadcast", "")));
        JsonObject missingTypeIdentity = JsonParser.parseString(template).getAsJsonObject();
        missingTypeIdentity.getAsJsonObject("target").getAsJsonObject("type").remove("ownerId");
        assertNull(FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.SCHEDULE_DEFINITION,
            "announce", missingTypeIdentity.toString()));
        missingTypeIdentity.getAsJsonObject("target").getAsJsonObject("type").addProperty("ownerId", "restudio.resync");
        missingTypeIdentity.getAsJsonObject("target").getAsJsonObject("type").remove("localId");
        assertNull(FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.SCHEDULE_DEFINITION,
            "announce", missingTypeIdentity.toString()));
    }

    @Test
    void invalidOrMissingInitialDocumentsNeverFallBackToAnInvalidDefault() {
        for (ReSyncResourceType type : new ReSyncResourceType[]{ReSyncResourceType.VARIABLE_DEFINITION,
            ReSyncResourceType.TIMER_DEFINITION, ReSyncResourceType.SCHEDULE_DEFINITION}) {
            assertNull(FlowManager.buildAutomationResourceForCreation(type, "sample", null));
            assertNull(FlowManager.buildAutomationResourceForCreation(type, "sample", ""));
            assertNull(FlowManager.buildAutomationResourceForCreation(type, "sample", "[]"));
            assertNull(FlowManager.buildAutomationResourceForCreation(type, "sample", "{"));
        }
        assertNull(FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.SCHEDULE_DEFINITION, "sample",
            "{\"id\":\"sample\",\"targetType\":\"function\",\"targetId\":\"\"}"));
        JsonObject variable = JsonParser.parseString("{\"id\":\"different\",\"valueType\":\"boolean\",\"defaultValue\":false}")
            .getAsJsonObject();
        assertNull(FlowManager.buildAutomationResourceForCreation(ReSyncResourceType.VARIABLE_DEFINITION,
            "sample", variable.toString()));
    }

    @Test
    void scheduleTargetIdentityKeepsServerOwnerAndGraphTypeExact() {
        for (String type : new String[]{"flow", "function", "command"}) {
            ServerResourceLocator target = target("restudio.resync", type);
            assertTrue(FlowManager.scheduleTargetIdentityMatches(SERVER.canonicalText(), target));
            assertFalse(FlowManager.scheduleTargetIdentityMatches("22222222-2222-2222-2222-222222222222", target));
        }
        assertFalse(FlowManager.scheduleTargetIdentityMatches(SERVER.canonicalText(), target("other.owner", "flow")));
        assertFalse(FlowManager.scheduleTargetIdentityMatches(SERVER.canonicalText(), target("restudio.resync", "timer_definition")));
        assertFalse(FlowManager.scheduleTargetIdentityMatches(SERVER.canonicalText(), null));
    }

    private static ServerResourceLocator target(String owner, String type) {
        return new ServerResourceLocator(SERVER, ContractRef.of(OwnerId.of(owner), ResourceTypeId.of(type)), "broadcast");
    }
}
