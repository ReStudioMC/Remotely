package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowDataType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationDefinitionDraftTest {
    @Test
    void createsRuntimeValidDefaultsWithoutDiscardingFolderIdentity() {
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "flag", "ops", null);
        JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "cooldown", "ops", null);
        JsonObject schedule = AutomationDefinitionDraft.create(AutomationDefinitionDraft.SCHEDULE, "nightly", "ops",
            new AutomationDefinitionDraft.Target("function", "rebuild"));

        assertEquals("boolean", variable.get("valueType").getAsString());
        assertFalse(variable.get("defaultValue").getAsBoolean());
        assertEquals("", variable.get("description").getAsString());
        assertEquals(60D, timer.get("defaultDuration").getAsDouble());
        assertEquals("seconds", timer.get("defaultUnit").getAsString());
        assertEquals("ops", schedule.get("folder").getAsString());
        assertEquals("restudio.resync", schedule.getAsJsonObject("target").getAsJsonObject("type")
            .get("ownerId").getAsString());
        assertEquals("after_delay", schedule.getAsJsonObject("timing").get("mode").getAsString());
        assertFalse(schedule.has("targetType"));
        assertFalse(schedule.has("timingMode"));

        AutomationDefinitionDraft.put(AutomationDefinitionDraft.TIMER, timer, "description", "Controls cooldowns");
        assertEquals("Controls cooldowns", AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.TIMER, "cooldown", timer).document().get("description").getAsString());
    }

    @Test
    void keepsFlatDocumentsFlatAndNestedDocumentsNested() {
        JsonObject flat = flatSchedule();
        flat.addProperty("extension", "kept");
        AutomationDefinitionDraft.put(AutomationDefinitionDraft.SCHEDULE, flat, "targetId", "second");
        AutomationDefinitionDraft.put(AutomationDefinitionDraft.SCHEDULE, flat, "duration", "30");

        assertEquals("second", flat.get("targetId").getAsString());
        assertEquals(30D, flat.get("duration").getAsDouble());
        assertFalse(flat.has("target"));
        assertFalse(flat.has("timing"));
        assertEquals("kept", flat.get("extension").getAsString());

        JsonObject nested = AutomationDefinitionDraft.create(AutomationDefinitionDraft.SCHEDULE, "nested", "",
            new AutomationDefinitionDraft.Target("flow", "first"));
        nested.getAsJsonObject("target").addProperty("extension", true);
        AutomationDefinitionDraft.put(AutomationDefinitionDraft.SCHEDULE, nested, "targetId", "second");
        AutomationDefinitionDraft.put(AutomationDefinitionDraft.SCHEDULE, nested, "duration", "30");

        assertEquals("second", nested.getAsJsonObject("target").get("id").getAsString());
        assertEquals(30D, nested.getAsJsonObject("timing").get("duration").getAsDouble());
        assertFalse(nested.has("targetId"));
        assertFalse(nested.has("duration"));
        assertTrue(nested.getAsJsonObject("target").get("extension").getAsBoolean());
    }

    @Test
    void keepsNumericTextPlainAndDoesNotConvertValuesWhenUnitsChange() {
        JsonObject timer = AutomationDefinitionDraft.create(AutomationDefinitionDraft.TIMER, "precise", "", null);

        AutomationDefinitionDraft.put(AutomationDefinitionDraft.TIMER, timer, "defaultDuration",
            "0.000000123400");
        AutomationDefinitionDraft.put(AutomationDefinitionDraft.TIMER, timer, "defaultUnit", "minutes");

        assertEquals("0.000000123400", timer.get("defaultDuration").toString());
        assertEquals("0.000000123400", AutomationDefinitionDraft.text(AutomationDefinitionDraft.TIMER, timer,
            "defaultDuration"));
        assertEquals("minutes", timer.get("defaultUnit").getAsString());
        JsonObject prepared = AutomationDefinitionDraft.prepare(AutomationDefinitionDraft.TIMER, "precise", timer)
            .document();
        assertEquals("0.000000123400", prepared.get("defaultDuration").toString());

        AutomationDefinitionDraft.put(AutomationDefinitionDraft.TIMER, timer, "tickInterval", "1e-7");
        assertEquals("0.0000001", timer.get("tickInterval").toString());

        timer.addProperty("defaultDuration", 1.0E-7D);
        assertFalse(AutomationDefinitionDraft.text(AutomationDefinitionDraft.TIMER, timer, "defaultDuration")
            .toLowerCase().contains("e"));
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "number", "",
            null);
        variable.addProperty("defaultValue", 1.0E-7D);
        assertFalse(AutomationDefinitionDraft.text(AutomationDefinitionDraft.VARIABLE, variable, "defaultValue")
            .toLowerCase().contains("e"));
    }

    @Test
    void updatesBothRepresentationsAndRejectsConflicts() {
        JsonObject both = flatSchedule();
        JsonObject target = new JsonObject();
        JsonObject type = new JsonObject();
        type.addProperty("ownerId", AutomationDefinitionDraft.CORE_OWNER);
        type.addProperty("localId", "function");
        target.add("type", type);
        target.addProperty("id", "first");
        both.add("target", target);
        JsonObject timing = new JsonObject();
        timing.addProperty("mode", "after_delay");
        timing.addProperty("duration", 10D);
        both.add("timing", timing);

        AutomationDefinitionDraft.put(AutomationDefinitionDraft.SCHEDULE, both, "targetType", "command");
        AutomationDefinitionDraft.put(AutomationDefinitionDraft.SCHEDULE, both, "duration", "45");

        assertEquals("command", both.get("targetType").getAsString());
        assertEquals("command", both.getAsJsonObject("target").getAsJsonObject("type").get("localId").getAsString());
        assertEquals(45D, both.get("duration").getAsDouble());
        assertEquals(45D, both.getAsJsonObject("timing").get("duration").getAsDouble());
        both.getAsJsonObject("target").addProperty("id", "conflict");
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.SCHEDULE, "schedule", both));
    }

    @Test
    void validatesExactIdentityRuntimeTimingAndCoreTarget() {
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "flag", "", null);
        variable.addProperty("valueType", "unknown_value_type");
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "flag", variable, List.of(FlowDataType.BOOLEAN)));

        JsonObject repeating = flatSchedule();
        repeating.addProperty("timingMode", "repeating");
        repeating.addProperty("duration", 0D);
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.SCHEDULE, "schedule", repeating));

        JsonObject wrongOwner = AutomationDefinitionDraft.create(AutomationDefinitionDraft.SCHEDULE, "owned", "",
            new AutomationDefinitionDraft.Target("function", "target"));
        wrongOwner.getAsJsonObject("target").getAsJsonObject("type").addProperty("ownerId", "extension");
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.SCHEDULE, "owned", wrongOwner));
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "different", variable));
    }

    @Test
    void targetIdsRemainExactAndTimingAliasesCannotBypassValidation() {
        JsonObject conflictingTarget = flatSchedule();
        conflictingTarget.addProperty("targetId", "CaseSensitive");
        JsonObject nestedTarget = new JsonObject();
        nestedTarget.addProperty("type", "function");
        nestedTarget.addProperty("id", "casesensitive");
        conflictingTarget.add("target", nestedTarget);
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.SCHEDULE, "schedule", conflictingTarget));

        JsonObject repeating = flatSchedule();
        repeating.addProperty("timingMode", "Repeating");
        repeating.addProperty("duration", 0D);
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.SCHEDULE, "schedule", repeating));
    }

    @Test
    void variableDefaultValueMustMatchItsSelectedType() {
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "flag", "", null);
        variable.addProperty("defaultValue", "false");

        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "flag", variable));
        variable.addProperty("defaultValue", false);
        assertFalse(AutomationDefinitionDraft.prepare(AutomationDefinitionDraft.VARIABLE, "flag", variable)
            .document().get("defaultValue").getAsBoolean());
    }

    @Test
    void variableTypesUseTheSharedGenericTypeGrammar() {
        JsonObject list = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "names", "", null);
        list.addProperty("valueType", "list<extension:future>");
        list.add("defaultValue", new JsonArray());

        assertEquals("list<extension:future>", AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "names", list).document().get("valueType").getAsString());

        JsonObject malformed = list.deepCopy();
        malformed.addProperty("valueType", "list<string");
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "names", malformed));
    }

    @Test
    void serverCatalogOffersAndValidatesExtensionAndConcreteGenericTypes() {
        FlowDataType extension = FlowDataType.serverType("my-ext:future-value", "Future Value", 0x123456,
            null, false, "extension");
        List<FlowDataType> catalog = List.of(FlowDataType.ANY, FlowDataType.BOOLEAN, FlowDataType.STRING,
            FlowDataType.LIST, FlowDataType.MAP, FlowDataType.OPTIONAL, FlowDataType.RESULT,
            FlowDataType.JOB_REFERENCE, extension);

        List<String> options = AutomationDefinitionDraft.valueTypeOptions(catalog);
        assertTrue(options.contains("my-ext:future-value"));
        assertTrue(options.contains("list<my-ext:future-value>"));
        assertTrue(options.contains("map<string,my-ext:future-value>"));
        assertTrue(options.contains("result<my-ext:future-value,any>"));
        assertTrue(options.contains("job_reference<my-ext:future-value>"));
        assertFalse(options.contains("list"));
        assertFalse(options.contains("result"));
        assertFalse(options.contains("job_reference"));
        assertFalse(options.stream().anyMatch(value -> value.contains("my_ext")));

        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "future", "", null);
        variable.addProperty("valueType", "my-ext:future-value");
        assertEquals("my-ext:future-value", AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "future", variable, catalog).document().get("valueType").getAsString());

        variable.addProperty("valueType", "list<my-ext:future-value>");
        variable.add("defaultValue", new JsonArray());
        assertEquals("list<my-ext:future-value>", AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "future", variable, catalog).document().get("valueType").getAsString());

        variable.addProperty("valueType", "list<my_ext:future_value>");
        assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "future", variable, catalog));
    }

    @Test
    void persistedTypeValidationEnforcesGenericShapeWithoutProcessRegistryMembership() {
        JsonObject variable = AutomationDefinitionDraft.create(AutomationDefinitionDraft.VARIABLE, "future", "", null);
        variable.addProperty("valueType", "result<my-ext:future-value,string>");
        assertEquals("result<my-ext:future-value,string>", AutomationDefinitionDraft.prepare(
            AutomationDefinitionDraft.VARIABLE, "future", variable).document().get("valueType").getAsString());

        for (String expression : List.of("list", "optional", "job_reference", "map<string>",
            "result<my-ext:future-value>", "boolean<string>", "job_reference<string,boolean>", "tuple")) {
            JsonObject malformed = variable.deepCopy();
            malformed.addProperty("valueType", expression);
            assertThrows(IllegalArgumentException.class, () -> AutomationDefinitionDraft.prepare(
                AutomationDefinitionDraft.VARIABLE, "future", malformed), expression);
        }
    }

    private JsonObject flatSchedule() {
        JsonObject value = new JsonObject();
        value.addProperty("id", "schedule");
        value.addProperty("targetType", "function");
        value.addProperty("targetId", "first");
        value.addProperty("timingMode", "after_delay");
        value.addProperty("duration", 10D);
        return value;
    }
}
