package redxax.oxy.remotely.network;

@FunctionalInterface
public interface NetworkRestoreValueResolver {
    String resolve(NetworkRestoreEntry entry);
}
