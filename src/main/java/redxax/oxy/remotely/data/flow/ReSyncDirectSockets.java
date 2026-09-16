package redxax.oxy.remotely.data.flow;

import java.net.URI;
import java.nio.ByteBuffer;

public final class ReSyncDirectSockets {
    public static volatile Connector connector = Connector.UNAVAILABLE;

    private ReSyncDirectSockets() {
    }

    public interface Handler {
        void onOpen(Object socket);

        void onMessage(Object socket, ByteBuffer bytes);

        void onClose(Object socket, int code, String reason, boolean remote);

        void onError(Object socket, Exception exception);
    }

    public interface Connector {
        Object open(URI uri, Handler handler);

        void connect(Object socket);

        void close(Object socket);

        boolean isOpen(Object socket);

        void send(Object socket, byte[] frame);

        Connector UNAVAILABLE = new Connector() {
            @Override
            public Object open(URI uri, Handler handler) {
                return null;
            }

            @Override
            public void connect(Object socket) {
            }

            @Override
            public void close(Object socket) {
            }

            @Override
            public boolean isOpen(Object socket) {
                return false;
            }

            @Override
            public void send(Object socket, byte[] frame) {
            }
        };
    }
}
