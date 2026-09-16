package redxax.oxy.remotely.data.flow;

import restudio.resync.contract.canonical.CanonicalDigests;
import restudio.resync.flow.cache.CatalogCacheKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ReSyncCatalogPublicationReceiptOutbox {
    public static final int DEFAULT_MAX_ENTRIES = 128;

    private final int maximumEntries;
    private final List<Entry> entries = new ArrayList<>();

    public ReSyncCatalogPublicationReceiptOutbox() {
        this(DEFAULT_MAX_ENTRIES);
    }

    public ReSyncCatalogPublicationReceiptOutbox(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("Receipt outbox capacity must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public synchronized boolean stageReceived(CatalogCacheKey key, long revision, byte[] canonicalBytes) {
        return stageReceived(key, revision, publicationIdentity(key, revision, canonicalBytes));
    }

    synchronized boolean stageReceived(CatalogCacheKey key, long revision, PublicationIdentity identity) {
        Reservation reservation = reserveReceived(key, revision, identity);
        return reservation != null && publishReceived(reservation);
    }

    synchronized Reservation reserveReceived(CatalogCacheKey key, long revision, PublicationIdentity identity) {
        validateIdentity(key, revision, identity);
        Entry entry = find(key, revision, identity);
        if (entry == null) {
            if (entries.size() >= maximumEntries) {
                return null;
            }
            entry = new Entry(key, revision, identity);
            entries.add(entry);
        }
        return entry.reservation();
    }

    synchronized boolean publishReceived(Reservation reservation) {
        Entry entry = find(reservation);
        if (entry == null) {
            return false;
        }
        entry.published = true;
        return true;
    }

    synchronized boolean release(Reservation reservation) {
        Entry entry = find(reservation);
        return entry != null && !entry.published && entries.remove(entry);
    }

    public synchronized boolean stageTerminal(CatalogCacheKey key, long revision, byte[] canonicalBytes,
                                               Terminal terminal, String diagnosticCode) {
        return stageTerminal(key, revision, publicationIdentity(key, revision, canonicalBytes), terminal,
            diagnosticCode);
    }

    synchronized boolean stageTerminal(CatalogCacheKey key, long revision, PublicationIdentity identity,
                                       Terminal terminal, String diagnosticCode) {
        validateIdentity(key, revision, identity);
        Objects.requireNonNull(terminal, "Receipt terminal is required");
        String diagnostic = diagnosticCode == null ? "" : diagnosticCode;
        if (!diagnostic.equals(diagnostic.strip())) {
            throw new IllegalArgumentException("Receipt diagnostic code must be canonical");
        }
        if (terminal == Terminal.REJECTED && diagnostic.isBlank()) {
            throw new IllegalArgumentException("Rejected receipt requires a diagnostic code");
        }
        if (terminal == Terminal.APPLIED && !diagnostic.isBlank()) {
            throw new IllegalArgumentException("Applied receipt cannot carry a diagnostic code");
        }
        Entry entry = find(key, revision, identity);
        if (entry == null) {
            if (entries.size() >= maximumEntries) {
                return false;
            }
            entry = new Entry(key, revision, identity);
            entries.add(entry);
        }
        entry.published = true;
        if (entry.terminal == null || (entry.terminal == Terminal.REJECTED && terminal == Terminal.APPLIED)) {
            entry.terminal = terminal;
            entry.diagnosticCode = diagnostic;
            entry.terminalDelivered = false;
        }
        return true;
    }

    public synchronized List<Pending> pending() {
        return entries.stream().filter(entry -> entry.published).map(Entry::pending).toList();
    }

    public synchronized boolean markReceivedDelivered(Pending pending) {
        Entry entry = find(pending);
        if (entry == null) {
            return false;
        }
        entry.receivedDelivered = true;
        removeIfComplete(entry);
        return true;
    }

    public synchronized boolean markTerminalDelivered(Pending pending) {
        Entry entry = find(pending);
        if (entry == null || entry.terminal == null || entry.terminal != pending.terminal()
            || !entry.diagnosticCode.equals(pending.diagnosticCode())) {
            return false;
        }
        entry.terminalDelivered = true;
        removeIfComplete(entry);
        return true;
    }

    public synchronized int size() {
        return entries.size();
    }

    private PublicationIdentity publicationIdentity(CatalogCacheKey key, long revision, byte[] canonicalBytes) {
        Objects.requireNonNull(key, "Receipt publication key is required");
        if (revision < 0) {
            throw new IllegalArgumentException("Receipt publication revision must not be negative");
        }
        Objects.requireNonNull(canonicalBytes, "Receipt canonical bytes are required");
        if (canonicalBytes.length == 0) {
            throw new IllegalArgumentException("Receipt canonical bytes cannot be empty");
        }
        return PublicationIdentity.of(canonicalBytes);
    }

    private void validateIdentity(CatalogCacheKey key, long revision, PublicationIdentity identity) {
        Objects.requireNonNull(key, "Receipt publication key is required");
        if (revision < 0) {
            throw new IllegalArgumentException("Receipt publication revision must not be negative");
        }
        Objects.requireNonNull(identity, "Receipt publication identity is required");
    }

    private Entry find(CatalogCacheKey key, long revision, PublicationIdentity identity) {
        return entries.stream().filter(entry -> entry.key.equals(key) && entry.revision == revision
            && entry.identity.equals(identity)).findFirst().orElse(null);
    }

    private Entry find(Pending pending) {
        if (pending == null) {
            return null;
        }
        return entries.stream().filter(entry -> entry.key.equals(pending.key()) && entry.revision == pending.revision()
            && entry.identity.equals(pending.identity())).findFirst().orElse(null);
    }

    private Entry find(Reservation reservation) {
        if (reservation == null) {
            return null;
        }
        return entries.stream().filter(entry -> entry.key.equals(reservation.key())
            && entry.revision == reservation.revision() && entry.identity.equals(reservation.identity()))
            .findFirst().orElse(null);
    }

    private void removeIfComplete(Entry entry) {
        if (entry.receivedDelivered && entry.terminal != null && entry.terminalDelivered) {
            entries.remove(entry);
        }
    }

    public enum Terminal {
        APPLIED,
        REJECTED
    }

    public record Pending(CatalogCacheKey key, long revision, PublicationIdentity identity, Terminal terminal,
                          String diagnosticCode, boolean receivedDelivered, boolean terminalDelivered) {
        public Pending {
            key = Objects.requireNonNull(key, "Receipt publication key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Receipt publication revision must not be negative");
            }
            identity = Objects.requireNonNull(identity, "Receipt publication identity is required");
            diagnosticCode = diagnosticCode == null ? "" : diagnosticCode;
        }
    }

    record Reservation(CatalogCacheKey key, long revision, PublicationIdentity identity) {
        Reservation {
            key = Objects.requireNonNull(key, "Receipt publication key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Receipt publication revision must not be negative");
            }
            identity = Objects.requireNonNull(identity, "Receipt publication identity is required");
        }
    }

    public record PublicationIdentity(int canonicalLength, byte[] sha256) {
        public PublicationIdentity {
            if (canonicalLength < 1) {
                throw new IllegalArgumentException("Receipt canonical length must be positive");
            }
            sha256 = Objects.requireNonNull(sha256, "Receipt publication hash is required").clone();
            if (sha256.length != 32) {
                throw new IllegalArgumentException("Receipt publication hash must be SHA-256");
            }
        }

        static PublicationIdentity of(byte[] canonicalBytes) {
            return new PublicationIdentity(canonicalBytes.length, CanonicalDigests.sha256(canonicalBytes));
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof PublicationIdentity other && canonicalLength == other.canonicalLength
                && Arrays.equals(sha256, other.sha256);
        }

        @Override
        public int hashCode() {
            return 31 * Integer.hashCode(canonicalLength) + Arrays.hashCode(sha256);
        }
    }

    private static final class Entry {
        private final CatalogCacheKey key;
        private final long revision;
        private final PublicationIdentity identity;
        private boolean published;
        private boolean receivedDelivered;
        private Terminal terminal;
        private String diagnosticCode = "";
        private boolean terminalDelivered;

        private Entry(CatalogCacheKey key, long revision, PublicationIdentity identity) {
            this.key = Objects.requireNonNull(key, "Receipt publication key is required");
            if (revision < 0) {
                throw new IllegalArgumentException("Receipt publication revision must not be negative");
            }
            this.revision = revision;
            this.identity = Objects.requireNonNull(identity, "Receipt publication identity is required");
        }

        private Pending pending() {
            return new Pending(key, revision, identity, terminal, diagnosticCode, receivedDelivered, terminalDelivered);
        }

        private Reservation reservation() {
            return new Reservation(key, revision, identity);
        }
    }
}
