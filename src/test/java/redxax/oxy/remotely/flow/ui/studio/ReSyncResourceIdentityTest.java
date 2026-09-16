package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.CoreGraphUiProjection;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ReSyncFlowClient;
import redxax.oxy.remotely.data.flow.ReSyncFrameTransport;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;
import redxax.oxy.remotely.flow.data.ReSyncResourceDragPayload;
import redxax.oxy.remotely.flow.data.TriggerBinding;
import redxax.oxy.remotely.flow.data.TriggerType;
import restudio.rescreen.theme.ThemeManager;
import restudio.resync.flow.identity.ContentHash;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.cache.GraphResourceState;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncResourceIdentityTest {
    @Test
    void deletedCommandReleasesIdDespiteStaleMembershipAndKeepsTombstoneAuthority() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        ServerId server = ServerId.deterministic("recreated-command");
        String serverId = server.canonicalText();
        String id = "recreated";
        try {
            install(manager, serverId);
            manager.applyServerGraphList(serverId, ReSyncResourceType.COMMAND, List.of(id));
            assertEquals("command", graphOwner(manager, serverId, id));
            Field projectionField = FlowManager.class.getDeclaredField("coreGraphUiProjection");
            projectionField.setAccessible(true);
            CoreGraphUiProjection projection = (CoreGraphUiProjection) projectionField.get(manager);
            ServerResourceLocator resource = new ServerResourceLocator(server,
                ContractRef.of(OwnerId.of("restudio.resync"), ResourceTypeId.of("command")), id);
            projection.apply(ReSyncResourceType.COMMAND, GraphResourceState.tombstone(resource, 2L,
                UUID.randomUUID(), new ContentHash("c".repeat(64))), false, 1L);

            assertFalse(manager.hasAuthoritativeResource(serverId, ReSyncResourceType.COMMAND, id));
            assertFalse(ReSyncResourceCreator.exists(manager, serverId, "command", id));
            assertNull(graphOwner(manager, serverId, id));
            assertTrue(projection.authoritative(serverId, ReSyncResourceType.COMMAND, id));
            assertTrue(projection.tombstoned(serverId, ReSyncResourceType.COMMAND, id));

            manager.applyServerGraphList(serverId, ReSyncResourceType.FUNCTION, List.of(id));
            assertEquals("function", graphOwner(manager, serverId, id));
        } finally {
            manager.shutdown();
        }
    }

    private static String graphOwner(FlowManager manager, String serverId, String id) throws Exception {
        Method method = ReSyncResourceCreator.class.getDeclaredMethod("graphIdOwner", FlowManager.class,
            String.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, manager, serverId, "command", id);
    }

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void authoritativeGraphMembershipKeepsTypedIdsDistinctWithoutDrafts() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        String serverId = "resource-identity";
        try {
            install(manager, serverId);
            manager.applyServerGraphList(serverId, ReSyncResourceType.FLOW, List.of("test"));
            manager.applyServerGraphList(serverId, ReSyncResourceType.COMMAND, List.of("test"));
            List<ReSyncProjectMetadata.ResourceEntry> resources = manager.getProjectResources(serverId);
            ReSyncProjectMetadata.ResourceEntry flow = find(resources, ReSyncResourceType.FLOW, "test");
            ReSyncProjectMetadata.ResourceEntry command = find(resources, ReSyncResourceType.COMMAND, "test");

            assertNotNull(flow);
            assertNotNull(command);
            assertNotEquals(flow.key(), command.key());
            assertTrue(manager.hasAuthoritativeResource(serverId, ReSyncResourceType.FLOW, "test"));
            assertTrue(manager.hasAuthoritativeResource(serverId, ReSyncResourceType.COMMAND, "test"));
            assertNull(manager.getGraph(serverId, ReSyncResourceType.FLOW, "test"));
            assertNull(manager.getGraph(serverId, ReSyncResourceType.COMMAND, "test"));
            assertNull(manager.getGraphType(serverId, "test"));
            assertTrue(ReSyncResourceCreator.exists(manager, serverId, ReSyncResourceDragPayload.FLOW, "test", "Other"));
            assertTrue(ReSyncResourceCreator.exists(manager, serverId, ReSyncResourceDragPayload.COMMAND, "test", "Other"));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void staleCommandBindingDoesNotReserveOrHydrateCommandResource() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        String serverId = "stale-command-binding";
        String commandId = "stale";
        try {
            install(manager, serverId);
            injectCommandBinding(manager, serverId, commandId, "Stale Command");
            manager.applyServerGraphList(serverId, ReSyncResourceType.COMMAND, List.of());

            assertNotNull(manager.getCommandBinding(serverId, commandId));
            assertFalse(manager.hasAuthoritativeResource(serverId, ReSyncResourceType.COMMAND, commandId));
            assertFalse(ReSyncResourceCreator.exists(manager, serverId, ReSyncResourceDragPayload.COMMAND, commandId));
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void authoritativeCommandMembershipHydratesWithoutCommandBindingOrMetadata() throws Exception {
        FlowManager manager = new FlowManager(null, null);
        String serverId = "typed-command-membership";
        String commandId = "listed-command";
        try {
            install(manager, serverId);
            manager.applyServerGraphList(serverId, ReSyncResourceType.COMMAND, List.of(commandId));

            assertNull(manager.getCommandBinding(serverId, commandId));
            assertTrue(manager.hasAuthoritativeResource(serverId, ReSyncResourceType.COMMAND, commandId));
            assertNotNull(find(manager.getProjectResources(serverId), ReSyncResourceType.COMMAND, commandId));
            assertTrue(ReSyncResourceCreator.exists(manager, serverId, ReSyncResourceDragPayload.COMMAND, commandId));
        } finally {
            manager.shutdown();
        }
    }

    private static void install(FlowManager manager, String serverId) throws Exception {
        OpenTransport transport = new OpenTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null);
        Class<?> fixture = Class.forName("redxax.oxy.remotely.data.flow.FlowManagerTestConnection");
        Method method = fixture.getDeclaredMethod("install", FlowManager.class, String.class,
            ReSyncFlowClient.class, ReSyncFrameTransport.class);
        method.setAccessible(true);
        method.invoke(null, manager, serverId, client, transport);
    }

    @SuppressWarnings("unchecked")
    private static void injectCommandBinding(FlowManager manager, String serverId, String commandId,
                                             String context) throws Exception {
        Field field = FlowManager.class.getDeclaredField("triggerBindings");
        field.setAccessible(true);
        Map<String, List<TriggerBinding>> bindings = (Map<String, List<TriggerBinding>>) field.get(manager);
        bindings.put(serverId, List.of(new TriggerBinding(commandId + ":command", commandId,
            TriggerType.COMMAND, context)));
    }

    private static ReSyncProjectMetadata.ResourceEntry find(List<ReSyncProjectMetadata.ResourceEntry> resources,
                                                            ReSyncResourceType type, String id) {
        return resources.stream().filter(resource -> type.typeId().equals(resource.getType())
            && id.equals(resource.getId())).findFirst().orElse(null);
    }

    private static final class OpenTransport implements ReSyncFrameTransport {
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
