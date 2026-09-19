package redxax.oxy.remotely.settings.server;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerSettingsMetadataParserTest {
    @Test
    void browserSafeParserLoadsCanonicalBundledMetadata() {
        ServerSettingsRegistry registry = ServerSettingsRegistry.empty();

        BundledServerSettingsRegistry.loadInto(registry, new BrowserSafeYamlServerSettingsMetadataParser());

        assertTrue(registry.snapshot().packs().size() >= 7);
        assertTrue(registry.snapshot().packs().stream().flatMap(pack -> pack.documents().stream())
                .flatMap(document -> document.fields().stream()).anyMatch(field -> field.type() == ServerSettingsFieldType.MAP));
    }

    @Test
    void parsesSafeMetadataAndImmutableFields() {
        String yaml = """
                providerId: test
                priority: 4
                packs:
                  - id: test-pack
                    name: Test Pack
                    description: Settings used by the parser test.
                    applicableSoftwareIds: [Paper*]
                    documents:
                      - path: config/test.yml
                        format: YAML
                        required: false
                        fields:
                          - id: test.enabled
                            key: enabled
                            type: BOOLEAN
                            tab: General
                            group: Test
                            name: Enabled
                            description: Turns the test feature on or off.
                            default: true
                          - id: test.mode
                            key: mode
                            type: SELECT
                            tab: General
                            group: Test
                            name: Mode
                            description: Selects the test mode.
                            default: fast
                            options: [fast, safe]
                """;

        ServerSettingsMetadata metadata = new ServerSettingsMetadataParser().parse(yaml, "test.yml");

        assertEquals("test", metadata.providerId());
        assertEquals(4, metadata.priority());
        ServerSettingsDocument document = metadata.packs().getFirst().documents().getFirst();
        assertEquals("config/test.yml", document.relativePath());
        assertEquals(2, document.fields().size());
        assertEquals(List.of("fast", "safe"), document.fields().get(1).options());
    }

    @Test
    void rejectsUnsafePathsRangesAndNonSafeTags() {
        String unsafePath = """
                providerId: test
                packs:
                  - id: test
                    name: Test
                    description: Test
                    applicableSoftwareIds: [*]
                    documents:
                      - path: ../server.yml
                        format: YAML
                        fields: []
                """;
        String unsafeTag = "!!java/object {}";

        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsMetadataParser().parse(unsafePath));
        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsMetadataParser().parse(unsafeTag));
        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsField(
                "test", "value", ServerSettingsFieldType.INTEGER, "General", "Test", "Value", "A value.", 4,
                new BigDecimal("5"), new BigDecimal("2"), List.of()
        ));
    }

    @Test
    void parsesStructuredAndDurationDefaults() {
        String yaml = """
                providerId: test
                packs:
                  - id: test
                    name: Test
                    description: Test
                    applicableSoftwareIds: [paper]
                    documents:
                      - path: config/test.yml
                        format: YAML
                        fields:
                          - id: test.duration
                            key: refresh
                            type: duration
                            tab: Software Settings
                            group: Paper World
                            name: Refresh
                            description: Refresh interval.
                            default: 12h
                          - id: test.list
                            key: values
                            type: list
                            tab: Software Settings
                            group: Paper World
                            name: Values
                            description: Values.
                            default: [stone, dirt]
                          - id: test.map
                            key: limits
                            type: map
                            tab: Software Settings
                            group: Paper World
                            name: Limits
                            description: Limits.
                            default: {stone: 1}
                """;

        ServerSettingsDocument document = new ServerSettingsMetadataParser().parse(yaml).packs().getFirst().documents().getFirst();

        assertEquals(ServerSettingsFieldType.DURATION, document.fields().get(0).type());
        assertEquals(List.of("stone", "dirt"), document.fields().get(1).defaultValue());
        assertEquals(Map.of("stone", 1), document.fields().get(2).defaultValue());
    }

    @Test
    void preservesNullableUnionsAndArbitraryNumericDefaults() {
        String yaml = """
                providerId: test
                packs:
                  - id: test
                    name: Test
                    description: Test
                    applicableSoftwareIds: [paper]
                    documents:
                      - path: config/test.yml
                        format: YAML
                        fields:
                          - id: huge
                            key: values.huge
                            type: integer
                            tab: Software Settings
                            group: Test
                            name: Huge
                            description: Huge integer.
                            default: 9223372036854775808123
                          - id: exact
                            key: values.exact
                            type: decimal
                            tab: Software Settings
                            group: Test
                            name: Exact
                            description: Exact decimal.
                            default: 1.2300
                          - id: nullable
                            key: values.nullable
                            type: text
                            tab: Software Settings
                            group: Test
                            name: Nullable
                            description: Nullable value.
                            nullable: true
                            default: null
                          - id: sentinel
                            key: values.sentinel
                            type: boolean-or-disabled
                            tab: Software Settings
                            group: Test
                            name: Sentinel
                            description: Boolean sentinel.
                            default: disabled
                          - id: dotted
                            key: values.dotted
                            type: map
                            tab: Software Settings
                            group: Test
                            name: Dotted
                            description: Dotted map key.
                            default: {"a.b": null}
                """;

        List<ServerSettingsField> fields = new ServerSettingsMetadataParser().parse(yaml).packs().getFirst().documents().getFirst().fields();

        assertEquals(new BigInteger("9223372036854775808123"), fields.get(0).defaultValue());
        assertEquals(new BigDecimal("1.2300"), fields.get(1).defaultValue());
        assertTrue(fields.get(2).nullable());
        assertTrue(fields.get(2).defaultSpecified());
        assertEquals("disabled", fields.get(3).defaultValue());
        assertEquals(Collections.singletonMap("a.b", null), fields.get(4).defaultValue());
    }

    @Test
    void rejectsInvalidDurationDefaults() {
        String yaml = """
                providerId: test
                packs:
                  - id: test
                    name: Test
                    description: Test
                    applicableSoftwareIds: [paper]
                    documents:
                      - path: config/test.yml
                        format: YAML
                        fields:
                          - id: refresh
                            key: refresh
                            type: duration
                            tab: Software Settings
                            group: Test
                            name: Refresh
                            description: Refresh interval.
                            default: sometime
                """;

        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsMetadataParser().parse(yaml));
    }

    @Test
    void parsesRecursiveCollectionSchemas() {
        String yaml = """
                providerId: test
                packs:
                  - id: test
                    name: Test
                    description: Test
                    applicableSoftwareIds: [paper]
                    documents:
                      - path: config/test.yml
                        format: YAML
                        fields:
                          - id: sequence
                            key: sequence
                            type: list
                            tab: Software Settings
                            group: Test
                            name: Sequence
                            description: Sequence.
                            default: []
                            collection:
                              mode: sequence
                              ordered: true
                              unique: true
                              value:
                                type: text
                                catalog: {source: 'server:minecraft:block'}
                                allowCustom: true
                          - id: fixed
                            key: fixed
                            type: map
                            tab: Software Settings
                            group: Test
                            name: Fixed
                            description: Fixed map.
                            default: {ambient: -1}
                            collection:
                              mode: fixed-map
                              ordered: true
                              unique: true
                              presentation:
                                itemName: Category
                                itemsName: Categories
                                detailsLabel: Category Settings
                              keys:
                                - {value: ambient, label: Ambient, description: Passive ambient creatures.}
                              value:
                                type: integer
                                min: 0
                                sentinels:
                                  - value: -1
                                    label: Use Default
                                    description: Uses the current Bukkit setting.
                                    role: inherit
                                    reference:
                                      document: bukkit.yml
                                      fields: {ambient: spawn-limits.ambient}
                          - id: nested
                            key: nested
                            type: map
                            tab: Software Settings
                            group: Test
                            name: Nested
                            description: Nested map.
                            default: {}
                            collection:
                              mode: dynamic-map
                              ordered: true
                              unique: true
                              key:
                                type: text
                                catalog: {source: 'server:minecraft:entity_type'}
                                allowCustom: true
                              value:
                                type: object
                                fields:
                                  - key: action
                                    name: Action
                                    type: select
                                    options: [DROP, KICK]
                          - id: dependent
                            key: dependent
                            type: map
                            tab: Software Settings
                            group: Test
                            name: Dependent
                            description: Dependent map.
                            default: {}
                            collection:
                              mode: dynamic-map
                              ordered: true
                              unique: true
                              key: {type: text}
                              value:
                                type: list
                                collection:
                                  mode: sequence
                                  ordered: true
                                  unique: true
                                  value:
                                    type: text
                                    catalog: {field: nested}
                """;

        List<ServerSettingsField> fields = new ServerSettingsMetadataParser().parse(yaml).packs().getFirst().documents().getFirst().fields();

        ServerSettingsField.CollectionSchema sequence = fields.get(0).collection();
        assertEquals(ServerSettingsField.CollectionMode.SEQUENCE, sequence.mode());
        assertEquals("server:minecraft:block", sequence.value().catalog().source());
        assertTrue(sequence.value().allowCustom());
        ServerSettingsField.CollectionSchema fixed = fields.get(1).collection();
        assertEquals("ambient", fixed.keys().getFirst().value());
        assertEquals("Passive ambient creatures.", fixed.keys().getFirst().description());
        assertEquals("Category", fixed.presentation().itemName());
        assertEquals("Categories", fixed.presentation().itemsName());
        assertEquals("Category Settings", fixed.presentation().detailsLabel());
        assertNull(fields.get(1).disabledValue());
        assertEquals(ServerSettingsField.SentinelRole.INHERIT, fixed.value().sentinels().getFirst().role());
        assertEquals(-1, fixed.value().sentinels().getFirst().value());
        assertEquals("Uses the current Bukkit setting.", fixed.value().sentinels().getFirst().description());
        assertEquals("bukkit.yml", fixed.value().sentinels().getFirst().reference().document());
        assertEquals("spawn-limits.ambient", fixed.value().sentinels().getFirst().reference().fieldFor("ambient"));
        ServerSettingsField.ValueSpec nested = fields.get(2).collection().value();
        assertEquals(ServerSettingsField.ValueType.OBJECT, nested.type());
        assertEquals(List.of("DROP", "KICK"), nested.fields().getFirst().value().options());
        ServerSettingsField.ValueSpec dependent = fields.get(3).collection().value().collection().value();
        assertEquals("nested", dependent.catalog().field());
    }

    @Test
    void rejectsContradictoryCollectionSchemas() {
        String yaml = """
                providerId: test
                packs:
                  - id: test
                    name: Test
                    description: Test
                    applicableSoftwareIds: [paper]
                    documents:
                      - path: config/test.yml
                        format: YAML
                        fields:
                          - id: invalid
                            key: invalid
                            type: list
                            tab: Software Settings
                            group: Test
                            name: Invalid
                            description: Invalid collection.
                            default: []
                            collection:
                              mode: sequence
                              ordered: true
                              unique: true
                              key: {type: text}
                              value: {type: text}
                """;

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ServerSettingsMetadataParser().parse(yaml));

        assertTrue(exception.getMessage().contains("Sequence collections cannot define map keys"));
    }

    @Test
    void rejectsAmbiguousAndInvalidSentinelReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettingsField.ValueReference(null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerSettingsField.ValueReference("bukkit.yml", "spawn-limits.ambient",
                        Map.of("ambient", "spawn-limits.ambient")));
        ServerSettingsField.ValueReference reference = new ServerSettingsField.ValueReference("bukkit.yml",
                "spawn-limits.ambient", Map.of());
        assertThrows(IllegalArgumentException.class, () -> new ServerSettingsField.Sentinel(-1, "Disabled",
                ServerSettingsField.SentinelRole.DISABLED, reference));
    }
}
