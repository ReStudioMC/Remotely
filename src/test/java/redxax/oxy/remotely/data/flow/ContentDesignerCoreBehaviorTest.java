package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.flow.data.CustomContentDefinition;
import redxax.oxy.remotely.flow.data.CustomContentGraphAdapter;
import redxax.oxy.remotely.flow.data.FlowGraph;
import redxax.oxy.remotely.flow.data.FlowNode;
import redxax.oxy.remotely.flow.data.FlowSerializer;
import redxax.oxy.remotely.flow.ui.ContentDesignerScreen;
import redxax.oxy.remotely.flow.ui.FlowNodeWidget;
import redxax.oxy.remotely.flow.ui.GraphEditorScreen;
import redxax.oxy.remotely.flow.ui.studio.ScreenBackedStudioView;
import redxax.oxy.remotely.test.TestDrawContext;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.flow.cache.CatalogAuthoringPublication;
import restudio.resync.flow.cache.CatalogCacheKey;
import restudio.resync.flow.cache.CatalogCacheOpaque;
import restudio.resync.flow.cache.CatalogCachePublication;
import restudio.resync.flow.cache.CatalogCachePublicationCodec;
import restudio.resync.flow.cache.CatalogCacheState;
import restudio.resync.flow.cache.CatalogPublicationReceipt;
import restudio.resync.flow.canonical.CanonicalJson;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.NodeId;
import restudio.resync.flow.identity.NodeInstanceId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentDesignerCoreBehaviorTest {
    private static final OwnerId OWNER = OwnerId.of("restudio.resync");
    private static final ServerId SERVER = ServerId.deterministic("content-designer-ui");
    private static final CatalogBinding BINDING = new CatalogBinding(1, "a".repeat(64), "b".repeat(64));
    private static final ServerResourceLocator RESOURCE = new ServerResourceLocator(SERVER,
        ContractRef.of(OWNER, ResourceTypeId.of("custom_content")), "item");
    private static final ContractRef<CapabilityId> EDITOR = ContractRef.of(OWNER, CapabilityId.of("generic-editor"));
    private static final NodeInstanceId ROOT = NodeInstanceId.deterministic("content-ui-root");
    private static final NodeInstanceId ACTION = NodeInstanceId.deterministic("content-ui-action");
    private RemotelyClient previousApplication;
    private ContentApplication application;

    @BeforeEach
    void installApplicationOwner() {
        previousApplication = RemotelyClient.INSTANCE;
        application = new ContentApplication();
    }

    @AfterEach
    void restoreApplicationOwner() {
        RemotelyClient.INSTANCE = previousApplication;
    }

    @Test
    void outerStudioRoutesCatalogAddHeaderSaveAndShortcutToTheContentSession(@TempDir Path directory) throws Exception {
        ThemeManager.initBrowserDefaults();
        ReSyncFlowClient client = catalog(directory);
        ContentManager manager = new ContentManager(client, directory);
        application.manager = manager;
        manager.publish(content(), 1, UUID.randomUUID().toString());
        ContentStudio host = new ContentStudio();
        ContentEditor editor = new ContentEditor(manager.current(), host, client);
        assertSame(manager, editor.manager());
        try {
            host.openWorkspaceContentDesigner("item", "Item", manager.current().getGraph(), editor, false);
            ScreenBackedStudioView view = host.contentView();
            assertSame(editor, view.screen());
            assertSame(host, GraphEditorScreen.getStudioScreen(SERVER.canonicalText()));
            assertFalse(editor.hasStudioDocument());
            await(view, editor, () -> editor.session() != null && editor.widget(ROOT) != null && editor.widget(ACTION) != null);

            editor.openCatalogMenu();
            await(view, editor, editor::catalogMenuReady);
            assertTrue(editor.keyPressed(key(editor, ReKey.DOWN, false)));
            assertTrue(editor.keyPressed(key(editor, ReKey.ENTER, false)));
            await(view, editor, () -> editor.session().graphDocument().nodes().size() == 3);
            GraphNode added = editor.session().graphDocument().nodes().stream()
                .filter(node -> !node.instanceId().equals(ROOT) && !node.instanceId().equals(ACTION)).findFirst().orElseThrow();
            assertEquals("action", added.definition().id().canonicalText());
            await(view, editor, () -> editor.widget(added.instanceId()) != null);
            assertTrue(host.contentDirty());

            editor.clickHeaderSave();
            await(view, editor, () -> manager.pending != null);
            assertEquals(ReSyncResourceType.CUSTOM_CONTENT, manager.pending.type());
            assertEquals("item", manager.pending.id());
            assertEquals(0, host.outerSavingCalls);
            settle(manager, host, 2);
            await(view, editor, () -> editor.session().revision() == 2 && !editor.session().dirty());
            assertFalse(host.contentDirty());
            assertEquals(0, host.outerSavedCalls);

            manager.pending = null;
            editor.setName("Saved From Shortcut");
            await(view, editor, () -> "Saved From Shortcut".equals(editor.name()));
            assertTrue(host.keyPressed(key(host, ReKey.S, true)));
            await(view, editor, () -> manager.pending != null);
            settle(manager, host, 3);
            await(view, editor, () -> editor.session().revision() == 3 && !editor.session().dirty());
            assertEquals("Saved From Shortcut", editor.name());
            assertFalse(editor.hasStudioDocument());
            assertEquals(0, host.outerSavingCalls);
            assertEquals(0, host.outerSavedCalls);
            assertEquals(3, editor.session().graphDocument().nodes().size());
        } finally {
            if (manager.pending != null && DesignerSaveNotifications.isPending(manager.pending)) {
                DesignerSaveNotifications.failExact(manager.pending, "Test Complete");
            }
            editor.removed();
            host.removed();
            manager.shutdown();
            client.shutdown();
        }
    }

    private static void settle(ContentManager manager, ContentStudio host, long revision) {
        DesignerSaveNotifications.SaveTicket ticket = manager.pending;
        manager.publish(manager.saved, revision, ticket.mutationId());
        assertNotNull(DesignerSaveNotifications.completeMutation(SERVER.canonicalText(), ReSyncResourceType.CUSTOM_CONTENT,
            "item", ticket.mutationId()));
        host.markStudioDocumentSaved("custom_content", "item", ticket.sequence(), revision, "c".repeat(64), ticket.mutationId());
    }

    private static ReKeyEvent key(Object source, ReKey key, boolean control) {
        return new ReKeyEvent(source, source, System.nanoTime(), new ReModifierState(false, control, false, false, false, false),
            ReKeyEvent.Action.PRESSED, key, 0, 0, ReKeyLocation.STANDARD, false);
    }

    @Test
    void embeddedDesignerEditsPinsAndSavesAcrossAcknowledgmentFollowedByNewerAuthority(@TempDir Path directory) throws Exception {
        ThemeManager.initBrowserDefaults();
        ReSyncFlowClient client = catalog(directory);
        ContentManager manager = new ContentManager(client, directory);
        application.manager = manager;
        manager.publish(content(), 1, UUID.randomUUID().toString());
        Screen host = new Screen();
        host.resize(1100, 720);
        ContentEditor editor = new ContentEditor(manager.current(), host, client);
        assertSame(manager, editor.manager());
        ScreenBackedStudioView view = new ScreenBackedStudioView(host, editor);
        try {
            view.init();
            await(view, editor, () -> editor.session() != null && editor.widget(ROOT) != null && editor.widget(ACTION) != null);
            assertTrue(editor.widget(ROOT).isEditable());
            editor.moveRoot(270, 180);
            await(view, editor, () -> editor.node(ROOT).x() == 270);
            assertTrue(editor.connectRoot());
            assertEquals(1, editor.projectedConnections());
            await(view, editor, () -> editor.session().graphDocument().connections().size() == 1);
            editor.save();
            await(view, editor, () -> manager.pending != null);
            assertEquals(ReSyncResourceType.CUSTOM_CONTENT, manager.pending.type());
            assertNotNull(manager.saved.getGraph().getOpaqueProperties().get(CustomContentCoreEditor.CORE_GRAPH));
            editor.setName("After Save");
            await(view, editor, () -> "After Save".equals(editor.name()));

            manager.publish(manager.saved, 2, manager.pending.mutationId());
            assertNotNull(DesignerSaveNotifications.completeMutation(SERVER.canonicalText(), ReSyncResourceType.CUSTOM_CONTENT,
                "item", manager.pending.mutationId()));
            CoreGraphEditorSession newer = CustomContentCoreEditor.prepare(manager.saved, RESOURCE, 2,
                client.activeCatalogAuthoringPublication().orElseThrow(), client.catalogPublicationProjection().active().orElseThrow().entries().values());
            newer.setNodePosition(ACTION, 760, 220);
            CustomContentDefinition remote = CustomContentCoreEditor.materialize(newer, manager.saved,
                client.activeCatalogAuthoringPublication().orElseThrow(), client.catalogPublicationProjection().active().orElseThrow().entries().values());
            manager.publish(remote, 3, UUID.randomUUID().toString());
            await(view, editor, () -> editor.session().revision() == 3 && editor.node(ACTION).x() == 760);
            assertEquals("After Save", editor.name());
            assertTrue(editor.session().dirty());
            manager.pending = null;
            editor.save();
            await(view, editor, () -> manager.pending != null);
            manager.publish(manager.saved, 4, manager.pending.mutationId());
            assertNotNull(DesignerSaveNotifications.completeMutation(SERVER.canonicalText(), ReSyncResourceType.CUSTOM_CONTENT,
                "item", manager.pending.mutationId()));
            await(view, editor, () -> editor.session().revision() == 4 && !editor.session().dirty());
            editor.removed();

            ContentEditor reopened = new ContentEditor(manager.current(), host, client);
            ScreenBackedStudioView reopenedView = new ScreenBackedStudioView(host, reopened);
            try {
                reopenedView.init();
                await(reopenedView, reopened, () -> reopened.session() != null && reopened.widget(ROOT) != null);
                assertEquals("After Save", reopened.name());
                assertEquals(270, reopened.node(ROOT).x());
                assertEquals(760, reopened.node(ACTION).x());
                assertEquals(1, reopened.session().graphDocument().connections().size());
                assertFalse(reopened.session().dirty());
            } finally {
                reopened.removed();
            }
        } finally {
            editor.removed();
            manager.shutdown();
            client.shutdown();
        }
    }

    private static void await(ScreenBackedStudioView view, ContentEditor editor, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            editor.tick();
            view.render(new TestDrawContext(), 0, 0, 0);
            if (condition.getAsBoolean()) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        assertTrue(condition.getAsBoolean(), () -> "Content editor did not settle: " + editor.issue());
    }

    private static CustomContentDefinition content() {
        FlowGraph graph = new FlowGraph();
        graph.setId("content.item.item");
        graph.setResourceType("custom_content");
        FlowNode root = new FlowNode("custom_content.item", 240, 160, new LinkedHashMap<>(Map.of("content_id", "item", "name", "Before")));
        root.setVersion(2);
        graph.setNodes(new LinkedHashMap<>(Map.of(ROOT.canonicalText(), root,
            ACTION.canonicalText(), new FlowNode("action", 600, 160, Map.of()))));
        CustomContentDefinition content = CustomContentGraphAdapter.toDefinition(graph);
        assertNotNull(content);
        return content;
    }

    private static ReSyncFlowClient catalog(Path directory) throws Exception {
        ReSyncFlowClient client = new ReSyncFlowClient(SERVER.canonicalText(), new Transport(), null,
            new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(directory.resolve("catalog.json"))));
        CatalogAuthoringPublication.Entry capability = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.CAPABILITIES, EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(), Set.of(CatalogAuthoringPublication.Section.CAPABILITIES), false,
            CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "generic-editor"))));
        CatalogAuthoringPublication.Entry editor = new CatalogAuthoringPublication.Entry(
            CatalogAuthoringPublication.Section.EDITORS, EDITOR.canonicalText(), CatalogCacheState.ACTIVE,
            Set.of(EDITOR), Set.of(CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.CAPABILITIES),
            false, CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(Map.of("id", "generic-editor"))));
        List<CatalogAuthoringPublication.SectionProjection> sections = new ArrayList<>();
        for (CatalogAuthoringPublication.Section section : List.of(CatalogAuthoringPublication.Section.TYPES,
            CatalogAuthoringPublication.Section.EDITORS, CatalogAuthoringPublication.Section.PREVIEWS,
            CatalogAuthoringPublication.Section.CAPABILITIES)) {
            sections.add(new CatalogAuthoringPublication.SectionProjection(section, true, true, CatalogCacheState.ACTIVE,
                section == CatalogAuthoringPublication.Section.EDITORS ? List.of(editor)
                    : section == CatalogAuthoringPublication.Section.CAPABILITIES ? List.of(capability) : List.of()));
        }
        CatalogAuthoringPublication authoring = new CatalogAuthoringPublication(BINDING, new CatalogVersion(1, 0), sections, Set.of(EDITOR));
        List<CatalogCachePublication.Entry> entries = List.of(entry("custom_content.item", 2,
            List.of(pin("content_id", "input", "string"), pin("name", "input", "string"), pin("use", "output", "execution"))),
            entry("action", 1, List.of(pin("execute", "input", "execution"))));
        CatalogCacheKey key = new CatalogCacheKey(SERVER, BINDING, CatalogProjectionVersion.current());
        CatalogCachePublication publication = new CatalogCachePublication(CatalogCachePublication.Kind.FULL, key,
            BINDING, 1, entries, authoring, Map.of());
        ReSyncCatalogPublicationProjection projection = client.catalogPublicationProjection();
        Field authoringField = ReSyncFlowClient.class.getDeclaredField("catalogAuthoringProjection");
        authoringField.setAccessible(true);
        ReSyncCatalogPublicationReceiptHandler handler = new ReSyncCatalogPublicationReceiptHandler(SERVER,
            "content-ui-publication", projection, (ReSyncCatalogAuthoringProjection) authoringField.get(client), null);
        handler.setAuthoringRequired(true);
        CatalogPublicationReceipt receipt = CatalogPublicationReceipt.pending("content-ui-publication", "content-ui-owner", publication)
            .dispatched().clientReceived(key, 1).receipt().orElseThrow();
        assertTrue(handler.apply(receipt, publication, new CatalogCachePublicationCodec().encodeBytes(publication)).applied());
        ReSyncCatalogPublicationProjection.Snapshot snapshot = projection.active().orElseThrow();
        Method publish = ReSyncFlowClient.class.getDeclaredMethod("publishTypedInteractionProjection",
            ReSyncCatalogPublicationProjection.Snapshot.class, ReSyncTypedInteractionProjection.class);
        publish.setAccessible(true);
        assertTrue((boolean) publish.invoke(client, snapshot, ReSyncTypedInteractionProjection.from(snapshot)));
        Field authority = ReSyncFlowClient.class.getDeclaredField("catalogAuthority");
        authority.setAccessible(true);
        authority.set(client, ReSyncFlowClient.CatalogAuthority.TYPED_PUBLICATION);
        return client;
    }

    private static CatalogCachePublication.Entry entry(String id, int version, List<Map<String, Object>> pins) {
        Map<String, Object> descriptor = Map.of("kind", "node", "id", id, "schemaVersion", version,
            "displayName", id, "description", "Content", "domain", "flow", "family", "utility", "pins", pins,
            "metadata", Map.of(), "inspector", Map.of("intent", "none", "sections", List.of()));
        return CatalogCachePublication.Entry.present(ContractRef.of(OWNER, NodeId.of(id)), 1, CatalogCacheState.ACTIVE,
            Set.of(), false, CatalogCacheOpaque.of(CanonicalJson.canonicalBytes(descriptor)));
    }

    private static Map<String, Object> pin(String id, String direction, String type) {
        return Map.of("kind", "pin", "id", id, "displayName", id, "direction", direction, "requirement", "optional",
            "description", id, "resourceRole", "", "editor", Map.of("ownerId", OWNER.canonicalText(), "localId", "generic-editor"),
            "type", Map.of("kind", "named", "type", Map.of("ownerId", "builtin", "localId", type), "arguments", List.of()));
    }

    private static final class ContentManager extends FlowManager {
        private final ReSyncFlowClient client;
        private volatile CustomContentAuthority authority;
        private volatile DesignerSaveNotifications.SaveTicket pending;
        private volatile CustomContentDefinition saved;

        private ContentManager(ReSyncFlowClient client, Path directory) {
            super(null, null, id -> new ReSyncCatalogPublicationCache(redxax.oxy.remotely.data.flow.DesktopReSyncStorage.fromKey(directory.resolve("manager-catalog.json"))), directory);
            this.client = client;
        }

        private void publish(CustomContentDefinition content, long revision, String mutation) {
            JsonObject payload = JsonParser.parseString(FlowSerializer.serializeCustomContent(content)).getAsJsonObject();
            ReSyncResourceRevisionReconciler.ResourceResult result = new ReSyncResourceRevisionReconciler.ResourceResult(
                SERVER.canonicalText(), "custom_content", "item", revision, mutation, "c".repeat(64), false, payload, 1);
            client.resourceRevisionReconciler().apply(result);
            authority = new CustomContentAuthority(client, revision, result);
        }

        private CustomContentDefinition current() { return authority.materialize(); }
        @Override public ReSyncFlowClient existingFlowClient(String serverId) { return client; }
        @Override public boolean isFlowClientConnected(String serverId) { return true; }
        @Override public long getCustomContentRevision(String serverId, String id) { return authority.generation(); }
        @Override public CustomContentAuthority customContentAuthority(String serverId, String id) { return authority; }
        @Override public ReSyncResourceRevisionReconciler.Stamp customContentStamp(String serverId, String id) {
            return ReSyncResourceRevisionReconciler.Stamp.from(authority.result());
        }
        @Override public boolean isCurrentCustomContentAuthority(CustomContentAuthority expected) { return authority == expected; }
        @Override public CustomContentDefinition getCustomContent(String serverId, String id) { return current(); }
        @Override public boolean saveCustomContent(String serverId, CustomContentDefinition content,
                                                   DesignerSaveNotifications.SaveTicket ticket, CustomContentAuthority expected) {
            if (!isCurrentCustomContentAuthority(expected)) return false;
            saved = FlowSerializer.deserializeCustomContent(FlowSerializer.serializeCustomContent(content));
            pending = ticket;
            return true;
        }
    }

    private static final class ContentEditor extends ContentDesignerScreen {
        private final ReSyncFlowClient client;
        private SquareButtonWidget saveHeader;
        private ContentEditor(CustomContentDefinition content, Screen parent, ReSyncFlowClient client) {
            super(SERVER.canonicalText(), content.getGraph(), parent);
            this.client = client;
        }
        @Override protected ReSyncFlowClient typedCatalogClient() { return client; }
        @Override protected SquareButtonWidget headerButton(String icon, String hint, Runnable action) {
            SquareButtonWidget button = super.headerButton(icon, hint, action);
            if ("Save".equals(hint)) saveHeader = button;
            return button;
        }
        private boolean hasStudioDocument() { return activeStudioDocument != null; }
        private void openCatalogMenu() { showAllNodesMenu(200, 160); }
        private boolean catalogMenuReady() {
            try {
                Field field = GraphEditorScreen.class.getDeclaredField("nodeItemSelector");
                field.setAccessible(true);
                ItemSelectorWidget selector = (ItemSelectorWidget) field.get(this);
                if (selector == null) return false;
                selector.tick();
                return !selector.isVirtualLoading();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        private void clickHeaderSave() {
            assertNotNull(saveHeader);
            assertTrue(saveHeader.mouseClicked(new ReMouseEvent(this, saveHeader, System.nanoTime(), ReModifierState.none(),
                ReMouseEvent.Action.PRESSED, saveHeader.getX() + 2, saveHeader.getY() + 2, 0, 0, ReMouseButton.LEFT, 0, 1)));
        }
        private CoreGraphEditorSession session() { return (CoreGraphEditorSession) field("contentCoreSession"); }
        private FlowManager manager() { return (FlowManager) field("flowManager"); }
        private String issue() { return String.valueOf(field("contentCoreIssue")); }
        private FlowNodeWidget widget(NodeInstanceId id) { return widgetCache.get(id.canonicalText()); }
        private GraphNode node(NodeInstanceId id) {
            return session().graphDocument().nodes().stream().filter(node -> node.instanceId().equals(id)).findFirst().orElseThrow();
        }
        private String name() { return String.valueOf(node(ROOT).values().get(PinId.of("name")).value().value()); }
        private int projectedConnections() { return graph.getConnections().size(); }
        private void save() { onSave(); }
        private void moveRoot(int x, int y) throws Exception {
            widget(ROOT).setPosition(x, y);
            Method sync = GraphEditorScreen.class.getDeclaredMethod("syncNodePosition", FlowNodeWidget.class);
            sync.setAccessible(true);
            sync.invoke(this, widget(ROOT));
        }
        private boolean connectRoot() throws Exception {
            Method start = GraphEditorScreen.class.getDeclaredMethod("startWireDrag", FlowNodeWidget.class, String.class, boolean.class);
            start.setAccessible(true);
            start.invoke(this, widget(ROOT), "use", false);
            Method connect = GraphEditorScreen.class.getDeclaredMethod("connectWireTarget", FlowNodeWidget.class, String.class, boolean.class);
            connect.setAccessible(true);
            return (boolean) connect.invoke(this, widget(ACTION), "execute", true);
        }
        private void setName(String value) throws Exception {
            Method set = ContentDesignerScreen.class.getDeclaredMethod("setProperty", String.class, Object.class);
            set.setAccessible(true);
            set.invoke(this, "name", value);
        }
        private Object field(String name) {
            try {
                Field field = ContentDesignerScreen.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(this);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class ContentApplication extends RemotelyClient {
        private ContentManager manager;
        private ContentApplication() { super(null); }
        @Override public FlowManager getFlowManager() { return manager; }
    }

    private static final class ContentStudio extends GraphEditorScreen {
        private int outerSavingCalls;
        private int outerSavedCalls;

        private ContentStudio() throws Exception {
            super(new FlowGraph(), SERVER.canonicalText(), new Screen());
            resize(1100, 720);
            studioMode = true;
            Field chrome = GraphEditorScreen.class.getDeclaredField("studioChromeBuilt");
            chrome.setAccessible(true);
            chrome.set(this, true);
        }

        private ScreenBackedStudioView contentView() { return (ScreenBackedStudioView) activeStudioDocument.view(); }
        private boolean contentDirty() { return isStudioDocumentDirty(activeStudioDocument); }
        @Override public void markChangesSaving(long sequence) {
            outerSavingCalls++;
            super.markChangesSaving(sequence);
        }
        @Override public void markChangesSaved(long sequence) {
            outerSavedCalls++;
            super.markChangesSaved(sequence);
        }
    }

    private static final class Transport implements ReSyncFrameTransport {
        @Override public void setFrameHandler(Consumer<byte[]> handler) { }
        @Override public void setCloseHandler(Runnable handler) { }
        @Override public void send(byte[] frame) { }
        @Override public void close() { }
        @Override public boolean isOpen() { return true; }
    }
}
