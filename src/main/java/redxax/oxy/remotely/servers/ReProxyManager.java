package redxax.oxy.remotely.servers;

import java.time.Duration;
import redxax.oxy.remotely.util.BrowserWork;
import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.RemotelyClient;
import restudio.rebase.instance.Instance;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import restudio.rescreen.platform.Async;
import restudio.rebase.platform.jvm.JvmAsyncBridge;


public class ReProxyManager {
    private static final byte FRAME_AUTH_OK = 2;
    private static final byte FRAME_AUTH_ERROR = 3;
    private static final byte FRAME_PONG = 5;
    private static final byte FRAME_OPEN_STREAM = 6;
    private static final byte FRAME_STREAM_DATA = 7;
    private static final byte FRAME_CLOSE_STREAM = 8;
    private static final byte FRAME_STREAM_ERROR = 9;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int MAX_RELAY_FRAME_BYTES = 1_048_576;
    private static final long AUTH_TIMEOUT_SECONDS = 15;
    private static final String PREFERRED_DOMAIN_KEY = "reproxy.preferredDomainId";
    private static final String QUICK_SERVER_DOMAIN_KEY = "reproxy.quickServerDomainId";
    private static final Map<Integer, ReProxySession> activeSessions = BrowserSafeState.map();
    private static final Set<Integer> startingPorts = BrowserSafeState.set();

    private record ReProxySession(Instance instance, String tunnelId, String domain, int localPort, WebSocket webSocket, Map<Long, Socket> streams, BrowserSafeState.BooleanValue closing, BrowserSafeState.IntegerValue reconnects, boolean notifications) {
    }

    public static void start(Instance instance, Runnable onComplete) {
        start(instance, onComplete, new BrowserSafeState.IntegerValue(), true, true);
    }

    public static void startQuietly(Instance instance, Runnable onComplete) {
        start(instance, onComplete, new BrowserSafeState.IntegerValue(), false, false);
    }

    private static void start(Instance instance, Runnable onComplete, BrowserSafeState.IntegerValue reconnects, boolean notifications, boolean toggleActive) {
        if (instance == null || instance.getPort() <= 0 || instance.getPort() > 65535) {
            notify(notifications, "Invalid Server Port", Notification.Type.ERROR);
            if (onComplete != null) onComplete.run();
            return;
        }
        int localPort = instance.getPort();
        ReProxySession activeSession = activeSessions.get(localPort);
        if (activeSession != null) {
            if (sameInstance(activeSession.instance(), instance)) {
                if (toggleActive) {
                    stop(localPort, onComplete, notifications);
                } else if (onComplete != null) {
                    onComplete.run();
                }
            } else {
                notify(notifications, "Server Port Already Forwarded", Notification.Type.WARN);
                if (onComplete != null) onComplete.run();
            }
            return;
        }
        if (!startingPorts.add(localPort)) {
            notify(notifications, "ReProxy Is Starting", Notification.Type.INFO);
            if (onComplete != null) onComplete.run();
            return;
        }
        Runnable completion = completion(localPort, onComplete);
        if (!ReStudio.getInstance().isAuthenticated()) {
            notify(notifications, "ReStudio Login Required", Notification.Type.WARN);
            completion.run();
            return;
        }
        Async<Notification> notificationFuture = Async.pending();
        if (notifications) {
            ScreenManager.getInstance().execute(() -> notificationFuture.complete(new Notification.Builder().message("Starting ReProxy").type(Notification.Type.INFO).loading(true).autoSlideOut(false).build()));
        } else {
            notificationFuture.complete(null);
        }
        notificationFuture.thenAccept(notification -> ReStudio.getInstance().getApi().listReProxyDomains()
                .thenCompose(domains -> JvmAsyncBridge.toFuture(resolveDomain(instance, domains, notifications)))
                .thenCompose(domain -> ReStudio.getInstance().getApi().startReProxyTunnel(domain.id, instance.getPort(), "MINECRAFT_JAVA_TCP"))
                .thenAccept(response -> connect(instance, response, notification, completion, reconnects, notifications))
                .exceptionally(ex -> {
                    change(notification, "ReProxy Unavailable", cleanMessage(ex), Notification.Type.ERROR, null, false);
                    completion.run();
                    return null;
                })).exceptionally(ex -> {
                    completion.run();
                    return null;
                });
    }

    public static void stop(int localPort, Runnable onComplete) {
        stop(localPort, onComplete, true);
    }

    public static void stopQuietly(int localPort, Runnable onComplete) {
        stop(localPort, onComplete, false);
    }

    private static void stop(int localPort, Runnable onComplete, boolean notifications) {
        ReProxySession session = activeSessions.remove(localPort);
        if (session == null) {
            notify(notifications, "ReProxy Offline", Notification.Type.INFO);
            if (onComplete != null) onComplete.run();
            return;
        }
        notifications = notifications && session.notifications();
        for (Socket socket : session.streams().values()) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
        session.closing().set(true);
        session.webSocket().abort();
        boolean showNotifications = notifications;
        ReStudio.getInstance().getApi().stopReProxyTunnel(session.tunnelId()).whenComplete((ignored, ex) -> {
            notify(showNotifications, "ReProxy Offline", Notification.Type.INFO);
            if (onComplete != null) onComplete.run();
        });
    }

    public static void stopAll() {
        List<Integer> ports = new ArrayList<>(activeSessions.keySet());
        for (int port : ports) {
            stop(port, null);
        }
    }

    public static boolean isForwarded(Instance instance) {
        if (instance == null) {
            return false;
        }
        ReProxySession session = activeSessions.get(instance.getPort());
        return session != null && sameInstance(session.instance(), instance);
    }

    public static boolean isForwarded(int localPort) {
        return activeSessions.containsKey(localPort);
    }

    public static List<String> listActiveTunnels() {
        return activeSessions.values().stream().map(ReProxySession::domain).toList();
    }

    public static String getForwardedAddress(Instance instance) {
        if (instance == null) {
            return "";
        }
        ReProxySession session = activeSessions.get(instance.getPort());
        return session == null || !sameInstance(session.instance(), instance) ? "" : session.domain();
    }

    private static Async<ServerModels.ReProxyDomain> resolveDomain(Instance instance, List<ServerModels.ReProxyDomain> domains, boolean notifications) {
        String preferredDomainId = instance.getSettings().getProperty(domainPreferenceKey(instance), "");
        if (!preferredDomainId.isBlank()) {
            ServerModels.ReProxyDomain preferred = domains.stream()
                    .filter(domain -> Objects.equals(domain.id, preferredDomainId))
                    .filter(domain -> "ACTIVE".equalsIgnoreCase(domain.status))
                    .findFirst()
                    .orElse(null);
            if (preferred != null) {
                return Async.completed(preferred);
            }
        }
        if (isQuickServer(instance)) {
            return createDomainWithFallbacks(instance, sanitizeSubdomain(instance.getName()), 0, notifications);
        }
        List<ServerModels.ReProxyDomain> activeDomains = domains.stream()
                .filter(domain -> "ACTIVE".equalsIgnoreCase(domain.status))
                .sorted(Comparator.comparing(domain -> domain.subdomain))
                .toList();
        if (!activeDomains.isEmpty()) {
            ServerModels.ReProxyDomain domain = activeDomains.getFirst();
            if (activeDomains.size() > 1 && preferredDomainId.isBlank()) {
                notify(notifications, "Manage Domains", Notification.Type.INFO);
            }
            persistPreferredDomain(instance, domain.id);
            return Async.completed(domain);
        }
        return createDomainWithFallbacks(instance, sanitizeSubdomain(instance.getName()), 0, notifications);
    }

    private static Async<ServerModels.ReProxyDomain> createDomainWithFallbacks(Instance instance, String base, int attempt, boolean notifications) {
        List<String> candidates = domainCandidates(base);
        if (attempt >= candidates.size()) {
            notify(notifications, "Domain Taken", Notification.Type.WARN);
            return Async.failed(new IllegalStateException("Domain Taken"));
        }
        return JvmAsyncBridge.fromFuture(ReStudio.getInstance().getApi().createReProxyDomain(candidates.get(attempt)))
                .thenApply(domain -> {
                    persistPreferredDomain(instance, domain.id);
                    return domain;
                })
                .exceptionallyCompose(error -> {
                    String message = cleanMessage(error).toLowerCase(Locale.ROOT);
                    if (message.contains("taken") || message.contains("conflict") || message.contains("domain failed")) {
                        return createDomainWithFallbacks(instance, base, attempt + 1, notifications);
                    }
                    return Async.failed(error);
                });
    }

    private static List<String> domainCandidates(String base) {
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        return List.of(trimDomain(base), trimDomain(base + "-2"), trimDomain(base + "-3"), trimDomain(base + "-" + random), trimDomain(base + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 4)));
    }

    private static String trimDomain(String value) {
        String trimmed = value.length() > 32 ? value.substring(0, 32) : value;
        return trimmed.replaceAll("-$", "");
    }

    public static void setPreferredDomain(Instance instance, String domainId) {
        persistPreferredDomain(instance, domainId);
    }

    public static String getPreferredDomainId(Instance instance) {
        return instance == null ? "" : instance.getSettings().getProperty(domainPreferenceKey(instance), "");
    }

    private static void persistPreferredDomain(Instance instance, String domainId) {
        if (instance != null && domainId != null && !domainId.isBlank()) {
            instance.getSettings().setProperty(domainPreferenceKey(instance), domainId);
            instance.save().join();
        }
    }

    private static String domainPreferenceKey(Instance instance) {
        return isQuickServer(instance) ? QUICK_SERVER_DOMAIN_KEY : PREFERRED_DOMAIN_KEY;
    }

    private static boolean isQuickServer(Instance instance) {
        return instance != null && "true".equalsIgnoreCase(instance.getSettings().getProperty("quickServer.enabled"));
    }

    private static void connect(Instance instance, ServerModels.ReProxyStartTunnelResponse response, Notification notification, Runnable onComplete, BrowserSafeState.IntegerValue reconnects, boolean notifications) {
        int localPort = instance.getPort();
        String host = response.assignedNode.tunnelHost != null ? response.assignedNode.tunnelHost : response.assignedNode.fqdn;
        String scheme = response.assignedNode.tunnelScheme != null && !response.assignedNode.tunnelScheme.isBlank()
                ? response.assignedNode.tunnelScheme
                : host.startsWith("localhost") || host.startsWith("127.0.0.1") ? "ws" : "wss";
        URI uri = URI.create(scheme + "://" + host + ":" + response.assignedNode.tunnelPort + "/reproxy/tunnel?tunnelId=" + response.tunnelId);
        String displayUri = scheme + "://" + host + ":" + response.assignedNode.tunnelPort + "/reproxy/tunnel";
        String reachabilityError = tunnelReachabilityError(host, response.assignedNode.tunnelPort);
        if (reachabilityError != null) {
            change(notification, "ReProxy Unavailable", displayUri + " - " + reachabilityError, Notification.Type.ERROR, null, false);
            if (onComplete != null) onComplete.run();
            return;
        }
        Map<Long, Socket> streams = BrowserSafeState.map();
        BrowserSafeState.BooleanValue closing = new BrowserSafeState.BooleanValue(false);
        Listener listener = new Listener(instance, response, streams, notification, onComplete, closing, reconnects, notifications);
        HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer " + response.token).buildAsync(uri, listener)
                .thenAccept(webSocket -> {
                    if (closing.get()) {
                        webSocket.abort();
                        return;
                    }
                    activeSessions.put(localPort, new ReProxySession(instance, response.tunnelId, response.domain, localPort, webSocket, streams, closing, reconnects, notifications));
                    BrowserWork.schedule(Duration.ofSeconds(AUTH_TIMEOUT_SECONDS), () -> listener.checkAuthTimeout(webSocket));
                })
                .exceptionally(ex -> {
                    change(notification, "ReProxy Unavailable", displayUri + " - " + cleanMessage(ex), Notification.Type.ERROR, null, false);
                    if (onComplete != null) onComplete.run();
                    return null;
                });
    }

    private static void reconnect(Instance instance, BrowserSafeState.IntegerValue reconnects, boolean notifications) {
        int attempt = reconnects.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            notify(notifications, "ReProxy Offline", "Reconnect Failed", Notification.Type.ERROR);
            return;
        }
        BrowserWork.schedule(Duration.ofSeconds(Math.min(30, attempt * 3L)), () -> start(instance, null, reconnects, notifications, false));
    }

    private static Runnable completion(int localPort, Runnable onComplete) {
        BrowserSafeState.BooleanValue completed = new BrowserSafeState.BooleanValue();
        return () -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            startingPorts.remove(localPort);
            if (onComplete != null) {
                onComplete.run();
            }
        };
    }

    static boolean sameInstance(Instance left, Instance right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        String leftId = left.getInstanceId();
        String rightId = right.getInstanceId();
        if (leftId != null && !leftId.isBlank() && rightId != null && !rightId.isBlank()) {
            return leftId.equals(rightId);
        }
        return Objects.equals(left.getPath(), right.getPath());
    }

    private static void removeSession(int localPort, WebSocket webSocket) {
        activeSessions.computeIfPresent(localPort, (port, session) -> session.webSocket() == webSocket ? null : session);
    }

    private static String sanitizeSubdomain(String name) {
        String value = name == null ? "server" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (value.length() < 3) {
            value = "server-" + value;
        }
        if (value.length() > 32) {
            value = value.substring(0, 32).replaceAll("-$", "");
        }
        return value;
    }

    private static ByteBuffer encode(byte type, long streamId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(13 + payload.length);
        buffer.put(type);
        buffer.putLong(streamId);
        buffer.putInt(payload.length);
        buffer.put(payload);
        buffer.flip();
        return buffer;
    }

    private static String cleanMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = current.getClass().getSimpleName();
        }
        return message == null || message.isBlank() ? "Request Failed" : message;
    }

    private static void change(Notification notification, String message, String description, Notification.Type type, Runnable onClick, boolean loading) {
        if (notification == null) {
            return;
        }
        ScreenManager.getInstance().execute(() -> {
            notification.change(message, description, type, onClick);
            notification.loading = loading;
        });
    }

    private static void notify(String message, Notification.Type type) {
        notify(true, message, type);
    }

    private static void notify(String message, String description, Notification.Type type) {
        notify(true, message, description, type);
    }

    private static void notify(boolean notifications, String message, Notification.Type type) {
        if (notifications) {
            ScreenManager.getInstance().execute(() -> new Notification(message, type));
        }
    }

    private static void notify(boolean notifications, String message, String description, Notification.Type type) {
        if (notifications) {
            ScreenManager.getInstance().execute(() -> new Notification(message, description, type));
        }
    }

    private static String tunnelReachabilityError(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 4000);
            return null;
        } catch (Exception e) {
            return "Cannot Connect To " + host + ":" + port + " (" + cleanMessage(e) + ")";
        }
    }

    private static class Listener implements WebSocket.Listener {
        private final int localPort;
        private final Instance instance;
        private final ServerModels.ReProxyStartTunnelResponse response;
        private final Map<Long, Socket> streams;
        private final Notification notification;
        private final Runnable onComplete;
        private final BrowserSafeState.BooleanValue closing;
        private final BrowserSafeState.BooleanValue authenticated = new BrowserSafeState.BooleanValue();
        private final BrowserSafeState.IntegerValue reconnects;
        private final boolean notifications;
        private final Object sendLock = new Object();
        private final ByteArrayOutputStream binaryMessage = new ByteArrayOutputStream();

        private Listener(Instance instance, ServerModels.ReProxyStartTunnelResponse response, Map<Long, Socket> streams, Notification notification, Runnable onComplete, BrowserSafeState.BooleanValue closing, BrowserSafeState.IntegerValue reconnects, boolean notifications) {
            this.instance = instance;
            this.localPort = instance.getPort();
            this.response = response;
            this.streams = streams;
            this.notification = notification;
            this.onComplete = onComplete;
            this.closing = closing;
            this.reconnects = reconnects;
            this.notifications = notifications;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (binaryMessage.size() + data.remaining() > MAX_RELAY_FRAME_BYTES) {
                protocolError(webSocket, "Relay Frame Is Too Large");
                return null;
            }
            byte[] fragment = new byte[data.remaining()];
            data.get(fragment);
            binaryMessage.writeBytes(fragment);
            if (!last) {
                webSocket.request(1);
                return null;
            }
            byte[] message = binaryMessage.toByteArray();
            binaryMessage.reset();
            if (message.length < 13) {
                protocolError(webSocket, "Relay Frame Is Incomplete");
                return null;
            }
            ByteBuffer frame = ByteBuffer.wrap(message);
            byte type = frame.get();
            long streamId = frame.getLong();
            int length = frame.getInt();
            if (length < 0 || length != frame.remaining()) {
                protocolError(webSocket, "Relay Frame Is Invalid");
                return null;
            }
            byte[] payload = new byte[length];
            frame.get(payload);
            if (type == FRAME_AUTH_OK) {
                authenticated.set(true);
                change(notification, "ReProxy Online", "Copy Address", Notification.Type.SUCCESS, () -> RemotelyClient.INSTANCE.getHost().setClipboard(response.domain), false);
                if (onComplete != null) onComplete.run();
            } else if (type == FRAME_AUTH_ERROR) {
                closing.set(true);
                removeSession(localPort, webSocket);
                change(notification, "ReProxy Unavailable", new String(payload, StandardCharsets.UTF_8), Notification.Type.ERROR, null, false);
                webSocket.abort();
                if (onComplete != null) onComplete.run();
            } else if (type == FRAME_OPEN_STREAM) {
                openStream(webSocket, streamId);
            } else if (type == FRAME_STREAM_DATA) {
                writeStream(webSocket, streamId, payload);
            } else if (type == FRAME_CLOSE_STREAM || type == FRAME_STREAM_ERROR) {
                closeStream(streamId);
            }
            webSocket.request(1);
            return null;
        }

        private void protocolError(WebSocket webSocket, String message) {
            closing.set(true);
            removeSession(localPort, webSocket);
            change(notification, "ReProxy Unavailable", message, Notification.Type.ERROR, null, false);
            webSocket.abort();
            if (onComplete != null) onComplete.run();
        }

        private void checkAuthTimeout(WebSocket webSocket) {
            if (authenticated.get() || !closing.compareAndSet(false, true)) {
                return;
            }
            removeSession(localPort, webSocket);
            change(notification, "ReProxy Reconnecting", "Relay Authentication Timed Out", Notification.Type.WARN, null, false);
            webSocket.abort();
            if (onComplete != null) onComplete.run();
            reconnect(instance, reconnects, notifications);
        }

        private void openStream(WebSocket webSocket, long streamId) {
            try {
                Socket socket = new Socket("127.0.0.1", localPort);
                streams.put(streamId, socket);
                Thread.ofVirtual().name("ReProxy Stream " + streamId).start(() -> readLocal(webSocket, streamId, socket));
            } catch (Exception e) {
                sendFrame(webSocket, FRAME_STREAM_ERROR, streamId, cleanMessage(e).getBytes(StandardCharsets.UTF_8));
            }
        }

        private void readLocal(WebSocket webSocket, long streamId, Socket socket) {
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = socket.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);
                    sendFrame(webSocket, FRAME_STREAM_DATA, streamId, chunk);
                }
            } catch (Exception ignored) {
            } finally {
                sendFrame(webSocket, FRAME_CLOSE_STREAM, streamId, new byte[0]);
                closeStream(streamId);
            }
        }

        private void writeStream(WebSocket webSocket, long streamId, byte[] payload) {
            Socket socket = streams.get(streamId);
            if (socket == null) {
                return;
            }
            try {
                OutputStream output = socket.getOutputStream();
                output.write(payload);
                output.flush();
            } catch (Exception e) {
                sendFrame(webSocket, FRAME_STREAM_ERROR, streamId, cleanMessage(e).getBytes(StandardCharsets.UTF_8));
                closeStream(streamId);
            }
        }

        private void sendFrame(WebSocket webSocket, byte type, long streamId, byte[] payload) {
            synchronized (sendLock) {
                webSocket.sendBinary(encode(type, streamId, payload), true).join();
            }
        }

        private void closeStream(long streamId) {
            Socket socket = streams.remove(streamId);
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            removeSession(localPort, webSocket);
            if (closing.compareAndSet(false, true)) {
                change(notification, "ReProxy Reconnecting", cleanMessage(error), Notification.Type.WARN, null, false);
                reconnect(instance, reconnects, notifications);
            } else if (reconnects.get() == 0) {
                change(notification, "ReProxy Offline", cleanMessage(error), Notification.Type.WARN, null, false);
            }
            if (onComplete != null) onComplete.run();
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            removeSession(localPort, webSocket);
            for (Socket socket : streams.values()) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
            if (closing.compareAndSet(false, true)) {
                ReProxyManager.notify(notifications, "ReProxy Reconnecting", Notification.Type.WARN);
                reconnect(instance, reconnects, notifications);
            }
            if (onComplete != null) onComplete.run();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }
    }
}
