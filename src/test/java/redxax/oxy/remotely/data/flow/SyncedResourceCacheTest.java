package redxax.oxy.remotely.data.flow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncedResourceCacheTest {

    @Test
    void authoritativeServerListRemovesServerMembershipButPreservesEveryLocalDraft() {
        SyncedResourceCache<Resource> cache = new SyncedResourceCache<>(Resource::id, Resource::id);
        cache.cache("server", new Resource("deleted"));
        cache.putInDraft("server", new Resource("deleted"));
        cache.putInDraft("server", new Resource("new_local"));

        cache.applyServerList("server", List.of());

        assertTrue(cache.containsKey("server", "deleted"));
        assertNotNull(cache.getFromDraft("server", "deleted"));
        assertEquals(SyncedResourceState.DIRTY, cache.getState("server", "deleted"));
        assertTrue(cache.containsKey("server", "new_local"));
    }

    @Test
    void authorityEpochResetDropsOnlyAuthoritativeValues() {
        SyncedResourceCache<Resource> cache = new SyncedResourceCache<>(Resource::id, Resource::id);
        cache.cache("server", new Resource("authoritative"));
        cache.putInDraft("server", new Resource("draft"));
        cache.setPendingParent("server", "draft", "parent");

        cache.clearAuthoritativeForServer("server");

        assertTrue(cache.getFromCache("server", "authoritative") == null);
        assertNotNull(cache.getFromDraft("server", "draft"));
        assertEquals("parent", cache.removePendingParent("server", "draft"));
        assertEquals(SyncedResourceState.DIRTY, cache.getState("server", "draft"));
    }

    @Test
    void serverIdsIncludesDraftOnlyServersInDeterministicOrder() {
        SyncedResourceCache<Resource> cache = new SyncedResourceCache<>(Resource::id, Resource::id);
        cache.putInDraft("z:server", new Resource("draft"));
        cache.cache("a:server", new Resource("cached"));
        cache.markSaving("m:server", "pending");

        assertEquals(List.of("a:server", "m:server", "z:server"), new ArrayList<>(cache.serverIds()));
    }

    @Test
    void clearAllRemovesAuthoritativeAndDraftState() {
        SyncedResourceCache<Resource> cache = new SyncedResourceCache<>(Resource::id, Resource::id);
        cache.cache("server", new Resource("cached"));
        cache.putInDraft("server", new Resource("draft"));
        cache.setPendingParent("server", "draft", "parent");

        cache.clearAll();

        assertTrue(cache.serverIds().isEmpty());
        assertTrue(cache.getForServer("server").isEmpty());
        assertTrue(cache.getResourceIds("server").isEmpty());
    }

    @Test
    void pointLookupCopiesOnlyTheSelectedDraftAndNeverAliasesCacheState() {
        AtomicInteger copies = new AtomicInteger();
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> {
                copies.incrementAndGet();
                return new MutableResource(resource.id(), resource.value());
            });
        cache.cache("server", new MutableResource("shared/path", "server"));
        cache.putInDraft("server", new MutableResource("shared/path", "draft"));
        cache.cache("server", new MutableResource("other", "untouched"));
        copies.set(0);

        MutableResource selected = cache.getOwned("server", "shared/path");

        assertEquals(1, copies.get());
        assertEquals("draft", selected.value());
        selected.value("changed");
        assertEquals("draft", cache.getOwned("server", "shared/path").value());
    }

    @Test
    void familySnapshotDefersCopiesAndKeepsImmutableMembership() {
        AtomicInteger copies = new AtomicInteger();
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> {
                copies.incrementAndGet();
                return new MutableResource(resource.id(), resource.value());
            });
        cache.cache("server", new MutableResource("first", "one"));
        cache.cache("server", new MutableResource("second", "two"));
        copies.set(0);

        Map<String, MutableResource> snapshot = cache.getForServer("server");

        assertEquals(0, copies.get());
        assertEquals(List.of("first", "second"), snapshot.keySet().stream().sorted().toList());
        assertEquals(0, copies.get());
        assertEquals("one", snapshot.get("first").value());
        assertEquals(1, copies.get());
        cache.remove("server", "second");
        assertTrue(snapshot.containsKey("second"));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.put("third", new MutableResource("third", "three")));
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.entrySet().iterator().next().setValue(new MutableResource("changed", "changed")));
    }

    @Test
    void saveLeaseIsOpaqueVersionedAndCannotCommitOverANewerDraft() {
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> new MutableResource(resource.id(), resource.value()));
        MutableResource editor = new MutableResource("resource/path", "first");

        SyncedResourceCache.SaveLease<MutableResource> first = cache.putInDraft("server", editor);
        editor.value("outside");
        SyncedResourceCache.SaveLease<MutableResource> second = cache.putInDraft("server", new MutableResource("resource/path", "second"));

        assertEquals("first", first.serialize(MutableResource::value));
        assertEquals("second", second.serialize(MutableResource::value));
        assertFalse(first.isCurrent());
        assertTrue(second.isCurrent());
        assertFalse(cache.compareAndMarkSaved("server", "resource/path", first.version()));
        assertEquals("second", cache.getFromDraft("server", "resource/path").value());
        assertTrue(cache.compareAndMarkSaved("server", "resource/path", second.version()));
        assertEquals("second", cache.getFromCache("server", "resource/path").value());
    }

    @Test
    void rollbackRestoresTheExactInFlightDraftLease() {
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> new MutableResource(resource.id(), resource.value()));
        SyncedResourceCache.SaveLease<MutableResource> lease = cache.putInDraft("server", new MutableResource("resource", "draft"));
        SyncedResourceCache.EntrySnapshot<MutableResource> snapshot = cache.snapshot("server", "resource");

        cache.remove("server", "resource");
        cache.restore(snapshot);

        assertSame(lease, cache.getDraftLease("server", "resource"));
        assertTrue(lease.isCurrent());
    }

    @Test
    void saveAcknowledgementRetainsTheProjectedAuthoritativeValue() {
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> new MutableResource(resource.id(), resource.value()));
        SyncedResourceCache.SaveLease<MutableResource> lease = cache.putInDraft("server", new MutableResource("resource", "submitted"));

        cache.cache("server", new MutableResource("resource", "normalized"));

        assertTrue(cache.compareAndMarkSaved("server", "resource", lease.version()));
        assertEquals("normalized", cache.getFromCache("server", "resource").value());
        assertFalse(lease.isCurrent());
    }

    @Test
    void olderRenameLeaseCannotRestoreOverANewerEdit() {
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> new MutableResource(resource.id(), resource.value()));
        cache.cache("server", new MutableResource("old", "original"));
        assertTrue(cache.rename("server", "old", "renamed", MutableResource::id));
        SyncedResourceCache.SaveLease<MutableResource> renameLease = cache.getDraftLease("server", "renamed");

        SyncedResourceCache.SaveLease<MutableResource> newerLease = cache.putInDraft("server",
            new MutableResource("renamed", "newer edit"));
        if (renameLease.isCurrent()) cache.restoreRename("server", "renamed", "old", MutableResource::id);

        assertFalse(renameLease.isCurrent());
        assertTrue(newerLease.isCurrent());
        assertEquals("newer edit", cache.getFromDraft("server", "renamed").value());
        assertTrue(cache.getFromDraft("server", "old") == null);
    }

    @Test
    void snapshotCompareAndPutRebasesWithoutOverwritingANewerRoot() {
        SyncedResourceCache<MutableResource> cache = new SyncedResourceCache<>(MutableResource::id, MutableResource::id,
            resource -> new MutableResource(resource.id(), resource.value()));
        cache.cache("server", new MutableResource("resource", "base"));
        SyncedResourceCache.SnapshotLease<MutableResource> stale = cache.snapshotLease("server", "resource");
        cache.putInDraft("server", new MutableResource("resource", "concurrent"));

        assertTrue(stale.compareAndPutInDraft(new MutableResource("resource", "lost")) == null);
        assertEquals("concurrent", cache.getFromDraft("server", "resource").value());

        SyncedResourceCache.SnapshotLease<MutableResource> current = cache.snapshotLease("server", "resource");
        SyncedResourceCache.SaveLease<MutableResource> published = current.compareAndPutInDraft(
            new MutableResource("resource", "rebased"));
        assertNotNull(published);
        assertTrue(published.isCurrent());
        assertEquals("rebased", cache.getFromDraft("server", "resource").value());
    }

    private record Resource(String id) {
    }

    private static final class MutableResource {
        private String id;
        private String value;

        private MutableResource(String id, String value) {
            this.id = id;
            this.value = value;
        }

        private String id() {
            return id;
        }

        private void id(String id) {
            this.id = id;
        }

        private String value() {
            return value;
        }

        private void value(String value) {
            this.value = value;
        }
    }
}
