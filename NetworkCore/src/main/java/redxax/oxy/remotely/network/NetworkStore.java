package redxax.oxy.remotely.network;

import java.util.List;

public interface NetworkStore {
    List<NetworkDefinition> loadAll();

    void save(NetworkDefinition network);

    void delete(NetworkDefinition network);
}
