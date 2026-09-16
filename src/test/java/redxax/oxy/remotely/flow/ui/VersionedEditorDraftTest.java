package redxax.oxy.remotely.flow.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedEditorDraftTest {
    @Test
    void saveCheckpointsKeepClickTimePayloadAndReplayLaterEditsInOrder() throws Exception {
        MutableDraft initial = new MutableDraft("first");
        AtomicReference<MutableDraft> active = new AtomicReference<>(initial);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondSaved = new CountDownLatch(1);
        List<String> payloads = new ArrayList<>();
        VersionedEditorDraft<MutableDraft> draft = new VersionedEditorDraft<>(initial, value -> {
            if ("first".equals(value.value)) {
                firstStarted.countDown();
                await(firstRelease);
            }
            return value.value;
        }, MutableDraft::new, (previous, replacement) -> active.set(replacement), failure -> {}, () -> {});

        assertTrue(draft.capture("gui", "menu", snapshot -> payloads.add(snapshot.serialize(value -> value))));
        assertTrue(firstStarted.await(2L, TimeUnit.SECONDS));
        assertTrue(draft.defer(() -> {
            draft.markMutation();
            active.get().value = "second";
        }));
        assertTrue(draft.defer(() -> draft.capture("gui", "menu", snapshot -> {
            payloads.add(snapshot.serialize(value -> value));
            secondSaved.countDown();
        })));
        assertTrue(draft.defer(() -> {
            draft.markMutation();
            active.get().value = "third";
        }));

        firstRelease.countDown();
        drainUntil(draft, () -> secondSaved.getCount() == 0L);
        drainUntil(draft, () -> !draft.isFrozen());

        assertEquals(List.of("first", "second"), payloads);
        assertEquals("third", active.get().value);
    }

    @Test
    void payloadIsWorkerOwnedAndAcknowledgementRejectsSupersededSnapshots() throws Exception {
        AtomicReference<VersionedEditorDraft.SaveSnapshot> first = new AtomicReference<>();
        AtomicReference<VersionedEditorDraft.SaveSnapshot> second = new AtomicReference<>();
        CountDownLatch saved = new CountDownLatch(1);
        MutableDraft initial = new MutableDraft("one");
        VersionedEditorDraft<MutableDraft> draft = new VersionedEditorDraft<>(initial, value -> value.value,
            MutableDraft::new, (previous, replacement) -> {}, failure -> {}, () -> {});

        draft.capture("tab", "main", snapshot -> first.set(snapshot));
        drainUntil(draft, () -> !draft.isFrozen());
        draft.capture("tab", "main", snapshot -> {
            second.set(snapshot);
            saved.countDown();
        });
        assertTrue(saved.await(2L, TimeUnit.SECONDS));
        drainUntil(draft, () -> !draft.isFrozen());

        assertFalse(first.get().isCurrent());
        assertFalse(first.get().compareAndMarkSaved());
        assertTrue(second.get().isCurrent());
        assertTrue(second.get().compareAndMarkSaved());
        assertThrows(IllegalStateException.class, () -> second.get().serialize(value -> value));
    }

    @Test
    void failedPreparationReturnsOwnershipBeforeReplayingInput() {
        MutableDraft initial = new MutableDraft("before");
        AtomicReference<MutableDraft> active = new AtomicReference<>(initial);
        AtomicReference<VersionedEditorDraft.Failure> failure = new AtomicReference<>();
        VersionedEditorDraft<MutableDraft> draft = new VersionedEditorDraft<>(initial, value -> {
            throw new IllegalStateException("broken");
        }, MutableDraft::new, (previous, replacement) -> active.set(replacement), failure::set, () -> {});

        draft.capture("scoreboard", "sidebar", snapshot -> {});
        assertTrue(draft.defer(() -> active.get().value = "after"));
        drainUntil(draft, () -> !draft.isFrozen());

        assertEquals("after", active.get().value);
        assertInstanceOf(IllegalStateException.class, failure.get().cause());
    }

    @Test
    void failedPreparationKeepsTheExactSaveRequest() {
        Object firstRequest = new Object();
        Object secondRequest = new Object();
        AtomicReference<VersionedEditorDraft.Failure> firstFailure = new AtomicReference<>();
        AtomicReference<VersionedEditorDraft.Failure> secondFailure = new AtomicReference<>();
        VersionedEditorDraft<MutableDraft> first = new VersionedEditorDraft<>(new MutableDraft("first"), value -> {
            throw new IllegalStateException("first");
        }, MutableDraft::new, (previous, replacement) -> {}, firstFailure::set, () -> {});
        VersionedEditorDraft<MutableDraft> second = new VersionedEditorDraft<>(new MutableDraft("second"), value -> {
            throw new IllegalStateException("second");
        }, MutableDraft::new, (previous, replacement) -> {}, secondFailure::set, () -> {});

        first.capture("gui", "menu", firstRequest, snapshot -> {});
        second.capture("gui", "menu", secondRequest, snapshot -> {});
        drainUntil(first, () -> !first.isFrozen());
        drainUntil(second, () -> !second.isFrozen());

        assertEquals(firstRequest, firstFailure.get().request());
        assertEquals(secondRequest, secondFailure.get().request());
    }

    @Test
    void sameResourceSnapshotsFromDifferentEditorsKeepSubmissionOrder() throws Exception {
        CountDownLatch saved = new CountDownLatch(2);
        List<String> payloads = java.util.Collections.synchronizedList(new ArrayList<>());
        VersionedEditorDraft<MutableDraft> first = draft("first");
        VersionedEditorDraft<MutableDraft> second = draft("second");

        first.capture("gui", "menu", snapshot -> {
            payloads.add(snapshot.serialize(value -> value));
            saved.countDown();
        });
        second.capture("gui", "menu", snapshot -> {
            payloads.add(snapshot.serialize(value -> value));
            saved.countDown();
        });

        assertTrue(saved.await(2L, TimeUnit.SECONDS));
        drainUntil(first, () -> !first.isFrozen());
        drainUntil(second, () -> !second.isFrozen());
        assertEquals(List.of("first", "second"), payloads);
    }

    @Test
    void renderOwnershipRejectsCrossThreadMutation() throws Exception {
        VersionedEditorDraft<MutableDraft> draft = new VersionedEditorDraft<>(new MutableDraft("value"), value -> value.value,
            MutableDraft::new, (previous, replacement) -> {}, failure -> {}, () -> {});
        draft.markMutation();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                draft.markMutation();
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        });

        thread.start();
        thread.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test
    void typedKeysKeepSameIdSnapshotsIsolated() throws Exception {
        CountDownLatch saved = new CountDownLatch(2);
        List<VersionedEditorDraft.TypedKey> keys = java.util.Collections.synchronizedList(new ArrayList<>());
        VersionedEditorDraft<MutableDraft> gui = draft("gui");
        VersionedEditorDraft<MutableDraft> tab = draft("tab");

        gui.capture("gui", "main", snapshot -> {
            keys.add(snapshot.key());
            saved.countDown();
        });
        tab.capture("tab", "main", snapshot -> {
            keys.add(snapshot.key());
            saved.countDown();
        });
        assertTrue(saved.await(2L, TimeUnit.SECONDS));
        drainUntil(gui, () -> !gui.isFrozen());
        drainUntil(tab, () -> !tab.isFrozen());

        assertTrue(keys.contains(new VersionedEditorDraft.TypedKey("gui", "main")));
        assertTrue(keys.contains(new VersionedEditorDraft.TypedKey("tab", "main")));
    }

    @Test
    void boundedInputUsesVisibleBackpressureAndPreservesEveryAdmittedAction() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger replayed = new AtomicInteger();
        AtomicInteger backpressure = new AtomicInteger();
        VersionedEditorDraft<MutableDraft> draft = new VersionedEditorDraft<>(new MutableDraft("value"), value -> {
            started.countDown();
            await(release);
            return value.value;
        }, MutableDraft::new, (previous, replacement) -> {}, failure -> {}, backpressure::incrementAndGet);

        draft.capture("gui", "menu", snapshot -> {});
        assertTrue(started.await(2L, TimeUnit.SECONDS));
        for (int index = 0; index < 512; index++) {
            assertTrue(draft.defer(replayed::incrementAndGet));
        }
        assertTrue(draft.defer(replayed::incrementAndGet));
        draft.drain();

        assertEquals(2, backpressure.get());
        release.countDown();
        drainUntil(draft, draft::isSettled);
        assertEquals(512, replayed.get());
    }

    @Test
    void authoritativeRebasePreservesPostClickEdits() throws Exception {
        Gson gson = new Gson();
        JsonObject initial = new JsonObject();
        initial.addProperty("title", "click");
        AtomicReference<JsonObject> active = new AtomicReference<>(initial);
        Object request = new Object();
        CountDownLatch saved = new CountDownLatch(1);
        VersionedEditorDraft<JsonObject> draft = new VersionedEditorDraft<>(initial, gson::toJson,
            payload -> gson.fromJson(payload, JsonObject.class), (previous, replacement) -> active.set(replacement),
            failure -> {}, () -> {});

        draft.capture("gui", "menu", request, snapshot -> saved.countDown());
        assertTrue(saved.await(2L, TimeUnit.SECONDS));
        drainUntil(draft, () -> !draft.isFrozen());
        draft.markMutation();
        active.get().addProperty("title", "post-click");
        JsonObject authoritative = new JsonObject();
        authoritative.addProperty("title", "click");
        authoritative.addProperty("normalized", true);

        assertTrue(draft.acknowledge(request, () -> gson.toJson(authoritative)));
        drainUntil(draft, draft::isSettled);

        assertEquals("post-click", active.get().get("title").getAsString());
        assertTrue(active.get().get("normalized").getAsBoolean());
    }

    @Test
    void failedAuthoritativeRebaseRetainsCheckpointAndGatesLaterSave() throws Exception {
        Gson gson = new Gson();
        JsonObject initial = new JsonObject();
        initial.addProperty("title", "click");
        Object request = new Object();
        AtomicInteger rebaseFailures = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        CountDownLatch saved = new CountDownLatch(1);
        VersionedEditorDraft<JsonObject> draft = new VersionedEditorDraft<>(initial, gson::toJson,
            payload -> gson.fromJson(payload, JsonObject.class), (previous, replacement) -> {}, failure -> {
                if (failure.stage() == VersionedEditorDraft.Stage.REBASE) {
                    rebaseFailures.incrementAndGet();
                }
            }, busy::incrementAndGet);

        draft.capture("gui", "menu", request, snapshot -> saved.countDown());
        assertTrue(saved.await(2L, TimeUnit.SECONDS));
        drainUntil(draft, () -> !draft.isFrozen());
        assertTrue(draft.acknowledge(request, () -> {
            throw new IllegalStateException("authority unavailable");
        }));
        drainUntil(draft, () -> rebaseFailures.get() == 1 && !draft.isFrozen());

        assertFalse(draft.capture("gui", "menu", new Object(), snapshot -> {}));
        assertEquals(1, busy.get());
        draft.close();
    }

    private static void drainUntil(VersionedEditorDraft<?> draft, Condition condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            draft.drain();
            Thread.onSpinWait();
        }
        draft.drain();
        assertTrue(condition.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static VersionedEditorDraft<MutableDraft> draft(String value) {
        MutableDraft initial = new MutableDraft(value);
        return new VersionedEditorDraft<>(initial, draft -> draft.value, MutableDraft::new,
            (previous, replacement) -> {}, failure -> {}, () -> {});
    }

    private interface Condition {
        boolean get();
    }

    private static final class MutableDraft {
        private String value;

        private MutableDraft(String value) {
            this.value = value;
        }
    }
}
