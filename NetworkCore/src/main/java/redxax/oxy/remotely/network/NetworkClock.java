package redxax.oxy.remotely.network;

@FunctionalInterface
public interface NetworkClock {
    NetworkClock SYSTEM = System::currentTimeMillis;

    long millis();
}
