package redxax.oxy.remotely.network.config;

public interface NetworkConfigurationAdapter {
    interface Reader {
        String read(String key);

        boolean contains(String key);
    }

    default Reader prepare(String content) {
        return new Reader() {
            @Override
            public String read(String key) {
                return NetworkConfigurationAdapter.this.read(content, key);
            }

            @Override
            public boolean contains(String key) {
                return NetworkConfigurationAdapter.this.contains(content, key);
            }
        };
    }

    String read(String content, String key);

    boolean contains(String content, String key);

    String apply(String content, String key, String value);

    String remove(String content, String key);
}
