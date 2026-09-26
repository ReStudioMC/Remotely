package redxax.oxy.remotely.data.flow;

public interface ReSyncCatalogPublicationDecoder extends AutoCloseable {
    void decode(byte[] canonicalBytes, Callback callback);

    void cancelPending();

    @Override
    void close();

    interface Callback {
        void part(int index, int total, byte[] canonicalBytes);

        void node(int offset, int total, byte[] canonicalBytes);

        void completed(byte[] sha256);

        void failed(String reason);
    }
}
