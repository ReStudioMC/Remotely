package redxax.oxy.remotely.network;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.network.config.NetworkConfigurationAdapters;
import redxax.oxy.remotely.settings.server.BrowserSafeYaml;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkMutationEngineTest {
    @Test
    void removesOnlyPresentOwnedKeys() {
        String instanceId = UUID.randomUUID().toString();
        String networkId = UUID.randomUUID().toString();
        NetworkConfigDocumentKey key = new NetworkConfigDocumentKey(instanceId, "velocity.toml");
        NetworkConfigMutation removal = new NetworkConfigMutation(instanceId, "velocity.toml", ConfigurationFormat.TOML, "servers.survival", "", "", false, true, "Remove Route", NetworkMutationAction.REMOVE);
        NetworkReconciliationPlan plan = new NetworkReconciliationPlan("", networkId, 1, 0, List.of(removal), List.of(), NetworkPlanStrategy.DETACH);
        NetworkMutationEngine engine = new NetworkMutationEngine(new NetworkConfigurationAdapters(BrowserSafeYaml::parse));

        NetworkReconciliationPlan absent = engine.resolveCurrentValues(plan, Map.of(key, "[servers]\nlobby = \"127.0.0.1:25566\"\n"));
        NetworkReconciliationPlan present = engine.resolveCurrentValues(plan, Map.of(key, "[servers]\nsurvival = \"127.0.0.1:25567\"\n"));

        assertTrue(absent.changes().isEmpty());
        assertEquals(1, present.changes().size());
        assertEquals("[servers]\n", engine.apply("[servers]\nsurvival = \"127.0.0.1:25567\"\n", present.changes()));
    }

    @Test
    void resolvesDesiredRoutesAfterClearingTheOwnedSection() {
        String instanceId = UUID.randomUUID().toString();
        String networkId = UUID.randomUUID().toString();
        NetworkConfigDocumentKey key = new NetworkConfigDocumentKey(instanceId, "velocity.toml");
        NetworkConfigMutation clear = new NetworkConfigMutation(instanceId, "velocity.toml", ConfigurationFormat.TOML, "servers.*", "", "", false, true, "Replace Routes", NetworkMutationAction.REMOVE);
        NetworkConfigMutation lobby = new NetworkConfigMutation(instanceId, "velocity.toml", ConfigurationFormat.TOML, "servers.lobby", "", "\"127.0.0.1:30066\"", false, true, "Add Lobby");
        NetworkReconciliationPlan plan = new NetworkReconciliationPlan("", networkId, 1, 0, List.of(clear, lobby), List.of());
        NetworkMutationEngine engine = new NetworkMutationEngine(new NetworkConfigurationAdapters(BrowserSafeYaml::parse));
        String source = "[servers]\nlobby = \"127.0.0.1:30066\"\nfactions = \"127.0.0.1:30067\"\n";

        NetworkReconciliationPlan resolved = engine.resolveCurrentValues(plan, Map.of(key, source));
        String updated = engine.apply(source, resolved.changes());

        assertEquals(2, resolved.changes().size());
        assertTrue(updated.contains("lobby = \"127.0.0.1:30066\""));
        assertFalse(updated.contains("factions"));
    }

    @Test
    void resolvesMissingManagedSectionAsExplicitEmptyTable() {
        String instanceId = UUID.randomUUID().toString();
        String networkId = UUID.randomUUID().toString();
        NetworkConfigDocumentKey key = new NetworkConfigDocumentKey(instanceId, "velocity.toml");
        NetworkConfigMutation clear = new NetworkConfigMutation(instanceId, "velocity.toml", ConfigurationFormat.TOML, "forced-hosts.*", "", "", false, true, "Replace Forced Hosts", NetworkMutationAction.REMOVE);
        NetworkReconciliationPlan plan = new NetworkReconciliationPlan("", networkId, 1, 0, List.of(clear), List.of());
        NetworkMutationEngine engine = new NetworkMutationEngine(new NetworkConfigurationAdapters(BrowserSafeYaml::parse));
        String source = "config-version = \"2.8\"\n\n[servers]\nlobby = \"127.0.0.1:25566\"\n";

        NetworkReconciliationPlan resolved = engine.resolveCurrentValues(plan, Map.of(key, source));
        String updated = engine.apply(source, resolved.changes());

        assertEquals(1, resolved.changes().size());
        assertTrue(updated.contains("[forced-hosts]"));
    }
}
