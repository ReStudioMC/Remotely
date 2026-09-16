package redxax.oxy.remotely.servers;

import java.time.Duration;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.SSHPacket;
import net.schmizz.sshj.connection.channel.forwarded.ConnectListener;
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder;
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.config.Config;
import restudio.rebase.instance.Instance;
import restudio.rescreen.util.Notification;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;


public class ReverseProxyManager {
    private static final Map<Integer, ProxySession> activeSessions = new HashMap<>();

    private record ProxySession(SSHClient client, int allocatedRemotePort) {}

    public static void reverse(int localPort, Runnable onComplete) {
        Notification notification = new Notification.Builder().message("Reversing " + localPort + "...").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build();

        if (activeSessions.containsKey(localPort)) {
            ProxySession existing = activeSessions.get(localPort);
            if (existing.client.isConnected()) {
                notification.change("Port " + localPort + " Is Already being forwarded", "Click To Disconnect", Notification.Type.WARN, () -> shutdown(localPort, onComplete));
                notification.loading = false;
                return;
            } else {
                shutdown(localPort, null);
            }
        }

        String host;
        String user;
        if (Config.customReverseProxy) {
            host = Config.proxyHost;
            user = Config.proxyUser;
        } else {
            host = "RedxAx.net";
            user = "tunnel";
        }
        int port = 722;

        new Thread(() -> {
            SSHClient client = new SSHClient();
            try {
                client.addHostKeyVerifier(new PromiscuousVerifier());
                client.setConnectTimeout(10000);
                client.connect(host, port);
                client.authPassword(user, "");

                if (!client.isConnected()) {
                    throw new Exception("Failed to connect to proxy host");
                }

                startReverseProxy(client, localPort, notification, onComplete);

            } catch (Exception e) {
                try { client.disconnect(); } catch (Exception ignored) {}
                notification.change("Error Setting Up Reverse Proxy: ", e.getMessage(), Notification.Type.ERROR, null);
                notification.loading = false;
                if (onComplete != null) onComplete.run();
            }
        }, "Reverse Proxy Thread").start();
    }

    private static void startReverseProxy(SSHClient client, int localPort, Notification notification, Runnable onComplete) {
        try {
            Buffer<Buffer.PlainBuffer> req = new Buffer.PlainBuffer()
                .putString("0.0.0.0")
                .putUInt32(0);

            SSHPacket reply = client.getConnection().sendGlobalRequest("tcpip-forward", true, req.getCompactData())
                .retrieve(10, TimeUnit.SECONDS);

            if (reply == null) {
                throw new Exception("Server refused port forwarding request");
            }

            int allocatedPort = (int) new Buffer.PlainBuffer(reply).readUInt32();

            RemotePortForwarder rpf = client.getRemotePortForwarder();
            RemotePortForwarder.Forward forward = new RemotePortForwarder.Forward("0.0.0.0", allocatedPort);
            SocketForwardingConnectListener listener = new SocketForwardingConnectListener(new InetSocketAddress("localhost", localPort));

            Field listenersField = RemotePortForwarder.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<RemotePortForwarder.Forward, ConnectListener> listeners = (Map<RemotePortForwarder.Forward, ConnectListener>) listenersField.get(rpf);
            listeners.put(forward, listener);

            listeners.put(new RemotePortForwarder.Forward("127.0.0.1", allocatedPort), listener);

            activeSessions.put(localPort, new ProxySession(client, allocatedPort));

            notification.change("Reverse Proxy Started on Port " + allocatedPort, "Click To Copy The IP", Notification.Type.SUCCESS, () -> RemotelyClient.INSTANCE.getHost().setClipboard(Config.proxyHost + ":" + allocatedPort));
            notification.loading = false;

        } catch (Exception e) {
            try { client.disconnect(); } catch (Exception ignored) {}
            notification.change("Error Setting Up Reverse Proxy: ", e.getMessage(), Notification.Type.ERROR, null);
            notification.loading = false;
        } finally {
            if (onComplete != null) onComplete.run();
        }
    }

    public static void shutdown(int localPort, Runnable onComplete) {
        ProxySession session = activeSessions.get(localPort);
        if (session != null && session.client.isConnected()) {
            try {
                Buffer<Buffer.PlainBuffer> req = new Buffer.PlainBuffer()
                    .putString("0.0.0.0")
                    .putUInt32(session.allocatedRemotePort);
                session.client.getConnection().sendGlobalRequest("cancel-tcpip-forward", true, req.getCompactData()).tryRetrieve(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            } finally {
                try {
                    session.client.disconnect();
                } catch (Exception ignored) {}
            }
            activeSessions.remove(localPort);
            new Notification("Reverse Proxy for port " + localPort + " shutdown successfully", Notification.Type.INFO);
        } else {
            if (session != null) activeSessions.remove(localPort);
            new Notification("Port " + localPort + " Isn't Forwarded", Notification.Type.WARN);
        }
        if (onComplete != null) onComplete.run();
    }

    public static void shutdownAll() {
        for (Map.Entry<Integer, ProxySession> entry : activeSessions.entrySet()) {
            try {
                entry.getValue().client.disconnect();
            } catch (Exception ignored) {}
        }
        activeSessions.clear();
        new Notification("All reverse proxies shutdown successfully", Notification.Type.INFO);
    }

    public static void listActivePorts() {
        if (activeSessions.isEmpty()) {
            new Notification("No active reverse proxies", Notification.Type.INFO);
            return;
        }
        StringBuilder ports = new StringBuilder();
        for (Map.Entry<Integer, ProxySession> entry : activeSessions.entrySet()) {
            int localPort = entry.getKey();
            ProxySession session = entry.getValue();
            if (session.client.isConnected()) {
                ports.append(localPort).append(" -> ").append(session.allocatedRemotePort).append(" (Active), ");
            } else {
                ports.append(localPort).append(" (Disconnected), ");
            }
        }
        new Notification("Ports Forwarded:", ports.toString(), Notification.Type.INFO);
    }

    public static boolean isPortForwarded(Instance instance) {
        if (instance == null || instance.getPort() <= 0) {
            return false;
        }
        ProxySession session = activeSessions.get(instance.getPort());
        return session != null && session.client.isConnected();
    }

    public static void reverse(Instance sInfo, Runnable onComplete) {
        if (sInfo == null || sInfo.getPort() <= 0) {
            new Notification("Invalid Server Port", "Make Sure To Configure The Port Correctly", Notification.Type.ERROR);
            if (onComplete != null) onComplete.run();
        } else {
            if (isPortForwarded(sInfo)) {
                shutdown(sInfo.getPort(), onComplete);
            } else {
                reverse(sInfo.getPort(), onComplete);
            }
        }
    }
}
