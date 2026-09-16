package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FlowManagerCreationNotificationThreadTest {
    private static final Path FLOW_MANAGER = Path.of("src/main/java/redxax/oxy/remotely/data/flow/FlowManager.java");

    @Test
    void pausedCreationNotificationsAreQueuedOnTheRenderThread() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String pause = methodBody(source, "private void pauseCreationPhase(");
        String renderDispatch = "ScreenManager.getInstance().execute(\n                () -> new Notification(\"Create Pending\", notificationMessage, Notification.Type.WARN))";

        assertTrue(pause.contains(renderDispatch));
        assertTrue(pause.indexOf(renderDispatch) != pause.lastIndexOf(renderDispatch));
        assertTrue(pause.contains("notify = transaction.phase != CreationPhase.PAUSED;"));
        assertFalse(pause.contains("enqueueCreationJournal(transaction,\n            () -> new Notification"));
        assertFalse(pause.contains("if (!queued) {\n            new Notification"));
    }

    @Test
    void terminalCreationFailureConstructsItsNotificationOnTheUiOwner() throws IOException {
        String source = Files.readString(FLOW_MANAGER).replace("\r\n", "\n");
        String failure = methodBody(source, "private void failCreation(");
        String dispatch = "ScreenManager.getInstance().execute(\n            () -> new Notification(\"Create\", notificationMessage, Notification.Type.ERROR))";

        assertTrue(failure.contains(dispatch));
        assertFalse(failure.contains("\n        new Notification(\"Create\""));
        assertTrue(failure.contains("coreGraphDocumentAuthoring.discard(transaction.locator)"));
        assertTrue(failure.indexOf("coreGraphDocumentAuthoring.discard(transaction.locator)")
            < failure.indexOf(dispatch));
        assertTrue(failure.indexOf(dispatch) < failure.indexOf("enqueueCreationJournal(transaction"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        assertTrue(open >= 0, signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, index + 1);
            }
        }
        throw new IllegalStateException(signature);
    }
}
