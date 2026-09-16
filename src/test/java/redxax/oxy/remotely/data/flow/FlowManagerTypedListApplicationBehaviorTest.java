package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlowManagerTypedListApplicationBehaviorTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void authoritativeListHandlersPopulateAndRemoveBrowserMembership(ListScenario scenario) throws Exception {
        String serverId = "list-" + scenario.type().typeId();
        ScriptedReSyncTransport transport = new ScriptedReSyncTransport();
        ReSyncFlowClient client = new ReSyncFlowClient(serverId, transport, null);
        FlowManager manager = new FlowManager(null, null);
        FlowManagerTestConnection.install(manager, serverId, client, transport);
        try {
            scenario.apply().accept(manager, List.of("listed"));
            assertNotNull(find(manager.getProjectResources(serverId), scenario.type(), "listed"));

            scenario.apply().accept(manager, List.of());
            assertNull(find(manager.getProjectResources(serverId), scenario.type(), "listed"));
        } finally {
            manager.shutdown();
        }
    }

    private static Stream<ListScenario> scenarios() {
        return Stream.of(
            new ListScenario(ReSyncResourceType.FLOW,
                (manager, ids) -> manager.applyServerGraphList("list-flow", ReSyncResourceType.FLOW, ids)),
            new ListScenario(ReSyncResourceType.TAB,
                (manager, ids) -> manager.applyServerTabList("list-tab", ids)),
            new ListScenario(ReSyncResourceType.GUI,
                (manager, ids) -> manager.applyServerGuiList("list-gui", ids)),
            new ListScenario(ReSyncResourceType.CHAT,
                (manager, ids) -> manager.applyServerJsonResourceList("list-chat", ReSyncResourceType.CHAT, ids))
        );
    }

    private static ReSyncProjectMetadata.ResourceEntry find(List<ReSyncProjectMetadata.ResourceEntry> resources,
                                                            ReSyncResourceType type, String id) {
        return resources.stream().filter(resource -> type.typeId().equals(resource.getType())
            && id.equals(resource.getId())).findFirst().orElse(null);
    }

    private record ListScenario(ReSyncResourceType type, BiConsumer<FlowManager, List<String>> apply) {
        @Override
        public String toString() {
            return type.typeId();
        }
    }
}
