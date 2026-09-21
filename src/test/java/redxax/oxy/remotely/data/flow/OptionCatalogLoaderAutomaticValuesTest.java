package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorOptionSource;
import restudio.resync.flow.inspector.OptionQuerySchemaV1;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OptionCatalogLoaderAutomaticValuesTest {
    @Test
    void omittedGraphPinsDoNotBecomeInvalidNullCatalogDependencies() {
        TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
        TypeExpr strings = TypeExpr.list(string);
        TypeExpr optionalString = TypeExpr.optional(string);
        InspectorOptionSource source = new InspectorOptionSource(
            InspectorFieldId.of("server-runtime-data-category"),
            "Runtime Data Category",
            "Provides runtime data categories.",
            string,
            new OptionQuerySchemaV1(null, Map.of(), Map.of(
                "source", new OptionQuerySchemaV1.Field(string, false),
                "sources", new OptionQuerySchemaV1.Field(strings, false),
                "optional", new OptionQuerySchemaV1.Field(optionalString, false))),
            ContractRef.of(OwnerId.of("restudio.resync"), CapabilityId.of("options-server-runtime-data-category")),
            100,
            "server-runtime-data-category");

        OptionCatalogLoader.CoreQueryValues values = OptionCatalogLoader.automaticValues(source, "data.query_items",
            Map.of(
                "source", TypedValue.value(string, "minecraft:items"),
                "sources", TypedValue.nullValue(strings),
                "optional", TypedValue.nullValue(optionalString)));

        assertEquals("minecraft:items", values.dependencies().get("source").value());
        assertFalse(values.dependencies().containsKey("sources"));
        assertEquals(TypedValue.State.NULL, values.dependencies().get("optional").state());
        assertEquals(values.dependencies(), source.querySchema().normalize(
            new ServerId(UUID.fromString("88ea7985-329c-4296-b5b1-e8edef967d4f")), null,
            values.context(), values.dependencies()).dependencies());
    }
}
