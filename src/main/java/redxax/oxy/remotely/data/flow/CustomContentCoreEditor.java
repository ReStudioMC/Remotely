package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.FlowConnection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.CanonicalHash;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogAuthoringPublicationCodec;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.graph.GraphDocument;
import restudio.resync.flow.graph.GraphDocumentCodec;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CustomContentCoreEditor {
    public static final String CORE_GRAPH = "contentCoreGraph";
    public static final String CONTENT_PROPERTIES = "contentProperties";
    public static final String CONTENT_INPUTS = "contentInputs";
    public static final String CATALOG_COMPATIBILITY = "contentCatalogCompatibility";

    private CustomContentCoreEditor() {
    }

    public static CoreGraphEditorSession prepare(CustomContentDefinition acknowledged, ServerResourceLocator resource,
                                                 long revision, CatalogAuthoringPublication publication,
                                                 Collection<CatalogCachePublication.Entry> entries) {
        Objects.requireNonNull(acknowledged, "Acknowledged custom content is required");
        Objects.requireNonNull(publication, "Published authoring catalog is required");
        requireIdentity(acknowledged, resource);
        if (revision < 0L || !publication.compatible()) {
            throw new IllegalArgumentException("Custom content authority is unavailable");
        }
        FlowGraph stored = Objects.requireNonNull(acknowledged.getGraph(), "Custom content graph is unavailable");
        JsonElement embedded = stored.getOpaqueProperties().get(CORE_GRAPH);
        GraphDocument document;
        if (embedded != null) {
            document = GraphDocumentCodec.INSTANCE.decode(CanonicalCodec.decodePermissive(embedded.toString()));
            if (!document.resource().equals(resource) || document.revision() > revision) {
                throw new IllegalArgumentException("Custom content graph authority does not match its aggregate");
            }
            if (!document.catalogBinding().equals(publication.binding())) {
                if (document.catalogBinding().generation() >= publication.binding().generation()
                    || !Objects.equals(document.unknown().get(CATALOG_COMPATIBILITY), compatibility(document, publication, entries))) {
                    throw new IllegalArgumentException("Content Requires Server Catalog Migration");
                }
            }
            document = new GraphDocument(document.schemaVersion(), document.resource(), revision, publication.binding(),
                document.requiredCapabilities(), document.nodes(), document.connections(), document.passthroughs(), document.variables(),
                document.functions(), document.unknown());
            requireCatalogVersions(document, entries);
        } else {
            FlowGraph graph = FlowSerializer.deserialize(FlowSerializer.serialize(stored));
            graph.setId(resource.id());
            graph.setResourceType(ReSyncResourceType.CUSTOM_CONTENT.typeId());
            graph.setFunction(false);
            migrateContentRoot(graph, entries);
            CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(
                ReSyncResourceType.CUSTOM_CONTENT, resource, publication.binding(), publication.contractVersion(), entries, revision);
            CoreGraphAuthoringAdapter.Result converted = new CoreGraphAuthoringAdapter().convert(graph, context);
            if (!converted.lossless()) {
                throw new IllegalArgumentException(converted.reason());
            }
            document = converted.graphDocument();
            Set<ContractRef<CapabilityId>> required = new LinkedHashSet<>(document.requiredCapabilities());
            Set<ContractRef<NodeId>> definitions = document.nodes().stream().map(node -> node.definition()).collect(Collectors.toSet());
            entries.stream().filter(entry -> definitions.contains(entry.definitionKey()))
                .forEach(entry -> required.addAll(entry.requiredCapabilities()));
            document = new GraphDocument(document.schemaVersion(), resource, revision, document.catalogBinding(), required,
                document.nodes(), document.connections(), document.passthroughs(), document.variables(), document.functions(), document.unknown());
        }
        Set<ContractRef<CapabilityId>> capabilities = publication.capabilities().stream()
            .filter(CatalogAuthoringPublication.Entry::editable).map(CatalogAuthoringPublication.Entry::reference)
            .collect(Collectors.toUnmodifiableSet());
        if (!capabilities.containsAll(document.requiredCapabilities())) {
            throw new IllegalArgumentException("Custom content requires unavailable authoring capabilities");
        }
        document = withCompatibility(document, publication, entries);
        return new CoreGraphEditorSession(document, CatalogCachePublicationCodec.authoringPublicationChecksum(publication),
            capabilities, publication.advertisedEditCapabilities());
    }

    public static CustomContentDefinition materialize(CoreGraphEditorSession session, CustomContentDefinition acknowledged,
                                                       CatalogAuthoringPublication publication,
                                                       Collection<CatalogCachePublication.Entry> entries) {
        Objects.requireNonNull(session, "Custom content editor session is required");
        requireIdentity(acknowledged, session.resource());
        if (!session.catalogBinding().equals(publication.binding())) {
            throw new IllegalArgumentException("Content Catalog Changed Before Save");
        }
        GraphDocument document = withCompatibility(session.graphDocument(), publication, entries);
        CoreGraphUiProjection.ProjectionResult result = new CoreGraphUiProjection().projectEditorSession(session);
        if (!result.complete()) {
            throw new IllegalArgumentException(result.compactReason());
        }
        FlowGraph graph = result.graph();
        String flowId = acknowledged.getFlowId();
        if (flowId == null || flowId.isBlank()) {
            flowId = CustomContentGraphAdapter.contentFlowId(acknowledged.getType(), acknowledged.getId());
        }
        graph.setId(flowId);
        graph.setResourceType(ReSyncResourceType.CUSTOM_CONTENT.typeId());
        graph.setEnabled(acknowledged.isEnabled());
        CustomContentGraphAdapter.setContentProperty(graph, "content_id", acknowledged.getId());
        graph.getOpaqueProperties().put(CORE_GRAPH,
            JsonParser.parseString(GraphDocumentCodec.INSTANCE.encode(document).canonicalText()));
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
        if (content == null || !acknowledged.getId().equals(content.getId())) {
            throw new IllegalArgumentException("Custom content identity cannot change through its graph editor");
        }
        content.setVersion(acknowledged.getVersion());
        return content;
    }

    public static CoreGraphAuthoringAdapter.Result convert(FlowGraph graph, CoreGraphEditorSession session,
                                                           Collection<CatalogCachePublication.Entry> entries) {
        CoreGraphAuthoringAdapter.Context context = CoreGraphAuthoringAdapter.Context.strict(ReSyncResourceType.CUSTOM_CONTENT,
            session.resource(), session.catalogBinding(), session.graphDocument().schemaVersion(), entries, session.revision());
        CoreGraphAuthoringAdapter.Result result = new CoreGraphAuthoringAdapter().convert(graph,
            JsonParser.parseString(session.canonicalPayloadJson()).getAsJsonObject(), context);
        if (!result.lossless()) return result;
        GraphDocument converted = result.graphDocument();
        GraphDocument current = session.graphDocument();
        return CoreGraphAuthoringAdapter.Result.accepted(new GraphDocument(converted.schemaVersion(), converted.resource(),
            converted.revision(), converted.catalogBinding(), converted.requiredCapabilities(), converted.nodes(), converted.connections(),
            converted.passthroughs(), current.variables(), current.functions(), converted.unknown()));
    }

    public static CoreGraphEditorSession rebase(CoreGraphEditorSession authoritative, GraphDocument baseline,
                                                GraphDocument draft, GraphDocument acknowledgedSave) {
        GraphDocument merged = CoreGraphWorkspacePatch.rebase(acknowledgedSave != null ? acknowledgedSave : baseline,
            draft, authoritative.graphDocument());
        authoritative.replaceGraph(merged);
        return authoritative;
    }

    private static void requireIdentity(CustomContentDefinition content, ServerResourceLocator resource) {
        if (content == null || resource == null || !ReSyncResourceType.CUSTOM_CONTENT.typeId().equals(resource.resourceType().value())
            || !Objects.equals(content.getId(), resource.id())) {
            throw new IllegalArgumentException("Custom content requires its exact typed aggregate identity");
        }
    }

    private static GraphDocument withCompatibility(GraphDocument document, CatalogAuthoringPublication publication,
                                                   Collection<CatalogCachePublication.Entry> entries) {
        return new GraphDocument(document.schemaVersion(), document.resource(), document.revision(), document.catalogBinding(),
            document.requiredCapabilities(), document.nodes(), document.connections(), document.passthroughs(), document.variables(), document.functions(),
            document.unknown().with(CATALOG_COMPATIBILITY, compatibility(document, publication, entries)));
    }

    private static Map<String, String> compatibility(GraphDocument document, CatalogAuthoringPublication publication,
                                                     Collection<CatalogCachePublication.Entry> entries) {
        Map<ContractRef<NodeId>, CatalogCachePublication.Entry> catalog = new LinkedHashMap<>();
        entries.forEach(entry -> catalog.put(entry.definitionKey(), entry));
        JsonObject definitions = new JsonObject();
        document.nodes().stream().map(node -> node.definition()).distinct().sorted().forEach(reference -> {
            CatalogCachePublication.Entry entry = catalog.get(reference);
            if (entry == null || entry.tombstone() || entry.opaque() || entry.data() == null) {
                throw new IllegalArgumentException("Content Node Is Unavailable: " + reference.canonicalText());
            }
            JsonObject definition = new JsonObject();
            definition.addProperty("state", entry.state().name());
            JsonArray capabilities = new JsonArray();
            entry.requiredCapabilities().stream().sorted().forEach(capability -> capabilities.add(capability.canonicalText()));
            definition.add("requiredCapabilities", capabilities);
            definition.add("data", JsonParser.parseString(entry.data().canonicalText()));
            definitions.add(reference.canonicalText(), definition);
        });
        JsonObject authoring = JsonParser.parseString(new CatalogAuthoringPublicationCodec().encode(publication).canonicalText()).getAsJsonObject();
        authoring.remove("binding");
        return Map.of("version", "1",
            "descriptorHash", CanonicalHash.sha256("content-descriptors", CanonicalCodec.decodePermissive(definitions.toString())),
            "authoringHash", CanonicalHash.sha256("content-authoring", CanonicalCodec.decodePermissive(authoring.toString())));
    }

    private static void requireCatalogVersions(GraphDocument document, Collection<CatalogCachePublication.Entry> entries) {
        Map<ContractRef<NodeId>, Integer> versions = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : entries) {
            if (!entry.tombstone() && !entry.opaque() && entry.data() != null) {
                JsonObject descriptor = JsonParser.parseString(entry.data().canonicalText()).getAsJsonObject();
                versions.put(entry.definitionKey(), descriptor.get("schemaVersion").getAsInt());
            }
        }
        document.nodes().forEach(node -> {
            if (!Objects.equals(versions.get(node.definition()), node.definitionVersion())) {
                throw new IllegalArgumentException("Custom content requires a catalog migration for " + node.definition().canonicalText());
            }
        });
    }

    private static void migrateContentRoot(FlowGraph graph, Collection<CatalogCachePublication.Entry> entries) {
        Map<String, JsonObject> descriptors = new LinkedHashMap<>();
        for (CatalogCachePublication.Entry entry : entries) {
            if (!entry.tombstone() && !entry.opaque() && entry.data() != null) {
                JsonObject descriptor = JsonParser.parseString(entry.data().canonicalText()).getAsJsonObject();
                descriptors.put(entry.definitionKey().owner().canonicalText() + ":" + entry.definitionKey().id().canonicalText(), descriptor);
            }
        }
        for (Map.Entry<String, FlowNode> entry : graph.getNodes().entrySet()) {
            FlowNode node = entry.getValue();
            String qualified = node.getType().contains(":") ? node.getType() : "restudio.resync:" + node.getType();
            JsonObject descriptor = descriptors.get(qualified);
            if (descriptor == null) {
                throw new IllegalArgumentException("Custom content node is absent from the published catalog: " + node.getType());
            }
            node.setType(qualified);
            int targetVersion = descriptor.get("schemaVersion").getAsInt();
            int sourceVersion = Math.max(1, node.getVersion());
            if (sourceVersion == targetVersion) {
                continue;
            }
            JsonObject metadata = descriptor.has("metadata") && descriptor.get("metadata").isJsonObject()
                ? descriptor.getAsJsonObject("metadata") : new JsonObject();
            JsonObject authored = metadata.has("authoredSource") && metadata.get("authoredSource").isJsonObject()
                ? metadata.getAsJsonObject("authoredSource") : descriptor;
            JsonObject mapping = authored.has("migrationMapping") && authored.get("migrationMapping").isJsonObject()
                ? authored.getAsJsonObject("migrationMapping") : null;
            if (mapping == null || !mapping.has("complete") || !mapping.get("complete").getAsBoolean()
                || mapping.get("sourceSchemaVersion").getAsInt() != sourceVersion
                || mapping.get("targetSchemaVersion").getAsInt() != targetVersion) {
                throw new IllegalArgumentException("Custom content requires a declared node migration: " + node.getType());
            }
            Map<String, String> inputPins = new LinkedHashMap<>();
            Map<String, String> outputPins = new LinkedHashMap<>();
            JsonArray pins = mapping.getAsJsonArray("pins");
            for (JsonElement value : pins) {
                JsonObject pin = value.getAsJsonObject();
                Map<String, String> direction = "input".equals(pin.get("direction").getAsString()) ? inputPins : outputPins;
                if (direction.putIfAbsent(pin.get("source").getAsString(), pin.get("target").getAsString()) != null) {
                    throw new IllegalArgumentException("Custom content migration has ambiguous pins");
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            node.getInputValues().forEach((pin, value) -> {
                String target = inputPins.get(pin);
                if (target == null && (CustomContentGraphAdapter.FLOW_BRANCHES_KEY.equals(pin) || "armor_slot".equals(pin))) target = pin;
                if (target == null || values.containsKey(target)) {
                    throw new IllegalArgumentException("Custom content migration cannot preserve pin " + pin);
                }
                values.put(target, value);
            });
            node.setInputValues(values);
            for (FlowConnection connection : graph.getConnections()) {
                if (entry.getKey().equals(connection.getSourceNodeId())) {
                    String pin = outputPins.get(connection.getSourcePinId());
                    if (pin == null) throw new IllegalArgumentException("Custom content migration cannot preserve an output connection");
                    connection.setSourcePinId(pin);
                }
                if (entry.getKey().equals(connection.getTargetNodeId())) {
                    String pin = inputPins.get(connection.getTargetPinId());
                    if (pin == null) throw new IllegalArgumentException("Custom content migration cannot preserve an input connection");
                    connection.setTargetPinId(pin);
                }
            }
            node.setVersion(targetVersion);
        }
    }
}
