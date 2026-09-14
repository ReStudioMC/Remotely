package redxax.oxy.remotely;

import restudio.rebase.backend.FileExplorerProviders;

import java.util.Objects;

public final class RemotelySession implements AutoCloseable {
    private final RemotelyComposition composition;
    private final RemotelyClient client;
    private boolean initialized;
    private boolean closed;

    public RemotelySession(RemotelyComposition composition) {
        this.composition = Objects.requireNonNull(composition, "composition");
        client = new RemotelyClient(composition);
    }

    public synchronized RemotelyClient initialize() {
        if (closed) {
            throw new IllegalStateException("Session is closed");
        }
        if (!initialized) {
            try {
                client.initialize();
                initialized = true;
            } catch (RuntimeException exception) {
                if (RemotelyClient.INSTANCE == client) {
                    RemotelyClient.INSTANCE = null;
                }
                closeScheduler();
                closed = true;
                throw exception;
            }
        }
        return client;
    }

    public RemotelyComposition composition() {
        return composition;
    }

    public RemotelyClient client() {
        return client;
    }

    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (initialized) {
                client.shutdownAllTerminals();
            }
        } finally {
            initialized = false;
            FileExplorerProviders.clearServerIconResolver(composition.serverIconProvider());
            if (RemotelyClient.INSTANCE == client) {
                RemotelyClient.INSTANCE = null;
            }
            closeScheduler();
        }
    }

    private void closeScheduler() {
        if (!(composition.scheduler() instanceof AutoCloseable closeable)) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not close session scheduler", exception);
        }
    }
}
