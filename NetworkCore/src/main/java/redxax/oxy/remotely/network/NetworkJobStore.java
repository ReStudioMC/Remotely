package redxax.oxy.remotely.network;

import java.util.List;

public interface NetworkJobStore {
    List<NetworkJob> loadAll();

    void save(NetworkJob job);
}
