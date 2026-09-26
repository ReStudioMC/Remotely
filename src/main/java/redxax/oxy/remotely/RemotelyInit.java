package redxax.oxy.remotely;

import redxax.oxy.remotely.host.ApplicationHost;
import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.config.DesktopRemotelyConfigManager;
import redxax.oxy.remotely.data.flow.ReSyncDesktopIdentityProvider;
import redxax.oxy.remotely.packcontent.RemotelyPackContentIntegration;
import redxax.oxy.remotely.servers.ReProxyAutoStartService;
import restudio.rebase.Rebase;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.platform.jvm.JvmRebasePlatform;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.util.CredentialsManager;
import restudio.rescreen.Main;
import restudio.rescreen.config.Config;
import restudio.rescreen.logging.LogConsole;
import restudio.rescreen.logging.LogSettings;
import restudio.rescreen.logging.ReLog;
import restudio.rescreen.platform.desktop.DesktopAwt;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RemotelyInit {
    private static final String MAC_RELAUNCH_PROPERTY = "remotely.macos.firstThreadReady";

    public static void initCommon() {
        initCommon(RemotelyApplication.APP);
    }

    public static void initCommon(RemotelyApplication application) {
        initCommon(application, null);
    }

    public static void initCommon(RemotelyApplication application, LogConsole console) {
        if (isRebaseInitialized()) {
            if (Config.applicationDir instanceof Path applicationDirectory) {
                ReSyncDesktopIdentityProvider.initialize(applicationDirectory);
            }
            return;
        }

        Path remotelyDir = DesktopRemotelyPaths.initializeApplicationDir();
        Config.applicationDir = remotelyDir;
        LogSettings logSettings = LogSettings.standard(application == RemotelyApplication.APP ? "Remotely" : "Remotely Mod", DesktopRemotelyPaths.logsDir(remotelyDir));
        if (console == null) {
            ReLog.initialize(application == RemotelyApplication.MOD ? logSettings.withoutStandardStreamCapture() : logSettings);
        } else {
            ReLog.initialize(application == RemotelyApplication.MOD ? logSettings.withoutStandardStreamCapture() : logSettings, console);
        }
        RemotelyConfigManager configManager = new DesktopRemotelyConfigManager(remotelyDir);
        Config.setConfigManager(configManager);
        CredentialsManager.init(remotelyDir.toFile());
        InstanceManager.initialize(remotelyDir, configManager.getInstancesDir());
        InstanceManager.getInstance().addLegacyInstancesDir(DesktopRemotelyPaths.legacyAppDir());
        InstanceManager.getInstance().loadInstances();

        RemotelyManager remotelyManager = new RemotelyManager(remotelyDir, configManager);
        Rebase.initialize(remotelyManager, JvmRebasePlatform.create());
        ReStudio.getInstance().init(remotelyDir, application.reStudioClientId());
        ReSyncDesktopIdentityProvider.initialize(remotelyDir);
        new ReProxyAutoStartService(InstanceManager.getInstance()).start();
        RemotelyPackContentIntegration.install();
    }

    private static boolean isRebaseInitialized() {
        try {
            Rebase.get();
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    public static void initClient(ApplicationHost host) {
        startSession(DesktopRemotelyComposition.create(host).build());
    }

    public static RemotelySession startSession(RemotelyComposition composition) {
        return startSession(composition, null);
    }

    public static RemotelySession startSession(RemotelyComposition composition, LogConsole console) {
        Objects.requireNonNull(composition, "composition");
        if (composition.capabilities().has(RemotelyComposition.Capability.REBASE_BOOTSTRAP)) {
            initCommon(composition.application(), console);
        }
        if (composition.capabilities().has(RemotelyComposition.Capability.STORAGE_MIGRATION)) {
            DesktopRemotelyPaths.migrateLegacyAppDataAsync();
        }
        RemotelySession session = new RemotelySession(composition);
        session.initialize();
        return session;
    }

    public static void main(String[] args) {
        if (!ensureMacFirstThread(args)) {
            return;
        }
        DesktopAwt.initialize();
        Main.setEntryClass(RemotelyEntry.class);
        initCommon();
        Main.main(args);
    }

    private static boolean ensureMacFirstThread(String[] args) {
        if (!isMacOs() || Boolean.getBoolean(MAC_RELAUNCH_PROPERTY) || hasStartOnFirstThreadArg()) {
            return true;
        }

        List<String> command = new ArrayList<>();
        command.add(getJavaBinaryPath());
        command.add("-XstartOnFirstThread");
        command.add("-D" + MAC_RELAUNCH_PROPERTY + "=true");

        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        for (String jvmArg : runtimeMXBean.getInputArguments()) {
            if (!"-XstartOnFirstThread".equals(jvmArg) && !jvmArg.startsWith("-D" + MAC_RELAUNCH_PROPERTY + "=")) {
                command.add(jvmArg);
            }
        }

        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RemotelyInit.class.getName());
        command.addAll(List.of(args));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.inheritIO();

        try {
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            System.exit(exitCode);
            return false;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to relaunch with -XstartOnFirstThread", e);
        }
    }

    private static String getJavaBinaryPath() {
        String executableName = isWindows() ? "java.exe" : "java";
        String currentCommand = ProcessHandle.current().info().command().orElse(null);
        if (currentCommand != null && !currentCommand.isBlank()) {
            Path current = Path.of(currentCommand).toAbsolutePath().normalize();
            if (Files.isRegularFile(current) && current.getFileName().toString().equalsIgnoreCase(executableName)) {
                return current.toString();
            }
        }
        Path javaHomeExecutable = Path.of(System.getProperty("java.home"), "bin", executableName);
        if (Files.isRegularFile(javaHomeExecutable)) {
            return javaHomeExecutable.toAbsolutePath().normalize().toString();
        }
        throw new IllegalStateException("Current Java Launcher Is Unavailable");
    }

    private static boolean hasStartOnFirstThreadArg() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XstartOnFirstThread");
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
