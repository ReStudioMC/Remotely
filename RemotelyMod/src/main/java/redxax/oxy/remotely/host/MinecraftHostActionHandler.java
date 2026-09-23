package redxax.oxy.remotely.host;

import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.HostActionHandler.SelectedFile;
import restudio.rescreen.platform.KeyValueStore;
import restudio.rescreen.platform.desktop.DesktopFileActionHandler;
import restudio.rescreen.platform.desktop.DesktopHostActionHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class MinecraftHostActionHandler implements DesktopFileActionHandler {
    private final DesktopHostActionHandler desktop = new DesktopHostActionHandler();

    @Override
    public boolean openBrowser(String url) {
        return desktop.openBrowser(url);
    }

    @Override
    public Async<Boolean> openBrowserAsync(String url) {
        return desktop.openBrowserAsync(url);
    }

    @Override
    public boolean trashFiles(List<Path> paths, boolean permanentDelete) {
        return desktop.trashFiles(paths, permanentDelete);
    }

    @Override
    public void openAssociated(Path path) throws IOException {
        desktop.openAssociated(path);
    }

    @Override
    public void copyFilesToClipboard(List<Path> paths) {
        desktop.copyFilesToClipboard(paths);
    }

    @Override
    public void pickImageFileAsync(String title, Consumer<Path> onSelected) {
        desktop.pickImageFileAsync(title, onSelected);
    }

    @Override
    public boolean filePickerAvailable() {
        return desktop.filePickerAvailable();
    }

    @Override
    public void pickFilesAsync(boolean multiple, Consumer<List<Path>> onSelected) {
        desktop.pickFilesAsync(multiple, onSelected);
    }

    @Override
    public void pickFilesAsData(boolean multiple, Consumer<List<SelectedFile>> callback) {
        desktop.pickFilesAsData(multiple, callback);
    }

    @Override
    public void pickImagesAsData(boolean multiple, Consumer<List<SelectedFile>> callback) {
        desktop.pickImagesAsData(multiple, callback);
    }

    @Override
    public KeyValueStore persistentStore(String namespace) {
        return desktop.persistentStore(namespace);
    }
}
