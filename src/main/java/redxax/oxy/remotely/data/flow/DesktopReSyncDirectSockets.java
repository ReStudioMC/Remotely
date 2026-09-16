package redxax.oxy.remotely.data.flow;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;

public final class DesktopReSyncDirectSockets {
    private DesktopReSyncDirectSockets() {
    }

    public static void install() {
        ReSyncDirectSockets.connector = new ReSyncDirectSockets.Connector() {
            @Override
            public Object open(URI uri, ReSyncDirectSockets.Handler handler) {
                return new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshake) {
                        handler.onOpen(this);
                    }

                    @Override
                    public void onMessage(String message) {
                    }

                    @Override
                    public void onMessage(ByteBuffer bytes) {
                        handler.onMessage(this, bytes);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        handler.onClose(this, code, reason, remote);
                    }

                    @Override
                    public void onError(Exception exception) {
                        handler.onError(this, exception);
                    }
                };
            }

            @Override
            public void connect(Object socket) {
                if (socket instanceof WebSocketClient client) {
                    client.connect();
                }
            }

            @Override
            public void close(Object socket) {
                if (socket instanceof WebSocketClient client) {
                    client.close();
                }
            }

            @Override
            public boolean isOpen(Object socket) {
                return socket instanceof WebSocketClient client && client.isOpen();
            }

            @Override
            public void send(Object socket, byte[] frame) {
                if (socket instanceof WebSocketClient client) {
                    client.send(frame);
                }
            }
        };
    }
}
