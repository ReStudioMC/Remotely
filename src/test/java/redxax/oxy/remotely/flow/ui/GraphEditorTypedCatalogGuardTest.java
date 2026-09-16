package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.data.flow.ReSyncCatalogPublicationCache;
import redxax.oxy.remotely.data.flow.ReSyncGenericWidgetCapabilities;
import redxax.oxy.remotely.data.flow.ReSyncProductionDescriptorFixture;
import redxax.oxy.remotely.data.flow.ReSyncTypedInteractionProjection;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ServerId;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.flow.data.FlowDataType;
import redxax.oxy.remotely.flow.registry.NodeDefinition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphEditorTypedCatalogGuardTest {
    @BeforeAll
    static void initializeTheme() {
        restudio.rescreen.theme.ThemeManager.initBrowserDefaults();
        restudio.rescreen.render.TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void catalogAuthorityMatrixHasNoReadWriteBypass() {
        ReSyncFlowClient.CatalogAuthority[] blocked = {
            ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION,
            ReSyncFlowClient.CatalogAuthority.UNAVAILABLE
        };
        for (ReSyncFlowClient.CatalogAuthority authority : blocked) {
            assertFalse(GraphEditorScreen.allowsCatalogDescriptorResolution(authority));
            assertFalse(GraphEditorScreen.allowsCatalogNodeEditing(authority));
            assertFalse(GraphEditorScreen.allowsCatalogWorkspacePublication(authority));
            assertFalse(GraphEditorScreen.allowsCatalogDurableSave(authority));
            assertFalse(GraphEditorScreen.allowsTypedMutation(authority));
            assertFalse(GraphEditorScreen.allowsLegacyCatalogEditing(authority));
        }

        ReSyncFlowClient.CatalogAuthority typed = ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION;
        assertTrue(GraphEditorScreen.allowsCatalogDescriptorResolution(typed));
        assertTrue(GraphEditorScreen.allowsCatalogNodeEditing(typed));
        assertTrue(GraphEditorScreen.allowsCatalogWorkspacePublication(typed));
        assertTrue(GraphEditorScreen.allowsCatalogDurableSave(typed));
        assertTrue(GraphEditorScreen.allowsTypedMutation(typed));
        assertFalse(GraphEditorScreen.allowsLegacyCatalogEditing(typed));

        ReSyncFlowClient.CatalogAuthority legacy = ReSyncFlowClient.CatalogAuthority.LEGACY_COMPATIBILITY;
        assertFalse(GraphEditorScreen.allowsCatalogDescriptorResolution(legacy));
        assertFalse(GraphEditorScreen.allowsCatalogNodeEditing(legacy));
        assertFalse(GraphEditorScreen.allowsCatalogWorkspacePublication(legacy));
        assertFalse(GraphEditorScreen.allowsCatalogDurableSave(legacy));
        assertFalse(GraphEditorScreen.allowsTypedMutation(legacy));
        assertTrue(GraphEditorScreen.allowsLegacyCatalogEditing(legacy));
    }

    @Test
    void existingDraftRemainsVisibleAndReadOnlyWhileCatalogAuthorityReturns() {
        FlowGraph graph = new FlowGraph();
        FlowNode draft = new FlowNode("resync.reconnect:stable-node", 12, 24, Map.of("value", "draft"));
        graph.getNodes().put("draft-node", draft);
        FlowNodeWidget widget = new FlowNodeWidget(12, 24, draft, graph, "draft-node", "server",
            null, null, FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), null, true, true);

        assertTrue(graph.getNodes().containsKey("draft-node"));
        assertTrue(widget.isEditorReadOnly());
        assertFalse(widget.hasLoadedDefinition());
        assertTrue(widget.isDefinitionLookupBlocked());
        assertEquals(Map.of("value", "draft"), draft.getInputValues());
    }

    @Test
    void uniqueUnqualifiedTypedNodeLoadsItsAuthoritativeWidgetCapability() {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();
        ReSyncGenericWidgetCapabilities.WidgetDefinition resolved = GraphEditorScreen
            .typedWidgetDefinitionForNodeType(interaction, "event.command", false).orElseThrow();
        FlowGraph graph = new FlowGraph();
        FlowNode node = new FlowNode("event.command", 12, 24, Map.of());
        graph.getNodes().put("root", node);
        FlowNodeWidget widget = new FlowNodeWidget(12, 24, node, graph, "root", null, null, null,
            FlowNodeWidget.FunctionBoundaryCatalog.unavailable(), resolved.definition(), resolved.readOnly(), false);

        assertEquals(ReSyncProductionDescriptorFixture.entry("event.command").definitionKey().owner().canonicalText(),
            resolved.definition().getOwner());
        assertTrue(widget.hasLoadedDefinition());
        assertFalse(widget.isDefinitionLookupBlocked());
        assertFalse(widget.isEditorReadOnly());
        assertEquals("editable", widget.getDefinitionResolutionCause());
    }

    @Test
    void hiddenExistingNodeResolvesOutsideTheAddablePalette() {
        ReSyncTypedInteractionProjection interaction = ReSyncProductionDescriptorFixture.interaction();

        assertTrue(interaction.palette(false).definition("world_set_weather").isEmpty());
        ReSyncGenericWidgetCapabilities.WidgetDefinition resolved = GraphEditorScreen
            .typedWidgetDefinitionForNodeType(interaction, "world_set_weather", false).orElseThrow();

        assertTrue(resolved.definition().isHidden());
        assertEquals("world_set_weather", resolved.definition().getId());
    }

    @Test
    void incompleteTypedPublicationKeepsLegacyCustomFunctionDefinitionRegistrationBlocked(@TempDir Path temporaryDirectory) {
        ServerId server = new ServerId(UUID.randomUUID());
        ReSyncFlowClient typedClient = new ReSyncFlowClient(server.canonicalText(), new NoopTransport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("typed-catalog.json"))));
        ReSyncFlowClient legacyClient = new ReSyncFlowClient("legacy-server", new NoopTransport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(temporaryDirectory.resolve("legacy-catalog.json"))));
        CatalogBinding binding = new CatalogBinding(1, new ContentHash("1".repeat(64)), new ContentHash("2".repeat(64)));
        CatalogCacheKey key = new CatalogCacheKey(server, binding, CatalogProjectionVersion.current());
        ContractRef<NodeId> node = ContractRef.of(new OwnerId("typed.functions"), new NodeId("function"));
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key, 1,
            List.of(CatalogCachePublication.Entry.present(node, 1, CatalogCacheState.ACTIVE, Set.of(), false,
                CatalogCacheOpaque.of(CanonicalJson.canonicalizeJson("{\"id\":\"function\"}".getBytes(StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8)))));
        try {
            assertFalse(GraphEditorScreen.allowsLegacyCustomFunctionRegistration(typedClient));
            var codec = new restudio.resync.flow.cache.CatalogCachePublicationCodec();
            assertTrue(typedClient.catalogPublicationProjection().apply(publication, codec.encodeBytes(publication)));
            assertFalse(typedClient.acknowledgeCatalogPublicationKey(key));
            assertEquals(ReSyncFlowClient.CatalogAuthority.TYPED_RECONCILIATION, typedClient.catalogAuthority());
            assertEquals("CATALOG_PUBLICATION_KEY_MISMATCH_RECONCILIATION",
                typedClient.catalogAuthorityDiagnostic().orElseThrow());
            assertFalse(GraphEditorScreen.allowsLegacyCustomFunctionRegistration(typedClient));
            assertFalse(GraphEditorScreen.allowsLegacyCustomFunctionRegistration(legacyClient));
        } finally {
            typedClient.shutdown();
            legacyClient.shutdown();
        }
    }

    @Test
    void functionParameterPinIdentityUsesStableIdAndKeepsDisplayName() throws Exception {
        FlowGraph.FunctionParameter parameter = new FlowGraph.FunctionParameter("parameter-id", "Legacy Name", FlowDataType.STRING);
        parameter.setDisplayName("Input Label");
        Method pinId = GraphEditorScreen.class.getDeclaredMethod("functionParameterPinId", FlowGraph.FunctionParameter.class,
            NodeDefinition.PinDirection.class);
        pinId.setAccessible(true);

        assertEquals("function-input-parameter-id", pinId.invoke(null, parameter, NodeDefinition.PinDirection.INPUT));
        assertEquals("function-output-parameter-id", pinId.invoke(null, parameter, NodeDefinition.PinDirection.OUTPUT));
        assertEquals("Input Label", parameter.getDisplayName());
    }

    private static final class NoopTransport implements ReSyncFrameTransport {
        @Override
        public void setFrameHandler(Consumer<byte[]> handler) {
        }

        @Override
        public void setCloseHandler(Runnable handler) {
        }

        @Override
        public void send(byte[] frame) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isOpen() {
            return true;
        }
    }
}
