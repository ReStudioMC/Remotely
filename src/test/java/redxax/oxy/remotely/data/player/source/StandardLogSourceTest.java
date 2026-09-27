package redxax.oxy.remotely.data.player.source;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.player.PlayerService;
import restudio.rebase.instance.Instance;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StandardLogSourceTest {
    @Test
    void stopsProcessingLinesAfterDisable() {
        Instance instance = new Instance("Server", "1.21", "/tmp/server");
        PlayerService service = new PlayerService();
        StandardLogSource source = new StandardLogSource(instance, null);
        source.init(service);
        source.enable();

        instance.onLogOutput(1, "UUID of player Alice is " + UUID.randomUUID());
        assertEquals(1, service.getRegistry().getAll().size());

        source.disable();
        instance.onLogOutput(2, "UUID of player Bob is " + UUID.randomUUID());
        assertEquals(1, service.getRegistry().getAll().size());
    }
}
