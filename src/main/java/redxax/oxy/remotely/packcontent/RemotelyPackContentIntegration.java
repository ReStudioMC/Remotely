package redxax.oxy.remotely.packcontent;

import redxax.oxy.remotely.config.RemotelyConfigManager;
import redxax.oxy.remotely.servers.reproxy.PluginConfigDecoration;
import redxax.oxy.remotely.servers.reproxy.PluginForwarding;
import redxax.oxy.remotely.servers.reproxy.ReProxyIntegrations;
import restudio.rebase.api.unified.InstanceApi;
import restudio.rebase.api.unified.adapter.UnifiedFileSystemProvider;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceManager;
import restudio.rebase.platform.jvm.JvmRemoteFileSystemProvider;
import restudio.rebase.ui.screens.editor.EditorDecorationBinding;
import restudio.rebase.ui.screens.editor.FileEditorScreen;
import restudio.rebase.hosting.RemoteHost;
import restudio.rebase.ui.widgets.editor.TextLineDecoration;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.Async;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class RemotelyPackContentIntegration {
    private static Function<EditorDecorationBinding, PluginForwarding> rendererResolver;

    private RemotelyPackContentIntegration() {
    }

    public static void install() {
        install(null);
    }

    public static void install(Function<EditorDecorationBinding, PluginForwarding> forwarding) {
        if (forwarding != null) rendererResolver = forwarding;
        FileEditorScreen.setEditorDecorationBinder(binding -> bindEditor(binding, rendererResolver));
    }

    public static void refresh(Instance instance, FileSystemProvider provider, Path workspaceRoot) {
        if (provider == null || workspaceRoot == null) {
            return;
        }
        PackContentRegistry.get().refresh(instance, provider, workspaceRoot);
    }

    public static GlyphPreviewMode mode() {
        if (Config.configManager instanceof RemotelyConfigManager remotelyConfigManager) {
            return remotelyConfigManager.getGlyphPreviewMode();
        }
        return GlyphPreviewMode.INLINE_HOVER;
    }

    public static void refreshInstance(Instance instance) {
        if (instance == null || instance.getPath() == null || instance.getBackend() == null) {
            return;
        }
        refresh(instance, new UnifiedFileSystemProvider(InstanceApi.of(instance).files()), Path.of(instance.getPath()));
    }

    public static Async<Integer> refreshAllInstances() {
        List<Instance> instances = knownInstances();
        List<Async<Void>> refreshes = new ArrayList<>();
        for (Instance instance : instances) {
            if (instance == null || instance.getPath() == null || instance.getBackend() == null) {
                continue;
            }
            Path root = Path.of(instance.getPath());
            FileSystemProvider provider = new UnifiedFileSystemProvider(InstanceApi.of(instance).files());
            refreshes.add(PackContentRegistry.get().refresh(instance, provider, root));
        }
        if (refreshes.isEmpty()) {
            return Async.completed(0);
        }
        return Async.allOf(refreshes.toArray(Async[]::new)).thenApply(v -> refreshes.size());
    }

    private static List<Instance> knownInstances() {
        List<Instance> instances = new ArrayList<>();
        InstanceManager manager = InstanceManager.getInstance();
        instances.addAll(manager.getLocalInstances());
        for (RemoteHost host : manager.getRemoteHosts()) {
            instances.addAll(manager.getRemoteInstances(host));
        }
        return instances;
    }

    private static void bindEditor(EditorDecorationBinding binding, Function<EditorDecorationBinding, PluginForwarding> forwarding) {
        if (binding == null || binding.editor() == null || binding.provider() == null || binding.workspaceRoot() == null) {
            return;
        }
        if (!(binding.provider() instanceof JvmRemoteFileSystemProvider)) {
            return;
        }
        Instance instance = binding.instance() instanceof Instance value ? value : null;
        RemoteFileSystemProvider remoteProvider = binding.provider();
        FileSystemProvider provider = JvmRemoteFileSystemProvider.legacy(remoteProvider);
        Path workspaceRoot = Path.of(binding.workspaceRoot().asString()).normalize();
        Path filePath = resolveEditorPath(workspaceRoot, binding.filePath() == null ? null : binding.filePath().asString());
        Path contentRoot = contentRootFor(workspaceRoot, filePath);
        GlyphPreviewRenderer renderer = new GlyphPreviewRenderer(new DesktopGlyphPreviewAccess(instance, provider, contentRoot),
                filePath == null ? null : filePath.toString(), binding.language());
        TextLineDecoration glyphs = new TextLineDecoration() {
            @Override
            public void draw(TextLineDecorationContext context) {
                renderer.drawEditor(context, mode());
            }

            @Override
            public void afterDraw(TextLineDecorationOverlayContext context) {
                renderer.drawEditorOverlay(context);
            }

            @Override
            public boolean mouseClicked(TextLineDecorationClickContext context) {
                return renderer.openHoveredAsset(context.mouseX(), context.mouseY(), context.button());
            }
        };
        EditorDecorationBinding pluginBinding = pluginBinding(binding);
        Instance pluginInstance = pluginBinding.instance() instanceof Instance value ? value : null;
        PluginForwarding owner = forwarding == null ? null : forwarding.apply(pluginBinding);
        boolean local = pluginInstance != null && (pluginInstance.getBackendConfig() == null || "LOCAL".equalsIgnoreCase(pluginInstance.getBackendConfig().type));
        binding.editor().setLineDecoration(PluginConfigDecoration.compose(glyphs, PluginConfigDecoration.bind(pluginBinding, owner, local)));
    }

    private static EditorDecorationBinding pluginBinding(EditorDecorationBinding binding) {
        Instance instance = binding.instance() instanceof Instance value ? value : null;
        RemotePath file = binding.filePath();
        String type = binding.provider().getMetadata("type");
        if (file != null && (type == null || type.isBlank() || "LOCAL".equalsIgnoreCase(type))) {
            Path workspace = binding.workspaceRoot() == null ? null : Path.of(binding.workspaceRoot().asString()).normalize();
            Path target = resolveEditorPath(workspace, file.asString());
            if (target != null && target.isAbsolute()) {
                Instance match = null;
                List<Instance> candidates = new ArrayList<>(InstanceManager.getInstance().getLocalInstances());
                if (instance != null && !candidates.contains(instance)) candidates.add(instance);
                for (Instance candidate : candidates) {
                    if (candidate == null || !candidate.isServer() || candidate.getPath() == null || candidate.getPath().isBlank()) continue;
                    var config = candidate.getBackendConfig();
                    if (config != null && !"LOCAL".equalsIgnoreCase(config.type)) continue;
                    Path root = Path.of(candidate.getPath()).normalize();
                    if (!root.isAbsolute()) continue;
                    for (ReProxyIntegrations.Integration integration : ReProxyIntegrations.catalog()) {
                        for (String path : integration.paths()) {
                            Path expected = root.resolve(path).normalize();
                            if (!target.equals(expected)) continue;
                            if (match != null && !match.getInstanceId().equals(candidate.getInstanceId())) {
                                return new EditorDecorationBinding(null, binding.provider(), binding.workspaceRoot(), file,
                                        binding.language(), binding.editor(), binding.diskChanged(), binding.cleanup());
                            }
                            match = candidate;
                            file = RemotePath.of(expected.toString());
                        }
                    }
                }
                instance = match;
            }
        }
        if (instance == null || instance.getPath() == null || instance.getPath().isBlank()) {
            return new EditorDecorationBinding(null, binding.provider(), binding.workspaceRoot(), file,
                    binding.language(), binding.editor(), binding.diskChanged(), binding.cleanup());
        }
        return new EditorDecorationBinding(instance, binding.provider(), RemotePath.of(instance.getPath()), file,
                binding.language(), binding.editor(), binding.diskChanged(), binding.cleanup());
    }

    static Path resolveEditorPath(Path workspaceRoot, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Path path = Path.of(filePath);
        return path.isAbsolute() || path.getRoot() != null || workspaceRoot == null ? path.normalize() : workspaceRoot.resolve(path).normalize();
    }

    static Path contentRootFor(Path workspaceRoot, Path filePath) {
        if (filePath == null) {
            return workspaceRoot;
        }
        Path current = filePath.normalize();
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && name.toString().equalsIgnoreCase("Nexo")) {
                return current;
            }
            current = current.getParent();
        }
        current = filePath.normalize();
        while (current != null) {
            Path name = current.getFileName();
            if (name != null && name.toString().equalsIgnoreCase("ItemsAdder")) {
                return current;
            }
            current = current.getParent();
        }
        return workspaceRoot;
    }
}
