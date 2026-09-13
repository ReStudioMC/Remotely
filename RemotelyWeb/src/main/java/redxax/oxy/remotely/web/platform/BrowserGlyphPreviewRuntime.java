package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import redxax.oxy.remotely.packcontent.GlyphDefinition;
import redxax.oxy.remotely.packcontent.GlyphPreviewAccess;
import redxax.oxy.remotely.packcontent.NexoGlyphCatalog;
import redxax.oxy.remotely.packcontent.NexoGlyphPreviewAccess;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.screens.editor.FileEditorScreen;
import restudio.rebase.ui.screens.explorer.FileExplorerScreen;
import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.util.HashSet;
import java.util.Set;

final class BrowserGlyphPreviewRuntime implements NexoGlyphPreviewAccess.Runtime {
    private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;
    private final BrowserRemotelyServerApi api;
    private final RemoteFileSystemProvider files;
    private final ServerModels.ClientServerView server;
    private final RemotePath workspaceRoot;
    private final String serverId;
    private final Set<Identifier> images = new HashSet<>();
    private final Set<String> reportedFailures = new HashSet<>();
    private boolean closed;

    BrowserGlyphPreviewRuntime(BrowserRemotelyServerApi api, RemoteFileSystemProvider files,
                               ServerModels.ClientServerView server, RemotePath workspaceRoot) {
        this.api = api;
        this.files = files;
        this.server = server;
        this.workspaceRoot = workspaceRoot == null ? RemotePath.root() : workspaceRoot;
        this.serverId = server == null || server.identifier == null || server.identifier.isBlank()
                ? server == null || server.uuid == null ? "" : server.uuid
                : server.identifier;
    }

    @Override
    public Async<GlyphPreviewAccess.Image> loadImage(GlyphDefinition glyph, Integer requestedIndex) {
        String path = glyph == null || glyph.assetRef() == null ? null : glyph.assetRef().resolvedPath();
        if (closed || path == null || path.isBlank() || api == null || serverId.isBlank()) return Async.completed(null);
        Async<GlyphPreviewAccess.Image> loading = api.downloadFileData(serverId, path)
                .thenApply(bytes -> closed ? null : image(glyph, requestedIndex, bytes));
        loading.whenComplete((image, failure) -> report(path, image, failure));
        return loading;
    }

    @Override
    public boolean openSource(RemotePath path) {
        if (path == null || files == null) return false;
        ScreenManager manager = ScreenManager.getInstance();
        Screen parent = manager.getCurrentScreen();
        FileEditorScreen.canOpen(files, path).thenAccept(can -> manager.execute(() -> {
            if (can) {
                manager.setScreen(new FileEditorScreen(parent, server, files, workspaceRoot, path, workspaceRoot));
            } else {
                new Notification("Open Failed", path.fileName(), Notification.Type.ERROR);
            }
        }));
        return true;
    }

    @Override
    public boolean openAsset(RemotePath path) {
        if (path == null || files == null) return false;
        RemotePath parent = path.getParent() == null ? path : path.getParent();
        ScreenManager manager = ScreenManager.getInstance();
        manager.execute(() -> manager.setScreen(new FileExplorerScreen(
                manager.getCurrentScreen(), server, parent, workspaceRoot, false, files)));
        return true;
    }

    @Override
    public void close() {
        closed = true;
        for (Identifier image : images) ScreenManager.getInstance().imageAssets().releaseImage(image);
        images.clear();
        reportedFailures.clear();
    }

    private GlyphPreviewAccess.Image image(GlyphDefinition glyph, Integer requestedIndex, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalStateException("Glyph Preview Image Is Too Large");
        String mime = glyph.isGif() ? "image/gif" : "image/png";
        String source = "data:" + mime + ";base64," + BrowserHttpTransport.encodeBase64(bytes);
        Identifier id = ScreenManager.getInstance().imageAssets().registerRemoteImage(source);
        if (id == null) return null;
        images.add(id);
        NexoGlyphCatalog.Grid grid = glyph.isGif()
                ? new NexoGlyphCatalog.Grid(1, 1)
                : NexoGlyphCatalog.grid(glyph, requestedIndex);
        int total = Math.max(1, grid.rows() * grid.columns());
        int selected = Math.clamp(requestedIndex == null ? 0 : requestedIndex, 0, total - 1);
        int height = Math.max(1, glyph.height());
        return new GlyphPreviewAccess.Image(id, height, height, grid.rows(), grid.columns(), selected);
    }

    private void report(String path, GlyphPreviewAccess.Image image, Throwable failure) {
        if (closed) return;
        if (failure == null && image != null) {
            reportedFailures.remove(path);
            return;
        }
        if (!reportedFailures.add(path)) return;
        String reason = failure == null ? "Image Data Was Empty" : failureMessage(failure);
        consoleError("[Remotely Web] Nexo Glyph Image Load Failed: " + path + ": " + reason);
    }

    private static String failureMessage(Throwable failure) {
        Throwable current = failure;
        while (current != null && current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current == null) return "Unknown Failure";
        String message = current.getMessage();
        return message == null || message.isBlank() ? "Glyph Image Load Failed" : message;
    }

    @JSBody(params = {"message"}, script = "if(window.console&&console.error)console.error(message);")
    private static native void consoleError(String message);
}
