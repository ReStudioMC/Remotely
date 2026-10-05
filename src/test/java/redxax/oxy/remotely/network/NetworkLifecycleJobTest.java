package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkLifecycleJobTest {
    @TempDir
    Path directory;

    @Test
    void persistsInterruptedLifecycleJobsForResume() {
        NetworkDefinition network = network();
        NetworkLifecycleJobRepository repository = new NetworkLifecycleJobRepository(directory);
        NetworkLifecycleJob running = NetworkLifecycleJob.create(network, NetworkLifecycleOperation.START, "Test", List.of(NetworkLifecycleStep.pending(network.members().get(1), NetworkLifecycleAction.START, 0))).startingAttempt();
        repository.save(running);

        NetworkLifecycleJobManager reloaded = new NetworkLifecycleJobManager(directory);
        NetworkLifecycleJob recovered = reloaded.getJob(running.jobId()).orElseThrow();

        assertEquals(NetworkLifecycleStatus.INTERRUPTED, recovered.status());
        assertTrue(recovered.canResume());
    }

    @Test
    void gatesTheHubBeforeBackendsForStartAndStopsProxyFirst() {
        NetworkDefinition network = network();
        NetworkLifecyclePlanner planner = new NetworkLifecyclePlanner();

        List<NetworkLifecycleStep> start = planner.plan(network, NetworkLifecycleOperation.START);
        List<NetworkLifecycleStep> stop = planner.plan(network, NetworkLifecycleOperation.STOP);

        assertEquals("proxy", start.getFirst().routeName());
        assertEquals(NetworkLifecycleAction.HEALTH_GATE, start.get(1).action());
        assertEquals("proxy", start.get(1).routeName());
        assertEquals("lobby", start.get(2).routeName());
        assertEquals("proxy", stop.getFirst().routeName());
        assertEquals("lobby", stop.getLast().routeName());
    }

    @Test
    void restartContainsAFullStopThenAFullStart() {
        NetworkDefinition network = network();
        NetworkLifecyclePlanner planner = new NetworkLifecyclePlanner();

        List<NetworkLifecycleStep> steps = planner.plan(network, NetworkLifecycleOperation.RESTART);

        assertEquals(network.members().size() * 3, steps.size());
        assertTrue(steps.subList(0, network.members().size()).stream().allMatch(step -> step.action() == NetworkLifecycleAction.STOP));
        assertEquals(NetworkLifecycleAction.START, steps.get(network.members().size()).action());
        assertEquals(NetworkLifecycleAction.HEALTH_GATE, steps.get(network.members().size() + 1).action());
    }

    @Test
    void rollingRestartDrainsGameplayBeforeFallbackAndHealthGatesEveryBackend() {
        NetworkDefinition network = network();
        NetworkLifecyclePlanner planner = new NetworkLifecyclePlanner();

        List<NetworkLifecycleStep> steps = planner.plan(network, NetworkLifecycleOperation.ROLLING_RESTART);

        assertEquals(14, steps.size());
        assertEquals("survival", steps.getFirst().routeName());
        assertEquals(List.of(NetworkLifecycleAction.CAPACITY_GATE, NetworkLifecycleAction.MAINTENANCE, NetworkLifecycleAction.DRAIN, NetworkLifecycleAction.STOP, NetworkLifecycleAction.START, NetworkLifecycleAction.HEALTH_GATE, NetworkLifecycleAction.RESUME), steps.subList(0, 7).stream().map(NetworkLifecycleStep::action).toList());
        assertEquals("lobby", steps.getLast().routeName());
        assertEquals(NetworkLifecycleAction.RESUME, steps.getLast().action());
    }

    @Test
    void recoversOnlyLifecycleJobsThatMatchTheCurrentRevision() {
        NetworkDefinition network = network();
        NetworkLifecycleJob pending = NetworkLifecycleJob.create(network, NetworkLifecycleOperation.STOP, "Test", List.of()).withStatus(NetworkLifecycleStatus.SUCCEEDED, "Complete");
        NetworkDefinition committed = network.nextRevision(network.members(), network.routingGroups(), network.syncRealms(), NetworkDesiredState.STOPPED);
        NetworkDefinition newer = committed.nextRevision(committed.members(), committed.routingGroups(), committed.syncRealms(), NetworkDesiredState.RUNNING);
        NetworkLifecycleJob future = NetworkLifecycleJob.create(committed, NetworkLifecycleOperation.START, "Test", List.of()).withStatus(NetworkLifecycleStatus.SUCCEEDED, "Complete");
        NetworkLifecycleJob drain = NetworkLifecycleJob.create(network, NetworkLifecycleOperation.DRAIN, "Test", List.of()).withStatus(NetworkLifecycleStatus.SUCCEEDED, "Complete");

        assertTrue(NetworkManager.shouldRecoverLifecycle(pending, network));
        assertTrue(NetworkManager.shouldRecoverLifecycle(pending, committed));
        assertFalse(NetworkManager.shouldRecoverLifecycle(pending, newer));
        assertFalse(NetworkManager.shouldRecoverLifecycle(future, network));
        assertFalse(NetworkManager.shouldRecoverLifecycle(drain, network));
    }

    @Test
    void lifecycleMachineOwnsAttemptStepAndCompletionTransitions() {
        NetworkDefinition network = network();
        AtomicLong time = new AtomicLong(100);
        NetworkLifecycleMachine machine = new NetworkLifecycleMachine(time::incrementAndGet);
        NetworkLifecycleJob ready = NetworkLifecycleJob.create(network, NetworkLifecycleOperation.STOP, "Test",
                new NetworkLifecyclePlanner().planMember(network, network.members().get(1), NetworkLifecycleOperation.STOP), time::incrementAndGet);

        NetworkLifecycleJob running = machine.start(ready);
        List<NetworkLifecycleStep> next = machine.next(running);
        NetworkLifecycleJob begun = machine.begin(running, next);
        NetworkLifecycleJob succeeded = machine.succeed(begun, next.getFirst(), false, "Stopped");
        NetworkLifecycleJob complete = machine.finish(succeeded);

        assertEquals(1, running.attempt());
        assertEquals(NetworkLifecycleStepStatus.RUNNING, begun.steps().getFirst().status());
        assertEquals(NetworkLifecycleStepStatus.SUCCEEDED, succeeded.steps().getFirst().status());
        assertEquals(NetworkLifecycleStatus.SUCCEEDED, complete.status());
    }

    @Test
    void interruptedStartsCannotBatchBackendsAcrossTheHubGate() {
        NetworkDefinition network = network();
        NetworkLifecycleMachine machine = new NetworkLifecycleMachine();
        NetworkLifecycleJob job = machine.start(NetworkLifecycleJob.create(network, NetworkLifecycleOperation.START, "Test", new NetworkLifecyclePlanner().plan(network, NetworkLifecycleOperation.START)));
        List<NetworkLifecycleStep> first = machine.next(job);
        assertEquals(1, first.size());
        assertEquals("proxy", first.getFirst().routeName());
        job = machine.succeed(machine.begin(job, first), first.getFirst(), false, "Started");
        job = machine.start(job.withStatus(NetworkLifecycleStatus.INTERRUPTED, "Interrupted"));
        assertEquals(List.of(NetworkLifecycleAction.HEALTH_GATE), machine.next(job).stream().map(NetworkLifecycleStep::action).toList());
        List<NetworkLifecycleStep> gate = machine.next(job);
        job = machine.succeed(machine.begin(job, gate), gate.getFirst(), false, "Healthy");
        assertEquals(List.of("lobby", "survival"), machine.next(job).stream().map(NetworkLifecycleStep::routeName).toList());
        job = machine.start(job.withStatus(NetworkLifecycleStatus.INTERRUPTED, "Interrupted After Hub Readiness"));
        assertEquals(List.of(NetworkLifecycleAction.HEALTH_GATE), machine.next(job).stream().map(NetworkLifecycleStep::action).toList());
    }

    private NetworkDefinition network() {
        String proxyInstanceId = UUID.randomUUID().toString();
        NetworkMember proxy = NetworkMember.proxy(proxyInstanceId, 25565);
        NetworkMember lobby = NetworkMember.backend(UUID.randomUUID().toString(), "lobby", NetworkMemberRole.LOBBY, 25566);
        NetworkMember survival = NetworkMember.backend(UUID.randomUUID().toString(), "survival", NetworkMemberRole.GAMEPLAY, 25567);
        NetworkDefinition base = NetworkDefinition.create("Network", proxyInstanceId, NetworkForwardingPolicy.secureDefault("secret"), List.of(NetworkEntryPoint.primary(25565)), List.of(proxy, lobby, survival));
        RoutingGroup fallback = new RoutingGroup("fallback", "Fallback", RoutingStrategy.ORDERED, List.of(lobby.nodeId()), Map.of(), "", Set.of(), "");
        return new NetworkDefinition(base.schemaVersion(), base.networkId(), base.name(), base.revision(), base.proxyInstanceId(), base.desiredState(), base.forwarding(), base.entryPoints(), base.members(), List.of(fallback), base.syncRealms(), base.runtime(), base.features(), base.createdAt(), base.updatedAt());
    }
}
