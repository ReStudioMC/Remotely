package redxax.oxy.remotely.network.config;

import redxax.oxy.remotely.network.ConfigurationFormat;

import java.util.EnumMap;
import java.util.Map;

public class NetworkConfigurationAdapters {
    private final Map<ConfigurationFormat, NetworkConfigurationAdapter> adapters = new EnumMap<>(ConfigurationFormat.class);

    public NetworkConfigurationAdapters(StructuredDocumentParser structuredParser) {
        adapters.put(ConfigurationFormat.PROPERTIES, new PropertiesConfigurationAdapter());
        adapters.put(ConfigurationFormat.TOML, new TomlConfigurationAdapter(structuredParser));
        adapters.put(ConfigurationFormat.YAML, new YamlConfigurationAdapter(structuredParser));
        adapters.put(ConfigurationFormat.SECRET, new SecretConfigurationAdapter());
    }

    public NetworkConfigurationAdapter get(ConfigurationFormat format) {
        NetworkConfigurationAdapter adapter = adapters.get(format);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported configuration format " + format);
        }
        return adapter;
    }
}
