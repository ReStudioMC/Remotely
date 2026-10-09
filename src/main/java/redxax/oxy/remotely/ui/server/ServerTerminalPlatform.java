package redxax.oxy.remotely.ui.server;

public interface ServerTerminalPlatform {
    ServerTerminalPlatform NONE = new ServerTerminalPlatform() {
    };

    default void configure(ServerTerminal terminal) {
    }

    default void attach(ServerTerminal terminal) {
    }

    default void detach(ServerTerminal terminal) {
    }

    default void tick(ServerTerminal terminal, long now) {
    }

    default boolean connectionLost(ServerTerminal terminal, String reason) {
        return false;
    }

    default boolean canUseConsoleFallback(ServerTerminal terminal) {
        return false;
    }

    default boolean useConsoleFallback(ServerTerminal terminal) {
        return false;
    }

    default void startRequested(ServerTerminal terminal) {
    }

    default void stopRequested(ServerTerminal terminal) {
    }

    default boolean canAttachTerminal(ServerTerminal terminal) {
        return true;
    }

    default boolean beforeStartServerProcess(ServerTerminal terminal) {
        return true;
    }

    default boolean replacesStatusPolling() {
        return false;
    }

    default void stopProcessAsync(ServerTerminal terminal) {
        terminal.stopProcess();
    }

    default boolean isStaleLocalControllerStatus(Object status) {
        return false;
    }
}
