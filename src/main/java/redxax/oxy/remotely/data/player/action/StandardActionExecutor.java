package redxax.oxy.remotely.data.player.action;

import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import restudio.rebase.ui.widgets.TerminalWidget;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;

import restudio.rescreen.platform.Async;

public class StandardActionExecutor implements IActionExecutor {
    private final TerminalWidget terminal;

    public StandardActionExecutor(TerminalWidget terminal) {
        this.terminal = terminal;
    }

    @Override
    public boolean canExecute(String actionType) {
        return switch (actionType) {
            case "kick", "ban", "unban", "unban-ip", "op", "deop", "whitelist", "unwhitelist", "command" -> true;
            default -> false;
        };
    }

    @Override
    public Async<Void> execute(UnifiedPlayer player, String actionType, Object... args) {
        if (terminal == null) {
            ReLog.logger(LogTypes.MINECRAFT).source(LogSource.application("Remotely")).component(StandardActionExecutor.class).operation("Run Player Action").error("Server terminal is unavailable");
            return Async.failed(new IllegalStateException("Terminal unavailable"));
        }
        String name = player.getName();
        if (name == null && !actionType.equals("command")) {
            ReLog.logger(LogTypes.MINECRAFT).source(LogSource.application("Remotely")).component(StandardActionExecutor.class).operation("Run Player Action").error("Player name is unavailable");
            return Async.failed(new IllegalStateException("Player name unavailable"));
        }

        switch (actionType) {
            case "kick" -> {
                String reason = args.length > 0 ? (String) args[0] : "Kicked by operator";
                terminal.executeCommand("kick " + name + " " + reason);
            }
            case "ban" -> {
                String reason = args.length > 0 ? (String) args[0] : "Banned by operator";
                boolean ipBan = args.length > 1 && (boolean) args[1];
                if (ipBan) {
                    terminal.executeCommand("ban-ip " + name + " " + reason);
                } else {
                    terminal.executeCommand("ban " + name + " " + reason);
                }
            }
            case "unban" -> {
                terminal.executeCommand("pardon " + name);
            }
            case "unban-ip" -> terminal.executeCommand("pardon-ip " + args[0]);
            case "op" -> {
                terminal.executeCommand("op " + name);
            }
            case "deop" -> {
                terminal.executeCommand("deop " + name);
            }
            case "whitelist" -> terminal.executeCommand("whitelist add " + name);
            case "unwhitelist" -> terminal.executeCommand("whitelist remove " + name);
            case "command" -> {
                String command = args.length > 0 ? (String) args[0] : null;
                if (command != null) {
                    terminal.executeCommand(command);
                }
            }
        }
        return Async.completed(null);
    }

    @Override
    public int getPriority() {
        return 10;
    }
}
