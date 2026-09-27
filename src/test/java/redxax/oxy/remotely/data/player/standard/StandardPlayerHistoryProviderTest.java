package redxax.oxy.remotely.data.player.standard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import restudio.rebase.api.RebaseAPI;
import restudio.rebase.instance.Instance;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StandardPlayerHistoryProviderTest {
    @Test
    void stopsProcessingLinesAfterShutdown(@TempDir Path directory) {
        RebaseAPI api = (RebaseAPI) Proxy.newProxyInstance(RebaseAPI.class.getClassLoader(), new Class<?>[]{RebaseAPI.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "fileExists" -> CompletableFuture.completedFuture(false);
                    case "readFile" -> CompletableFuture.completedFuture("");
                    case "createDirectory", "writeFile" -> CompletableFuture.completedFuture(null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Instance instance = new Instance("Server", "1.21", directory.toString());
        StandardPlayerHistoryProvider history = new StandardPlayerHistoryProvider(instance, api, null, directory, name -> null);
        AtomicInteger matches = new AtomicInteger();
        history.registerPattern(Pattern.compile("PING"), (matcher, line, timestamp, number) -> matches.incrementAndGet());
        history.initialize();

        instance.onLogOutput(1, "PING");
        assertEquals(1, matches.get());

        history.shutdown();
        instance.onLogOutput(2, "PING");
        assertEquals(1, matches.get());
    }
}
