package redxax.oxy.remotely.data.player.source;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.player.PlayerService;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.instance.Instance;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StandardFileSourceTest {
    @Test
    void readsPresentPlayerFilesFromOneDirectoryListing() {
        AtomicInteger listings = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        RebaseAPI api = (RebaseAPI) Proxy.newProxyInstance(RebaseAPI.class.getClassLoader(), new Class<?>[]{RebaseAPI.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listDirectory" -> {
                        listings.incrementAndGet();
                        yield CompletableFuture.completedFuture(List.of(
                                new RebaseAPI.FileEntry(Path.of("/server/ops.json"), false, "", "", "ops.json"),
                                new RebaseAPI.FileEntry(Path.of("/server/usercache.json"), false, "", "", "usercache.json"),
                                new RebaseAPI.FileEntry(Path.of("/server/whitelist.json"), true, "", "", "whitelist.json")));
                    }
                    case "readFile" -> {
                        reads.incrementAndGet();
                        yield CompletableFuture.completedFuture("[]");
                    }
                    default -> throw new AssertionError("Unexpected API call: " + method.getName());
                });
        StandardFileSource source = new StandardFileSource(new Instance("Server", "1.21", "/server"), api);
        source.init(new PlayerService());

        source.enable();
        source.refresh();
        source.disable();
        source.refresh();

        assertEquals(2, listings.get());
        assertEquals(4, reads.get());
    }
}
