package redxax.oxy.remotely.data.flow;

import restudio.rebase.restudio.api.models.ServerModels.ClientServerView;

import java.util.List;
import java.util.Map;

public final class ReSyncLocalInstances {
    public static volatile Access access = Access.NONE;

    private ReSyncLocalInstances() {
    }

    public interface Access {
        Object manager();

        void addListener(Runnable listener);

        void removeListener(Runnable listener);

        Object find(String serverId, ClientServerView server);

        String instanceId(Object instance);

        String instanceName(Object instance);

        Object backendConfig(Object instance);

        Map<String, String> credentials(Object backendConfig);

        String backendType(Object backendConfig);

        String backendHost(Object instance);

        String instancePath(Object instance);

        Object backend(Object instance);

        boolean backendConnected(Object backend);

        void connectBackend(Object backend);

        Object fileSystem(Object backend);

        LocalProfile readLocalProfile(Object instance);

        LocalProfile readBackendProfile(Object instance, String instancePath, String backendHost);

        Access NONE = new Access() {
            @Override
            public Object manager() {
                return null;
            }

            @Override
            public void addListener(Runnable listener) {
            }

            @Override
            public void removeListener(Runnable listener) {
            }

            @Override
            public Object find(String serverId, ClientServerView server) {
                return null;
            }

            @Override
            public String instanceId(Object instance) {
                return "";
            }

            @Override
            public String instanceName(Object instance) {
                return "";
            }

            @Override
            public Object backendConfig(Object instance) {
                return null;
            }

            @Override
            public Map<String, String> credentials(Object backendConfig) {
                return Map.of();
            }

            @Override
            public String backendType(Object backendConfig) {
                return "";
            }

            @Override
            public String backendHost(Object instance) {
                return "";
            }

            @Override
            public String instancePath(Object instance) {
                return "";
            }

            @Override
            public Object backend(Object instance) {
                return null;
            }

            @Override
            public boolean backendConnected(Object backend) {
                return false;
            }

            @Override
            public void connectBackend(Object backend) {
            }

            @Override
            public Object fileSystem(Object backend) {
                return null;
            }

            @Override
            public LocalProfile readLocalProfile(Object instance) {
                return LocalProfile.unavailable("ReSyncConfigurationUnavailable", false);
            }

            @Override
            public LocalProfile readBackendProfile(Object instance, String instancePath, String backendHost) {
                return LocalProfile.unavailable("ReSyncConfigurationUnavailable", false);
            }
        };
    }

    public record LocalProfile(String content, String host, String serverId, String issue, boolean retryable) {
        public static LocalProfile unavailable(String issue, boolean retryable) {
            return new LocalProfile(null, "", "", issue, retryable);
        }

        public static LocalProfile missing(String issue, boolean retryable) {
            return new LocalProfile(null, "", "", issue, retryable);
        }

        public static LocalProfile found(String content, String host, String serverId) {
            return new LocalProfile(content, host, serverId, null, false);
        }
    }
}
