package redxax.oxy.remotely.data.player.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFileSnapshotStateTest {
    @Test
    void partialSnapshotRetainsAcceptedContentUntilCompleteReplacement() {
        PlayerFileSnapshotState state = new PlayerFileSnapshotState();
        String original = "[{\"name\":\"First\"}]";
        String complete = "[{\"name\":\"First\"},{\"name\":\"Second\"}]";

        long originalGeneration = state.observe();
        assertTrue(state.accept(originalGeneration, original).changed());

        long partialGeneration = state.observe();
        assertTrue(state.reject(partialGeneration, 0, 0L).current());
        assertEquals(original, state.acceptedContent());

        long completeGeneration = state.observe();
        assertTrue(state.accept(completeGeneration, complete).changed());
        assertEquals(complete, state.acceptedContent());
        assertFalse(state.accept(completeGeneration, complete).changed());
        assertFalse(state.accept(partialGeneration, "[]").current());
        assertEquals(complete, state.acceptedContent());
    }

    @Test
    void repeatedTransientFailuresRetryAndRateLimitDurableReports() {
        PlayerFileSnapshotState state = new PlayerFileSnapshotState();
        long generation = state.observe();

        PlayerFileSnapshotState.Rejected first = state.reject(generation, 0, 0L);
        PlayerFileSnapshotState.Rejected second = state.reject(generation, 1, 250L);
        PlayerFileSnapshotState.Rejected third = state.reject(generation, 2, 750L);

        assertTrue(first.retry());
        assertFalse(first.report());
        assertTrue(second.retry());
        assertFalse(second.report());
        assertFalse(third.retry());
        assertTrue(third.report());

        long repeatedGeneration = state.observe();
        assertFalse(state.reject(repeatedGeneration, 0, 1_000L).report());
        assertTrue(state.reject(repeatedGeneration, 1, 30_751L).report());
    }
}
