package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.runtime.RuntimeBindingDescriptor;
import restudio.resync.flow.runtime.RuntimeBindingManifest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReSyncProductionCatalogInteractionTest {
    private static final String SERVER = "e65887a4-ea27-4c55-bae2-e1c8d92da433";
    private static final ContentHash CONTENT = ContentHash.of("4f335d707ce8cd6c60c06e3296d92a46b8ee66feb22df533389b9f1c8ffa556b");
    private static final ContentHash MANIFEST = ContentHash.of("8f0915fc00c8e51c98234f096c81166045e8c1e3bd4ef49f269d2cfda9d423c8");

    @Test
    void hashlessManifestContentRequiresMatchingIndependentHashes() {
        RuntimeBindingManifest original = RuntimeBindingManifest.create(List.of(), List.of(), Map.of(), List.of(),
            Map.of("catalogEvidence", List.of("a".repeat(600_000), "b".repeat(600_000))));
        byte[] content = original.canonicalForm().getBytes(StandardCharsets.UTF_8);
        assertTrue(content.length > CanonicalLimits.standard().opaqueSubtreeBytes());
        ContentHash expected = original.bindingManifestHash();
        assertEquals(expected, manifestContent(content, expected, expected).bindingManifestHash());
        Map<String, Object> altered = sourceMap(CanonicalJson.parse(content, CanonicalLimits.catalog()));
        altered.put("version", 2);
        byte[] alteredContent = CanonicalJson.canonicalize(altered).getBytes(StandardCharsets.UTF_8);

        assertThrows(AssertionError.class, () -> manifestContent(alteredContent, expected, expected));
        assertThrows(AssertionError.class, () -> manifestContent(content, expected, ContentHash.of("0".repeat(64))));
    }

    @Test
    void sourcePolicyRejectsAccidentalHidingOfAVisibleNode() {
        CatalogCachePublication.Entry entry = policyEntry("string", "text");
        Map<String, Object> source = sourceMap(CanonicalJson.parseOpaque(entry.data().canonicalBytes()));
        SourceExpectation expected = sourceExpectation(source);
        ReSyncGenericDescriptorProjection.Projection descriptor = ReSyncGenericDescriptorProjection.open(entry,
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();
        ReSyncGenericWidgetCapabilities.Conversion converted = ReSyncGenericWidgetCapabilities.convert(descriptor);
        assertNoUnexpected(policyErrors(source, expected, descriptor, converted));
        NodeDefinition original = converted.widget().orElseThrow().definition();
        NodeDefinition hidden = new NodeDefinition.Builder(original.getId(), "Probe", NodeDefinition.NodeCategory.FLOW)
            .owner(original.getOwner()).hidden(true).input(original.getInputs().getFirst()).build();
        ReSyncGenericWidgetCapabilities.Conversion changed = new ReSyncGenericWidgetCapabilities.Conversion(
            Optional.of(new ReSyncGenericWidgetCapabilities.WidgetDefinition(hidden, false, entry.definitionKey().canonicalText())), "");

        List<String> errors = policyErrors(source, expected, descriptor, changed);
        assertTrue(errors.contains("hidden_drift:expected=false"));
        assertThrows(AssertionError.class, () -> assertNoUnexpected(errors));
    }

    @Test
    void sourcePolicyRejectsFalseUnsupportedClassificationForASupportedLiteral() {
        CatalogCachePublication.Entry entry = policyEntry("string", "text");
        Map<String, Object> source = sourceMap(CanonicalJson.parseOpaque(entry.data().canonicalBytes()));
        SourceExpectation expected = sourceExpectation(source);
        ReSyncGenericDescriptorProjection.Projection original = ReSyncGenericDescriptorProjection.open(entry,
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();
        ReSyncGenericDescriptorProjection.Field field = original.fields().getFirst();
        ReSyncGenericDescriptorProjection.Field unsupported = new ReSyncGenericDescriptorProjection.Field(field.id(), field.title(),
            field.description(), ReSyncGenericDescriptorProjection.EditorKind.UNSUPPORTED, field.typeExpression(), field.capability(),
            false, "A plausible nonblank unsupported reason");
        ReSyncGenericDescriptorProjection.Projection changed = new ReSyncGenericDescriptorProjection.Projection(original.definitionKey(),
            original.revision(), ReSyncGenericDescriptorProjection.Status.READ_ONLY, true, original.descriptor(), original.canonicalData(),
            List.of(unsupported), original.pins(), original.requiredCapabilities(), original.unknown(), "Unsupported editor");

        List<String> errors = policyErrors(source, expected, changed, ReSyncGenericWidgetCapabilities.convert(changed));
        assertTrue(errors.contains("literal_editor_drift:pin:value"));
        assertTrue(errors.contains("descriptor_read_only_drift:expected=false"));
        assertThrows(AssertionError.class, () -> assertNoUnexpected(errors));
    }

    @Test
    void sourcePolicyRetainsAGenuinelyUnsupportedDeclaredLiteralAsReadOnly() {
        CatalogCachePublication.Entry entry = policyEntry("player", "text");
        Map<String, Object> source = sourceMap(CanonicalJson.parseOpaque(entry.data().canonicalBytes()));
        SourceExpectation expected = sourceExpectation(source);
        ReSyncGenericDescriptorProjection.Projection descriptor = ReSyncGenericDescriptorProjection.open(entry,
            ReSyncGenericDescriptorProjection.ClientCapabilities.primitive()).orElseThrow();

        assertTrue(expected.readOnly());
        assertTrue(descriptor.readOnly());
        assertNoUnexpected(policyErrors(source, expected, descriptor, ReSyncGenericWidgetCapabilities.convert(descriptor)));
    }

    private static CatalogCachePublication.Entry policyEntry(String type, String widget) {
        Map<String, Object> source = Map.of("id", "policy.probe", "pins", List.of(Map.of(
            "id", "value", "direction", "input", "requirement", "required",
            "type", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", type), "arguments", List.of()),
            "editor", Map.of("ownerId", "restudio.resync", "localId", "generic-editor"),
            "presentation", Map.of("widget", widget))), "metadata", Map.of("authoredSource", Map.of("hidden", false)));
        return CatalogCachePublication.Entry.present(ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("policy.probe")),
            1, CatalogCacheState.ACTIVE, Set.of(), false, CatalogCacheOpaque.of(CanonicalJson.canonicalize(source).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiedProductionCatalogAccountsForEveryPaletteOutcome() throws Exception {
        boolean enabled = List.of("publication", "publication.sha256", "manifest", "manifest.sha256").stream()
            .anyMatch(key -> configured(key) != null);
        assumeTrue(enabled, "Supply the paired production publication and manifest paths and SHA256 values to run this gate");
        byte[] bytes = evidence("publication");
        CatalogCachePublication publication = new CatalogCachePublicationCodec().decodeBytes(bytes);
        RuntimeBindingManifest manifest = manifestContent(evidence("manifest"), MANIFEST, publication.key().bindingManifestHash());
        assertEquals(CatalogCachePublication.Kind.FULL, publication.kind());
        assertEquals(SERVER, publication.serverId().canonicalText());
        assertEquals(55L, publication.key().catalogGeneration());
        assertEquals(CONTENT, publication.key().snapshotChecksum());
        assertEquals(MANIFEST, publication.key().bindingManifestHash());
        assertTrue(manifest.matches(publication.catalogBinding()));
        assertNotNull(publication.authoringPublication(), "The proof must include the real authoring projection");
        assertEquals(publication.catalogBinding(), publication.authoringPublication().binding());
        assertEquals(CatalogAuthoringPublication.Section.values().length, publication.authoringPublication().sections().size());
        assertTrue(publication.authoringPublication().sections().stream().allMatch(section -> section.present() && section.acknowledged()));
        assertEquals(1322, publication.entries().size());
        assertEquals(1243L, publication.entries().stream().filter(entry -> entry.definitionKey().ownerId().equals("restudio.resync")).count());
        assertEquals(79L, publication.entries().stream().filter(entry -> entry.definitionKey().ownerId().equals("worldgen")).count());
        Map<String, RuntimeBindingDescriptor> bindings = manifest.bindings().stream()
            .collect(Collectors.toMap(binding -> binding.key().canonical(), binding -> binding));
        ReSyncCatalogPublicationProjection acknowledged = new ReSyncCatalogPublicationProjection(ServerId.parseCanonicalText(SERVER));
        assertTrue(acknowledged.acknowledgeActiveKey(publication.key()));
        assertTrue(acknowledged.apply(publication, bytes), "The real publication boundary rejected the verified evidence");

        List<String> unexpected = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> families = new TreeMap<>();
        Map<ContractRef<NodeId>, Outcome> outcomes = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : publication.entries()) {
            Outcome outcome;
            try {
                outcome = inspect(entry, bindings, unexpected);
            } catch (RuntimeException exception) {
                String identity = entry.definitionKey().canonicalText();
                Map<String, Object> details = new LinkedHashMap<>(Map.of("identity", identity,
                    "exception", exception.getClass().getSimpleName(), "reason", String.valueOf(exception.getMessage())));
                outcome = rejected("inspection_failed", identity, details, unexpected);
            }
            assertTrue(outcomes.putIfAbsent(entry.definitionKey(), outcome) == null, "Duplicate production identity");
            counts.merge(outcome.kind(), 1, Integer::sum);
            families.merge(outcome.family() + "/" + outcome.kind(), 1, Integer::sum);
            System.out.println("CATALOG_PROJECTION_ENTRY " + CanonicalJson.canonicalize(outcome.details()));
        }
        System.out.println("CATALOG_PROJECTION_SUMMARY " + CanonicalJson.canonicalize(Map.of(
            "catalogChecksum", CONTENT.canonicalText(), "bindingManifestHash", MANIFEST.canonicalText(),
            "definitions", outcomes.size(), "outcomes", counts, "families", families,
            "unexpectedFilters", unexpected, "deployedRuntimeManifestProven", false)));
        assertEquals(1322, counts.values().stream().mapToInt(Integer::intValue).sum());
        ReSyncTypedInteractionProjection interaction = ReSyncTypedInteractionProjection.from(acknowledged.active().orElseThrow());
        assertEquals(outcomes.keySet(), interaction.descriptors().keySet());
        Set<String> palette = interaction.palette(false).definitions().stream()
            .map(definition -> definition.getOwner() + "/" + definition.getId()).collect(Collectors.toSet());
        Set<String> expectedPalette = outcomes.entrySet().stream().filter(entry -> entry.getValue().expectedVisible())
            .map(entry -> entry.getKey().canonicalText()).collect(Collectors.toSet());
        assertEquals(expectedPalette, palette, "The aggregate palette must match every per-entry classification");
        assertReportedFamilies(interaction);
        assertNodeAuthoring(publication);
        assertNoUnexpected(unexpected);
    }

    private static void assertNodeAuthoring(CatalogCachePublication publication) {
        ServerResourceLocator resource = new ServerResourceLocator(ServerId.parseCanonicalText(SERVER),
            ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("flow")), "catalog-role-proof");
        CatalogBinding binding = new CatalogBinding(publication.key().catalogGeneration(), CONTENT, MANIFEST);
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.FLOW,
            resource, binding, new CatalogVersion(1, 0), publication.entries(), 0);
        CoreGraphAuthoringAdapter adapter = new CoreGraphAuthoringAdapter();
        List<GraphNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> test : Map.of("menu_create", "menu_id", "command_get", "command", "list_add", "value").entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put(test.getValue(), null);
            GraphNode node = adapter.createNode(NodeInstanceId.deterministic(test.getKey()),
                new FlowNode("restudio.resync:" + test.getKey(), 0, 0, values), context);
            CatalogCachePublication.Entry entry = publication.entries().stream()
                .filter(candidate -> candidate.definitionKey().equals(node.definition())).findFirst().orElseThrow();
            Map<String, Object> descriptor = sourceMap(CanonicalJson.parseOpaque(entry.data().canonicalBytes()));
            Map<?, ?> pin = list(descriptor.get("pins")).stream().map(ReSyncProductionCatalogInteractionTest::map)
                .filter(candidate -> test.getValue().equals(candidate.get("id"))).findFirst().orElseThrow();
            assertEquals(CanonicalJson.canonicalize(pin.get("type")),
                node.values().get(PinId.of(test.getValue())).value().type().canonicalJson());
            nodes.add(node);
        }
        GraphDocument document = new GraphDocument(resource, 0, binding, nodes, List.of());
        GraphDocument decoded = GraphDocumentCodec.INSTANCE.decode(GraphDocumentCodec.INSTANCE.encode(document));
        assertEquals(GraphDocumentCodec.INSTANCE.encodeText(document), GraphDocumentCodec.INSTANCE.encodeText(decoded));
        assertEquals(3, decoded.nodes().size());
    }

    private static Outcome inspect(CatalogCachePublication.Entry entry, Map<String, RuntimeBindingDescriptor> bindings,
                                   List<String> unexpected) {
        String identity = entry.definitionKey().canonicalText();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("identity", identity);
        details.put("publishedState", entry.state().name());
        details.put("requiredCapabilities", entry.requiredCapabilities().stream().map(ContractRef::canonicalText).sorted().toList());
        var opened = ReSyncGenericDescriptorProjection.open(entry, ReSyncGenericDescriptorProjection.ClientCapabilities.primitive());
        if (opened.isEmpty()) {
            return rejected("descriptor_missing", identity, details, unexpected);
        }
        ReSyncGenericDescriptorProjection.Projection descriptor = opened.orElseThrow();
        Map<String, Object> source = sourceMap(CanonicalJson.parseOpaque(entry.data().canonicalBytes()));
        SourceExpectation expected = sourceExpectation(source);
        String family = text(source.get("domain")) + "/" + text(source.get("family"));
        details.put("family", family);
        details.put("lifecycle", text(source.get("lifecycle")));
        details.put("descriptorStatus", descriptor.status().name());
        details.put("descriptorReason", descriptor.reason());
        details.put("sourceHidden", expected.hidden());
        details.put("sourceReadOnly", expected.readOnly());
        details.put("hiddenReason", text(authored(source).get("hiddenReason")));
        details.put("inputPins", descriptor.pins().stream().filter(pin -> pin.direction() == ReSyncGenericDescriptorProjection.Direction.INPUT).count());
        details.put("outputPins", descriptor.pins().stream().filter(pin -> pin.direction() == ReSyncGenericDescriptorProjection.Direction.OUTPUT).count());
        List<ReSyncGenericDescriptorProjection.Field> unsupported = descriptor.fields().stream().filter(field -> !field.editable()).toList();
        details.put("unsupportedFields", unsupported.stream().map(field -> Map.of("id", field.id(), "type", field.typeExpression(),
            "capability", field.capability(), "reason", field.reason())).toList());
        verifyRuntime(identity, source, bindings, unexpected);
        ReSyncGenericWidgetCapabilities.Conversion conversion = ReSyncGenericWidgetCapabilities.convert(descriptor);
        List<String> policyErrors = policyErrors(source, expected, descriptor, conversion);
        policyErrors.forEach(error -> unexpected.add(identity + ": " + error));
        details.put("sourcePolicyErrors", policyErrors);
        details.put("expectedFields", expected.fields().entrySet().stream().map(field -> Map.of(
            "id", field.getKey(), "type", field.getValue().type(), "capability", field.getValue().capability(),
            "editable", field.getValue().editable())).toList());
        details.put("widgetReason", conversion.reason());
        details.put("rejectedPins", rejectedPins(source, conversion.reason()));
        String kind;
        if (entry.tombstone() || entry.opaque() || entry.state() != CatalogCacheState.ACTIVE) {
            kind = "publication_unavailable";
        } else if (!policyErrors.isEmpty()) {
            kind = "source_policy_drift";
        } else if (expected.readOnly()) {
            kind = "unsupported_editor";
        } else if (descriptor.status() != ReSyncGenericDescriptorProjection.Status.ACTIVE || descriptor.readOnly()) {
            kind = "descriptor_unavailable";
        } else if (conversion.widget().isEmpty()) {
            kind = "widget_rejected";
        } else if (expected.hidden()) {
            kind = "hidden";
        } else {
            kind = "visible";
        }
        details.put("outcome", kind);
        if (!Set.of("visible", "hidden", "unsupported_editor").contains(kind)) {
            unexpected.add(identity + ": " + kind + ": " + descriptor.reason() + ": " + conversion.reason());
        } else if (conversion.widget().isEmpty()) {
            unexpected.add(identity + ": unsupported_editor_with_widget_loss: " + conversion.reason());
        }
        return new Outcome(kind, family, details, entry.state() == CatalogCacheState.ACTIVE && !entry.opaque()
            && !entry.tombstone() && !expected.readOnly() && !expected.hidden());
    }

    private static Outcome rejected(String kind, String identity, Map<String, Object> details, List<String> unexpected) {
        details.put("outcome", kind);
        unexpected.add(identity + ": " + kind);
        return new Outcome(kind, "unknown", details, false);
    }

    private static List<String> policyErrors(Map<String, Object> source, SourceExpectation expected,
                                             ReSyncGenericDescriptorProjection.Projection descriptor,
                                             ReSyncGenericWidgetCapabilities.Conversion conversion) {
        List<String> errors = new ArrayList<>();
        if (!source.equals(descriptor.descriptor())) {
            errors.add("descriptor_source_drift");
        }
        Map<String, String> sourcePins = new TreeMap<>();
        for (Object value : list(source.get("pins"))) {
            Map<?, ?> pin = map(value);
            sourcePins.put(text(pin.get("direction")) + ":" + text(pin.get("id")), CanonicalJson.canonicalize(pin.get("type")));
        }
        Map<String, String> projectedPins = descriptor.pins().stream().collect(Collectors.toMap(
            pin -> pin.direction().name().toLowerCase(Locale.ROOT) + ":" + pin.id(), ReSyncGenericDescriptorProjection.Pin::typeExpression));
        if (!sourcePins.equals(projectedPins)) {
            errors.add("projected_pin_contract_drift");
        }
        Map<String, ReSyncGenericDescriptorProjection.Field> actual = new LinkedHashMap<>();
        for (ReSyncGenericDescriptorProjection.Field field : descriptor.fields()) {
            if (actual.putIfAbsent(field.id(), field) != null) {
                errors.add("duplicate_literal_field:" + field.id());
            }
        }
        if (!actual.keySet().equals(expected.fields().keySet())) {
            errors.add("literal_field_set_drift:expected=" + expected.fields().keySet() + ":actual=" + actual.keySet());
        }
        expected.fields().forEach((id, field) -> {
            ReSyncGenericDescriptorProjection.Field projected = actual.get(id);
            if (field.capability().equals("/") || field.type().equals("null")) {
                errors.add("literal_source_contract_missing:" + id);
            }
            if (projected != null && (!field.type().equals(projected.typeExpression())
                || !field.capability().equals(projected.capability()) || field.editable() != projected.editable())) {
                errors.add("literal_editor_drift:" + id);
            }
        });
        if (expected.readOnly() != descriptor.readOnly()) {
            errors.add("descriptor_read_only_drift:expected=" + expected.readOnly());
        }
        conversion.widget().ifPresent(widget -> {
            if (widget.definition().isHidden() != expected.hidden()) {
                errors.add("hidden_drift:expected=" + expected.hidden());
            }
            if (widget.readOnly() != expected.readOnly()) {
                errors.add("widget_read_only_drift:expected=" + expected.readOnly());
            }
        });
        return errors;
    }

    private static SourceExpectation sourceExpectation(Map<String, Object> source) {
        Map<String, LiteralContract> fields = new LinkedHashMap<>();
        for (Object value : list(source.get("pins"))) {
            Map<?, ?> pin = map(value);
            if (!"input".equals(pin.get("direction"))) {
                continue;
            }
            String type = literalType(pin.get("type"));
            if ("execution".equals(type)) {
                continue;
            }
            Map<?, ?> presentation = map(pin.get("presentation"));
            String widget = text(presentation.get("widget")).toLowerCase(Locale.ROOT);
            String editor = reference(pin.get("editor"));
            boolean explicitLiteral = pin.get("default") != null || pin.get("optionSource") != null
                || !widget.isBlank() && !widget.equals("auto") || !list(presentation.get("options")).isEmpty();
            if (isScalar(type) || type.equals("resource_reference") || explicitLiteral
                || !editor.equals("/") && !editor.endsWith("/generic-editor")) {
                fields.put("pin:" + text(pin.get("id")), literalContract(pin.get("type"), pin.get("editor"), widget));
            }
        }
        for (Object section : list(map(source.get("inspector")).get("sections"))) {
            for (Object row : list(map(section).get("rows"))) {
                inspectorFields(list(map(row).get("fields")), "field:", fields);
            }
        }
        return new SourceExpectation(sourceHidden(source), Map.copyOf(fields));
    }

    private static void inspectorFields(List<?> values, String prefix, Map<String, LiteralContract> fields) {
        for (Object value : values) {
            Map<?, ?> field = map(value);
            String id = prefix + text(field.get("id"));
            fields.put(id, literalContract(field.get("valueType"), field.get("editor"), "auto"));
            inspectorFields(list(field.get("children")), id + "/", fields);
            inspectorFields(list(field.get("fields")), id + "/", fields);
            if (field.get("element") instanceof Map<?, ?> element) {
                inspectorFields(List.of(element), id + "/element/", fields);
            }
        }
    }

    private static LiteralContract literalContract(Object typeValue, Object editorValue, String widget) {
        String type = literalType(typeValue);
        if (type.equals("resource_reference")) {
            type = "string";
        }
        String editor = text(map(editorValue).get("localId")).toLowerCase(Locale.ROOT);
        Set<String> editors;
        Set<String> widgets;
        switch (type) {
            case "string", "text" -> {
                editors = Set.of("text", "text-editor", "string", "string-editor", "generic-editor");
                widgets = Set.of("text", "multiline", "dropdown", "searchable_list", "color");
            }
            case "number", "integer", "float", "double" -> {
                editors = Set.of("number", "number-editor", "numeric", "numeric-editor", "generic-editor");
                widgets = Set.of("number", "slider", "dropdown", "searchable_list");
            }
            case "boolean", "bool" -> {
                editors = Set.of("boolean", "boolean-editor", "bool", "bool-editor", "toggle", "toggle-editor", "generic-editor");
                widgets = Set.of("toggle", "dropdown", "searchable_list");
            }
            case "json", "json_object" -> {
                editors = Set.of("json", "json-editor", "generic-editor");
                widgets = Set.of("multiline");
            }
            default -> {
                editors = Set.of();
                widgets = Set.of();
            }
        }
        boolean editable = !text(map(editorValue).get("ownerId")).isBlank() && editors.contains(editor)
            && (widget.isBlank() || widget.equals("auto") || widgets.contains(widget));
        return new LiteralContract(CanonicalJson.canonicalize(typeValue), reference(editorValue), editable);
    }

    private static String literalType(Object value) {
        Map<?, ?> type = map(value);
        String kind = text(type.get("kind"));
        if (kind.equals("resource")) {
            return "resource_reference";
        }
        Map<?, ?> named = map(type.get("type"));
        return kind.equals("named") && "builtin".equals(named.get("ownerId")) && list(type.get("arguments")).isEmpty()
            ? text(named.get("localId")) : kind.equals("named") ? "" : kind;
    }

    private static boolean isScalar(String type) {
        return Set.of("string", "text", "number", "integer", "float", "double", "boolean", "bool", "json", "json_object").contains(type);
    }

    private static boolean sourceHidden(Map<String, Object> source) {
        Map<?, ?> metadata = map(source.get("metadata"));
        Object hidden = source.containsKey("hidden") ? source.get("hidden")
            : metadata.containsKey("hidden") ? metadata.get("hidden") : authored(source).get("hidden");
        if (hidden != null && !(hidden instanceof Boolean)) {
            throw new IllegalArgumentException("Source hidden flag must be boolean");
        }
        return Boolean.TRUE.equals(hidden);
    }

    private static Map<String, Object> sourceMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        map(value).forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static void assertNoUnexpected(List<String> unexpected) {
        assertTrue(unexpected.isEmpty(), () -> "Production filters require review: " + String.join("; ", unexpected));
    }

    private static void verifyRuntime(String identity, Map<String, Object> descriptor,
                                      Map<String, RuntimeBindingDescriptor> bindings, List<String> unexpected) {
        Map<?, ?> handler = map(descriptor.get("handler"));
        String key = reference(handler.get("capability")) + "#" + reference(handler.get("operation"));
        RuntimeBindingDescriptor binding = bindings.get(key);
        if (binding == null) {
            unexpected.add(identity + ": runtime_binding_missing: " + key);
            return;
        }
        Map<String, String> actual = new TreeMap<>();
        for (Object value : list(descriptor.get("pins"))) {
            Map<?, ?> pin = map(value);
            actual.put(text(pin.get("direction")) + ":" + text(pin.get("id")), CanonicalJson.canonicalize(pin.get("type")));
        }
        Map<String, String> expected = binding.pins().stream().collect(Collectors.toMap(
            pin -> pin.direction().name().toLowerCase(Locale.ROOT) + ":" + pin.id().canonicalText(), pin -> pin.type().canonicalJson()));
        if (!actual.equals(expected)) {
            unexpected.add(identity + ": runtime_endpoint_contract_mismatch: " + key);
        }
    }

    private static List<Map<String, Object>> rejectedPins(Map<String, Object> descriptor, String reason) {
        List<Map<String, Object>> rejected = new ArrayList<>();
        for (Object value : list(descriptor.get("pins"))) {
            Map<?, ?> pin = map(value);
            if (reason.endsWith(":" + text(pin.get("id")))) {
                Map<?, ?> presentation = map(pin.get("presentation"));
                rejected.add(Map.of("id", text(pin.get("id")), "type", pin.get("type"),
                    "widget", text(presentation.get("widget")), "visibleWhen", map(presentation.get("visibleWhen"))));
            }
        }
        return rejected;
    }

    private static void assertReportedFamilies(ReSyncTypedInteractionProjection interaction) {
        for (String id : List.of("event.block.break", "event.command", "player.properties", "entity.properties", "inventory.properties", "world_get_all",
            "menu_create", "list_add", "command_get")) {
            ContractRef<NodeId> key = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of(id));
            assertFalse(interaction.descriptor(key).orElseThrow().readOnly(), id);
            assertTrue(interaction.palette(false).definition(id).isPresent(), id);
        }
        ContractRef<NodeId> command = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("event.command"));
        NodeDefinition commandDefinition = interaction.widgetDefinition(command).orElseThrow().definition();
        Map<String, String> commandTypes = Map.of("flow", "execution", "event.player", "player", "event.command", "string",
            "event.is_cancelled", "boolean", "event.bound_command", "string", "event.command_label", "string",
            "event.args", "string", "event.args_list", "list<string>", "event.args_count", "number", "event.is_console", "boolean");
        assertEquals(3, ((Number) interaction.descriptor(command).orElseThrow().descriptor().get("schemaVersion")).intValue());
        assertEquals(10, commandDefinition.getOutputs().size());
        assertEquals(commandTypes.keySet(), commandDefinition.getOutputs().stream()
            .map(NodeDefinition.PinDefinition::getName).collect(Collectors.toSet()));
        commandTypes.forEach((pin, type) ->
            assertEquals(type, interaction.pinType(command, pin, false).orElseThrow().toString(), pin));
        ContractRef<NodeId> player = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("player.properties"));
        ContractRef<NodeId> entity = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("entity.properties"));
        ContractRef<NodeId> inventory = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("inventory.properties"));
        assertEquals("itemstack", interaction.pinType(player, "item_in_hand", false).orElseThrow().toString());
        assertEquals("list<entity>", interaction.pinType(entity, "output_passengers", false).orElseThrow().toString());
        assertEquals("list<itemstack>", interaction.pinType(inventory, "items", false).orElseThrow().toString());
        ContractRef<NodeId> menu = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("menu_create"));
        ContractRef<NodeId> list = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("list_add"));
        ContractRef<NodeId> commandGet = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("command_get"));
        assertEquals("resource_reference<builtin:gui>", interaction.pinType(menu, "menu_id", true).orElseThrow().toString());
        assertEquals("resource_reference<builtin:command>", interaction.pinType(commandGet, "command", true).orElseThrow().toString());
        assertEquals("result<command_definition,any>", interaction.pinType(commandGet, "result", false).orElseThrow().toString());
        assertEquals("list<type:t>", interaction.pinType(list, "list", true).orElseThrow().toString());
        assertEquals("type:t", interaction.pinType(list, "value", true).orElseThrow().toString());
        assertEquals("list<type:t>", interaction.pinType(list, "output_list", false).orElseThrow().toString());
        assertTrue(interaction.descriptor(list).orElseThrow().fields().isEmpty());
        for (String id : List.of("function_start", "function_end")) {
            ContractRef<NodeId> identity = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of(id));
            NodeDefinition definition = interaction.widgetDefinition(identity).orElseThrow().definition();
            assertTrue(definition.isHidden());
            assertTrue(definition.getInputs().isEmpty());
            assertTrue(definition.getOutputs().isEmpty());
            assertTrue(interaction.palette(false).definition(id).isEmpty());
            assertTrue(interaction.functionBoundary(identity).isEmpty());
        }
        ContractRef<NodeId> weather = ContractRef.of(OwnerId.of("restudio.resync"), NodeId.of("world_set_weather"));
        assertTrue(interaction.widgetDefinition(weather).orElseThrow().definition().isHidden());
        assertTrue(interaction.palette(false).definition("world_set_weather").isEmpty());
    }

    private static byte[] evidence(String kind) throws Exception {
        String location = configured(kind);
        String expectedHash = configured(kind + ".sha256");
        assertNotNull(location, "Missing explicit " + kind + " evidence path");
        assertNotNull(expectedHash, "Missing explicit " + kind + " SHA256");
        assertTrue(expectedHash.matches("[0-9a-fA-F]{64}"), "Evidence SHA256 must be exact hexadecimal");
        Path path = Path.of(location);
        assertTrue(path.isAbsolute() && Files.isRegularFile(path), "Evidence must be an explicit absolute regular file");
        assertTrue(Files.size(path) > 0 && Files.size(path) <= CanonicalLimits.catalog().inputBytes(), "Evidence exceeds the codec limit");
        byte[] bytes = Files.readAllBytes(path);
        assertEquals(expectedHash.toLowerCase(Locale.ROOT), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        return bytes;
    }

    private static RuntimeBindingManifest manifestContent(byte[] content, ContentHash expected, ContentHash publicationHash) {
        Map<String, Object> source = sourceMap(CanonicalJson.parse(content, CanonicalLimits.catalog()));
        assertFalse(source.containsKey("bindingManifestHash"), "The frozen exporter emits manifest content, not wire form");
        ContentHash computed = ContentHash.of(CanonicalJson.sha256("runtime-manifest", source, CanonicalLimits.catalog()));
        assertEquals(expected, computed, "Manifest content differs from the independently pinned binding hash");
        assertEquals(publicationHash, computed, "Manifest content differs from the verified publication binding");
        Map<String, Object> wire = new LinkedHashMap<>(source);
        wire.put("bindingManifestHash", computed.canonicalText());
        RuntimeBindingManifest decoded = RuntimeBindingManifest.fromCanonical(wire);
        assertArrayEquals(content, decoded.canonicalForm().getBytes(StandardCharsets.UTF_8), "Decoded manifest content must remain exact");
        return decoded;
    }

    private static String configured(String key) {
        String value = System.getProperty("resync.catalog.projection." + key);
        if (value == null || value.isBlank()) {
            value = System.getenv("RESYNC_CATALOG_PROJECTION_" + key.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static String reference(Object value) {
        Map<?, ?> reference = map(value);
        return text(reference.get("ownerId")) + "/" + text(reference.get("localId"));
    }

    private static Map<?, ?> authored(Map<String, Object> descriptor) {
        return map(map(descriptor.get("metadata")).get("authoredSource"));
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    private record Outcome(String kind, String family, Map<String, Object> details, boolean expectedVisible) {
    }

    private record LiteralContract(String type, String capability, boolean editable) {
    }

    private record SourceExpectation(boolean hidden, Map<String, LiteralContract> fields) {
        private boolean readOnly() {
            return fields.values().stream().anyMatch(field -> !field.editable());
        }
    }
}
