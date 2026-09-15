package redxax.oxy.remotely.network;

public record NetworkConfigMutation(String instanceId, String path, ConfigurationFormat format, String key, String currentValue, String desiredValue, boolean sensitive, boolean restartRequired, String description, NetworkMutationAction action, boolean currentPresent) {
    public NetworkConfigMutation(String instanceId, String path, ConfigurationFormat format, String key, String currentValue, String desiredValue, boolean sensitive, boolean restartRequired, String description) {
        this(instanceId, path, format, key, currentValue, desiredValue, sensitive, restartRequired, description, NetworkMutationAction.SET, true);
    }

    public NetworkConfigMutation(String instanceId, String path, ConfigurationFormat format, String key, String currentValue, String desiredValue, boolean sensitive, boolean restartRequired, String description, NetworkMutationAction action) {
        this(instanceId, path, format, key, currentValue, desiredValue, sensitive, restartRequired, description, action, true);
    }

    public NetworkConfigMutation {
        instanceId = normalize(instanceId);
        path = normalize(path);
        format = format == null ? ConfigurationFormat.PROPERTIES : format;
        key = normalize(key);
        currentValue = currentValue == null ? "" : currentValue;
        desiredValue = desiredValue == null ? "" : desiredValue;
        description = normalize(description);
        action = action == null ? NetworkMutationAction.SET : action;
    }

    public boolean changesValue() {
        return action == NetworkMutationAction.REMOVE ? currentPresent : !currentValue.equals(desiredValue);
    }

    public String displayCurrentValue() {
        return sensitive && !currentValue.isBlank() ? "••••••••" : currentValue;
    }

    public String displayDesiredValue() {
        if (action == NetworkMutationAction.REMOVE) {
            return "Removed";
        }
        return sensitive && !desiredValue.isBlank() ? "••••••••" : desiredValue;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
