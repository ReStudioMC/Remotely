package redxax.oxy.remotely.network.config;

public class SecretConfigurationAdapter implements NetworkConfigurationAdapter {
    @Override
    public String read(String content, String key) {
        return content == null ? "" : content.strip();
    }

    @Override
    public boolean contains(String content, String key) {
        return content != null && !content.isEmpty();
    }

    @Override
    public String apply(String content, String key, String value) {
        return value + System.lineSeparator();
    }

    @Override
    public String remove(String content, String key) {
        return "";
    }
}
