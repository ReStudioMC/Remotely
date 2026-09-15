package redxax.oxy.remotely.network.config;

public interface NetworkConfigurationAdapter {
    String read(String content, String key);

    boolean contains(String content, String key);

    String apply(String content, String key, String value);

    String remove(String content, String key);
}
