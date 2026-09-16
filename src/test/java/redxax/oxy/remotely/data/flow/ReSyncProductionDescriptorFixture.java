package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogCanonicalizer;
import restudio.resync.flow.catalog.CatalogCategoryDescriptor;
import restudio.resync.flow.catalog.CatalogContractRange;
import restudio.resync.flow.catalog.CatalogContribution;
import restudio.resync.flow.catalog.CatalogProvenance;
import restudio.resync.flow.catalog.CatalogSourceIngestor;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.inspector.InspectorFallback;
import restudio.resync.flow.inspector.InspectorValueSchema;
import restudio.resync.flow.runtime.RuntimeFailureContract;
import restudio.resync.flow.runtime.RuntimeOperationDescriptor;
import restudio.resync.flow.runtime.RuntimeSemantics;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ReSyncProductionDescriptorFixture {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final Map<String, CatalogCachePublication.Entry> ENTRIES = load();

    private ReSyncProductionDescriptorFixture() {
    }

    public static CatalogCachePublication.Entry entry(String id) {
        CatalogCachePublication.Entry entry = ENTRIES.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("Missing production descriptor: " + id);
        }
        return entry;
    }

    public static Map<String, String> commandOutputTypes() {
        return Map.of("flow", "execution", "event.player", "player", "event.command", "string",
            "event.is_cancelled", "boolean", "event.bound_command", "string", "event.command_label", "string",
            "event.args", "string", "event.args_list", "list<string>", "event.args_count", "number", "event.is_console", "boolean");
    }

    public static ReSyncTypedInteractionProjection interaction() {
        ServerId server = new ServerId(UUID.fromString("88888888-8888-4888-8888-888888888888"));
        CatalogCacheKey key = new CatalogCacheKey(server, 1, ContentHash.of("a".repeat(64)),
            ContentHash.of("b".repeat(64)), CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            List.copyOf(ENTRIES.values()));
        ReSyncCatalogPublicationProjection projection = new ReSyncCatalogPublicationProjection(server);
        if (!projection.apply(publication, new CatalogCachePublicationCodec().encodeBytes(publication))) {
            throw new IllegalStateException("Production fixture publication was rejected");
        }
        return ReSyncTypedInteractionProjection.from(projection.active().orElseThrow());
    }

    public static ReSyncGenericWidgetCapabilities.WidgetDefinition widget(ReSyncTypedInteractionProjection interaction, String id) {
        ContractRef<NodeId> identity = entry(id).definitionKey();
        return interaction.widgetDefinition(identity).orElseThrow(() -> new AssertionError(rejectionReason(interaction, id)));
    }

    public static String rejectionReason(ReSyncTypedInteractionProjection interaction, String id) {
        ReSyncGenericDescriptorProjection.Projection descriptor = interaction.descriptor(entry(id).definitionKey())
            .orElseThrow(() -> new AssertionError(id + ": descriptor_missing"));
        ReSyncGenericWidgetCapabilities.Conversion conversion = ReSyncGenericWidgetCapabilities.convert(descriptor);
        return id + ": status=" + descriptor.status() + ", descriptorReason=" + descriptor.reason()
            + ", widgetReason=" + conversion.reason();
    }

    private static Map<String, CatalogCachePublication.Entry> load() {
        try (InputStream stream = ReSyncProductionDescriptorFixture.class.getResourceAsStream(
            "/redxax/oxy/remotely/data/flow/production-property-event-nodes.json")) {
            if (stream == null) {
                throw new IllegalStateException("Production descriptor fixture is missing");
            }
            Map<?, ?> fixture = (Map<?, ?>) CanonicalJson.parseOpaque(stream.readAllBytes());
            LinkedHashMap<String, CatalogCachePublication.Entry> entries = new LinkedHashMap<>();
            for (Object value : (List<?>) fixture.get("sources")) {
                Map<?, ?> record = (Map<?, ?>) value;
                Map<?, ?> node = (Map<?, ?>) record.get("node");
                String category = ((String) node.get("category")).toLowerCase(Locale.ROOT);
                TypeExpr string = TypeExpr.named(TypeReference.of("builtin", "string"));
                TypeExpr any = TypeExpr.named(TypeReference.of("builtin", "any"));
                ContractRef<CapabilityId> editorId = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
                InspectorCapability editor = new InspectorCapability(editorId, "Generic Editor",
                    "Provides the shared editor capability for authored Flow pins.", new InspectorValueSchema(any),
                    new InspectorValueSchema(any), List.of(), editorId, InspectorFallback.GENERIC);
                CatalogSourceIngestor.CatalogIngestionContext context = new CatalogSourceIngestor.CatalogIngestionContext(
                    new CatalogContractRange(new CatalogVersion(1, 0), new CatalogVersion(1, 0)),
                    List.of(new CatalogCategoryDescriptor(category, category, "Groups the production node family.", 0)),
                    editor, ignored -> Optional.empty(), request -> Optional.of(runtime(request, string)), Optional::of);
                CatalogSourceIngestor.CatalogSource source = new CatalogSourceIngestor.CatalogSource(OWNER,
                    CatalogProvenance.SourceKind.BUNDLED, "classpath:/" + record.get("source"), "2.0.0", "production-fixture",
                    CanonicalJson.canonicalize(List.of(node)).getBytes(StandardCharsets.UTF_8));
                CatalogContribution contribution = new CatalogSourceIngestor().ingest(source, context);
                if (!contribution.provenanceErrors().isEmpty()) {
                    throw new IllegalStateException("Production fixture provenance was rejected: " + contribution.provenanceErrors());
                }
                contribution.definitions().forEach(descriptor -> entries.put(descriptor.id().value(),
                    CatalogCachePublication.Entry.present(descriptor.reference(OWNER), 1, CatalogCacheState.ACTIVE,
                        descriptor.requiredCapabilities(), false, CatalogCacheOpaque.of(
                            CatalogCanonicalizer.canonicalNodeContent(descriptor, contribution).getBytes(StandardCharsets.UTF_8)))));
            }
            return Map.copyOf(entries);
        } catch (IOException exception) {
            throw new IllegalStateException("Production descriptor fixture could not be read", exception);
        }
    }

    private static RuntimeOperationDescriptor runtime(CatalogSourceIngestor.RuntimeRequest request, TypeExpr string) {
        RuntimeFailureContract failure = new RuntimeFailureContract(string, Set.of("RUNTIME.FAILURE"), Set.of("failure"),
            RuntimeFailureContract.CommitBoundary.NO_MUTATION);
        RuntimeSemantics semantics = new RuntimeSemantics(RuntimeSemantics.Effect.STATE_READING,
            RuntimeSemantics.ThreadMode.MAIN, request.capability(), RuntimeSemantics.Cancellation.NONE, 0, 0, 0,
            RuntimeSemantics.UnloadPolicy.DRAIN, RuntimeSemantics.Retry.NEVER, RuntimeSemantics.Idempotency.NONE,
            RuntimeSemantics.Audit.NONE, RuntimeSemantics.Confirmation.NONE, RuntimeSemantics.SensitiveData.NONE,
            RuntimeSemantics.Determinism.ENVIRONMENT, Set.of(), Set.of("failure"), Set.of(), failure, Set.of(), Set.of());
        Map<String, Object> metadata = request.trigger()
            ? Map.of("eventType", request.source().get("eventType"), "handlerConfig", request.handlerConfig()) : Map.of();
        return new RuntimeOperationDescriptor(request.capability(), request.operation(), request.pins(), semantics, metadata);
    }
}
