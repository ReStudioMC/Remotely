package redxax.oxy.remotely.servers;

import restudio.rebase.reproxy.ReProxyModels.Binding;

import java.util.Objects;

public record ReProxyTarget(String id, String name, int port, Binding binding, String connectionId, String bindingId) {
    public ReProxyTarget(String id, String name, int port, Binding binding) {
        this(id, name, port, binding, "", "");
    }

    public ReProxyTarget {
        Objects.requireNonNull(binding, "Server Target Is Required");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Server Is Required");
        name = name == null || name.isBlank() ? "Server" : name;
        connectionId = connectionId == null ? "" : connectionId;
        bindingId = bindingId == null ? "" : bindingId;
    }

    public String key() {
        return "LOCAL".equals(binding.kind()) ? "local:" + id : binding.serverId();
    }

    public boolean matches(Binding value) {
        if (value == null || !Objects.equals(binding.kind(), value.kind())) return false;
        if ("LOCAL".equals(binding.kind())) return Objects.equals(binding.instanceId(), value.instanceId());
        return Objects.equals(binding.serverId(), value.serverId()) && Objects.equals(binding.agentId(), value.agentId());
    }
}
