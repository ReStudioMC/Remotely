package redxax.oxy.remotely.network;

import redxax.oxy.remotely.config.RemotelyConfigStore;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkOperationState;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HostedNetworkPendingStore {
    private static final String PREFIX = "remotely.network.pending.";
    private final RemotelyConfigStore config;
    private final Map<String, Pending> resident = new HashMap<>();
    private final Map<String, PendingAttach> attachments = new HashMap<>();
    private final Set<String> loaded = new HashSet<>();

    public HostedNetworkPendingStore(RemotelyConfigStore config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public synchronized Pending current(String account) {
        String key = key(account);
        if (!loaded.contains(key)) {
            String stored = config.readPendingNetwork(key);
            if (stored != null && !stored.isBlank()) {
                int separator = stored.lastIndexOf('|');
                if (separator < 0) throw new IllegalArgumentException("Pending Network Request Is Invalid");
                String body = new String(Base64.getUrlDecoder().decode(stored.substring(0, separator)), StandardCharsets.UTF_8);
                String state = stored.substring(separator + 1);
                NetworkOperationState terminal = state.isBlank() ? null : terminalState(state);
                NetworkCommand.Create command = HostedNetworkClient.createFromBody(body);
                resident.put(key, new Pending(command, body, terminal));
            }
            loaded.add(key);
        }
        return resident.get(key);
    }

    public synchronized Pending admit(String account, NetworkCommand.Create command) {
        Pending existing = current(account);
        if (existing != null) {
            if (!existing.command().requestId().equals(command.requestId())
                    || !existing.body().equals(HostedNetworkClient.bodyFor(command))) {
                throw new IllegalStateException("Finish The Pending Network Request Before Creating Another Network");
            }
            return existing;
        }
        String key = key(account);
        Pending pending = new Pending(command, HostedNetworkClient.bodyFor(command), null);
        config.writePendingNetwork(key, encode(pending));
        resident.put(key, pending);
        return pending;
    }

    public synchronized PendingAttach attachment(String account, String networkId) {
        String key = attachmentKey(account, networkId);
        if (!loaded.contains(key)) {
            String stored = config.readPendingNetwork(key);
            if (stored != null && !stored.isBlank()) {
                int separator = stored.lastIndexOf('|');
                if (separator < 0) throw new IllegalArgumentException("Pending Server Request Is Invalid");
                String body = new String(Base64.getUrlDecoder().decode(stored.substring(0, separator)), StandardCharsets.UTF_8);
                String state = stored.substring(separator + 1);
                NetworkOperationState terminal = state.isBlank() ? null : terminalState(state);
                NetworkCommand.Attach command = HostedNetworkClient.attachFromBody(body);
                if (!networkId.equals(command.networkId())) throw new IllegalArgumentException("Pending Server Network Does Not Match");
                attachments.put(key, new PendingAttach(command, body, terminal));
            }
            loaded.add(key);
        }
        return attachments.get(key);
    }

    public synchronized PendingAttach currentAttachment(String account, String networkId) {
        return attachment(account, networkId);
    }

    public synchronized PendingAttach admitAttachment(String account, NetworkCommand.Attach command) {
        Objects.requireNonNull(command, "command");
        PendingAttach existing = attachment(account, command.networkId());
        if (existing != null) {
            if (!existing.command().equals(command)) {
                throw new IllegalStateException("Finish The Pending Server Request Before Adding Another Server");
            }
            return existing;
        }
        String key = attachmentKey(account, command.networkId());
        PendingAttach pending = new PendingAttach(command, HostedNetworkClient.bodyFor(command), null);
        config.writePendingNetwork(key, encode(pending.body(), pending.terminal()));
        attachments.put(key, pending);
        return pending;
    }

    public synchronized void markAttachmentTerminal(String account, String requestId, String networkId, NetworkOperationState state) {
        NetworkOperationState terminal = terminalState(Objects.requireNonNull(state, "state").name());
        PendingAttach pending = attachment(account, networkId);
        if (pending == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) return;
        PendingAttach marked = new PendingAttach(pending.command(), pending.body(), terminal);
        String key = attachmentKey(account, networkId);
        config.writePendingNetwork(key, encode(marked.body(), marked.terminal()));
        attachments.put(key, marked);
    }

    public synchronized void clearAttachment(String account, String requestId, String networkId) {
        PendingAttach pending = attachment(account, networkId);
        if (pending == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) return;
        String key = attachmentKey(account, networkId);
        config.removePendingNetwork(key);
        attachments.remove(key);
    }

    public synchronized void discardAttachment(String account, String requestId, String networkId) {
        PendingAttach pending = attachment(account, networkId);
        if (pending == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) {
            throw new IllegalStateException("Saved Server Request Changed. Reopen Network Setup");
        }
        clearAttachment(account, requestId, networkId);
    }

    public synchronized void acknowledgeAttachment(String account, String requestId, String networkId) {
        PendingAttach pending = attachment(account, networkId);
        if (pending == null || pending.terminal() == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) {
            throw new IllegalStateException("Review The Server Outcome Before Adding Another Server");
        }
        clearAttachment(account, requestId, networkId);
    }

    public synchronized void markTerminal(String account, String requestId, String networkId, NetworkOperationState state) {
        NetworkOperationState terminal = terminalState(Objects.requireNonNull(state, "state").name());
        Pending pending = current(account);
        if (pending == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) return;
        Pending marked = new Pending(pending.command(), pending.body(), terminal);
        String key = key(account);
        config.writePendingNetwork(key, encode(marked));
        resident.put(key, marked);
    }

    public synchronized void acknowledgeTerminal(String account, String requestId, String networkId) {
        Pending pending = current(account);
        if (pending == null || pending.terminal() == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) {
            throw new IllegalStateException("Review The Network Outcome Before Starting Another Network");
        }
        clearTerminal(account, requestId, networkId);
    }

    public synchronized void discard(String account, String requestId, String networkId) {
        Pending pending = current(account);
        if (pending == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) {
            throw new IllegalStateException("Saved Network Request Changed. Reopen Network Setup");
        }
        clearTerminal(account, requestId, networkId);
    }

    public synchronized void clearTerminal(String account, String requestId, String networkId) {
        Pending pending = current(account);
        if (pending == null || !pending.command().requestId().equals(requestId)
                || !pending.command().networkId().equals(networkId)) return;
        String key = key(account);
        config.removePendingNetwork(key);
        resident.remove(key);
    }

    private static String key(String account) {
        if (account == null || !account.startsWith("true:") || account.length() <= 5) {
            throw new IllegalStateException("Sign In To Create A Reactor Network");
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(account.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(Pending pending) {
        return encode(pending.body(), pending.terminal());
    }

    private static String encode(String body, NetworkOperationState terminal) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))
                + "|" + (terminal == null ? "" : terminal.name());
    }

    private static String attachmentKey(String account, String networkId) {
        if (networkId == null || networkId.isBlank() || networkId.length() > 255
                || !networkId.equals(networkId.trim()) || networkId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Network Identity Is Invalid");
        }
        return key(account) + ".attach." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(networkId.getBytes(StandardCharsets.UTF_8));
    }

    private static NetworkOperationState terminalState(String value) {
        NetworkOperationState state = NetworkOperationState.valueOf(value);
        if (state != NetworkOperationState.FAILED && state != NetworkOperationState.ROLLED_BACK
                && state != NetworkOperationState.NEEDS_REVIEW) {
            throw new IllegalArgumentException("Pending Network Outcome Is Invalid");
        }
        return state;
    }

    public record Pending(NetworkCommand.Create command, String body, NetworkOperationState terminal) {
        public Pending {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(body, "body");
        }
    }

    public record PendingAttach(NetworkCommand.Attach command, String body, NetworkOperationState terminal) {
        public PendingAttach {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(body, "body");
        }
    }
}
