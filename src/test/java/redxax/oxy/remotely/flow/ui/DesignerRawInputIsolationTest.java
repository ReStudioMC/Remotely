package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.flow.data.ScoreboardDefinition;
import restudio.rescreen.platform.input.ReKey;
import restudio.rescreen.platform.input.ReKeyEvent;
import restudio.rescreen.platform.input.ReKeyLocation;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.theme.ThemeManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesignerRawInputIsolationTest {
    private static final Path DESIGNERS = Path.of("src", "main", "java", "redxax", "oxy", "remotely", "flow", "ui");

    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void slowScoreboardSnapshotCannotQueueReplayOrVersionRawInputAndOneFieldMutationPublishesOnce() throws Exception {
        ScoreboardDefinition scoreboard = new ScoreboardDefinition("runtime", "Runtime");
        ScoreboardDesignerScreen screen = new ScoreboardDesignerScreen(scoreboard);
        VersionedEditorDraft<ScoreboardDefinition> draft = scoreboardDraft(screen);
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CountDownLatch snapshotCompleted = new CountDownLatch(1);

        assertTrue(screen.requestCollaborationDocument(snapshot -> {
            snapshotEntered.countDown();
            await(releaseSnapshot);
            snapshotCompleted.countDown();
        }));
        draft.drain();
        Method updateObjective = ScoreboardDesignerScreen.class.getDeclaredMethod("updateObjective", String.class);
        updateObjective.setAccessible(true);
        long version;
        try {
            assertTrue(snapshotEntered.await(2, TimeUnit.SECONDS));
            version = screen.collaborationEditVersion();
            for (int index = 0; index < 128; index++) {
                double point = -1000.0 - index;
                screen.mouseClicked(mouse(screen, ReMouseEvent.Action.PRESSED, point, point, 0.0, 0.0));
                screen.mouseDragged(mouse(screen, ReMouseEvent.Action.DRAGGED, point, point, 1.0, 1.0));
                screen.mouseReleased(mouse(screen, ReMouseEvent.Action.RELEASED, point, point, 0.0, 0.0));
                screen.keyPressed(key(screen, index));
            }

            assertEquals(version, screen.collaborationEditVersion());
            assertTrue(deferred(draft).isEmpty());
            assertFalse(booleanField(draft, "backpressured"));
            assertFalse(booleanField(draft, "overflowed"));
            updateObjective.invoke(screen, "runtime_next");
            assertEquals(1, deferred(draft).size());
            assertEquals(version, screen.collaborationEditVersion());
        } finally {
            releaseSnapshot.countDown();
        }
        assertTrue(snapshotCompleted.await(2, TimeUnit.SECONDS));
        drainUntil(draft, draft::isSettled);
        assertEquals(version + 1L, screen.collaborationEditVersion());

        AtomicInteger publications = new AtomicInteger();
        AtomicReference<JsonObject> document = new AtomicReference<>();
        AtomicReference<Long> publishedVersion = new AtomicReference<>();
        assertTrue(screen.requestCollaborationDocument(snapshot -> {
            publications.incrementAndGet();
            document.set(snapshot.document());
            publishedVersion.set(snapshot.editVersion());
        }));
        drainUntil(draft, () -> publications.get() == 1 && draft.isSettled());

        assertEquals(1, publications.get());
        assertNotNull(document.get());
        assertEquals("runtime_next", document.get().get("objectiveId").getAsString());
        assertEquals(version + 1L, publishedVersion.get().longValue());
    }

    @Test
    void designerRawInputGatewaysNeverUseVersionedDraftDeferralOrMutationMarks() throws Exception {
        assertImmediate("ScoreboardDesignerScreen.java", "scoreboardDraft", List.of(
            "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput"));
        assertImmediate("TabDesignerScreen.java", "tabDraft", List.of(
            "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput"));
        assertImmediate("AdvancementDesignerScreen.java", "treeDraft", List.of(
            "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput"));
        assertImmediate("DialogDesignerScreen.java", "dialogDraft", List.of(
            "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput"));
        assertImmediate("GuiDesignerScreen.java", "guiDraft", List.of(
            "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput"));
        assertImmediate("FocusedJsonResourceDesignerScreen.java", "resourceDraft", List.of(
            "mouseClicked", "mouseReleased", "mouseDragged", "mouseScrolled", "keyPressed", "textInput"));
        assertImmediate("TextTemplateDesignerScreen.java", "resourceDraft", List.of("textInput"));
    }

    private static void assertImmediate(String file, String draft, List<String> methods) throws Exception {
        String source = Files.readString(DESIGNERS.resolve(file));
        for (String method : methods) {
            String body = method(source, method);
            assertFalse(body.contains(draft + ".defer("), file + " must not defer " + method);
            assertFalse(body.contains(draft + ".markMutation()"), file + " must not version raw " + method);
        }
    }

    private static String method(String source, String name) {
        int start = source.indexOf("public boolean " + name + "(");
        assertTrue(start >= 0, "Missing method " + name);
        int body = source.indexOf('{', start);
        assertTrue(body >= 0, "Missing method body " + name);
        int depth = 0;
        for (int index = body; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(start, index + 1);
            }
        }
        throw new IllegalStateException("Unclosed method " + name);
    }

    @SuppressWarnings("unchecked")
    private static VersionedEditorDraft<ScoreboardDefinition> scoreboardDraft(ScoreboardDesignerScreen screen) throws Exception {
        Field field = ScoreboardDesignerScreen.class.getDeclaredField("scoreboardDraft");
        field.setAccessible(true);
        return (VersionedEditorDraft<ScoreboardDefinition>) field.get(screen);
    }

    private static ArrayDeque<?> deferred(VersionedEditorDraft<?> draft) throws Exception {
        Field field = VersionedEditorDraft.class.getDeclaredField("deferred");
        field.setAccessible(true);
        return (ArrayDeque<?>) field.get(draft);
    }

    private static boolean booleanField(VersionedEditorDraft<?> draft, String name) throws Exception {
        Field field = VersionedEditorDraft.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(draft);
    }

    private static ReMouseEvent mouse(Object source, ReMouseEvent.Action action, double x, double y,
                                      double deltaX, double deltaY) {
        return new ReMouseEvent(source, source, 0L, ReModifierState.none(), action, x, y, deltaX, deltaY,
            ReMouseButton.LEFT, 0, 1);
    }

    private static ReKeyEvent key(Object source, long sequence) {
        return new ReKeyEvent(source, source, sequence, ReModifierState.none(), ReKeyEvent.Action.PRESSED,
            ReKey.A, 0, 0, ReKeyLocation.STANDARD, false);
    }

    private static void drainUntil(VersionedEditorDraft<?> draft, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            draft.drain();
            Thread.sleep(1L);
        }
        draft.drain();
        assertTrue(condition.getAsBoolean());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Snapshot Release Timed Out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
