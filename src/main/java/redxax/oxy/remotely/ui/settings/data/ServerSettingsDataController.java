package redxax.oxy.remotely.ui.settings.data;

import restudio.rescreen.platform.Async;
import restudio.rescreen.ui.settings.Setting;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ServerSettingsDataController extends AutoCloseable {
    Async<Void> load();

    Async<Void> ready();

    List<String> tabNames();

    default List<String> plannedTabNames() {
        return tabNames();
    }

    default Runnable onTabsPublished(Consumer<List<String>> listener) {
        listener.accept(tabNames());
        return () -> {};
    }

    default List<String> getTabNames() {
        return tabNames();
    }

    List<Setting> settings(String tab);

    default List<Setting> getSettings(String tab) {
        return settings(tab);
    }

    default List<Setting> settingsForTab(String tab) {
        return settings(tab);
    }

    Map<String, List<Setting>> settingsByTab();

    List<String> documentPaths();

    List<String> availableDocumentPaths();

    List<String> unavailableDocumentPaths();

    default List<String> getAvailableDocumentPaths() {
        return availableDocumentPaths();
    }

    boolean isDocumentAvailable(String relativePath);

    Map<String, String> changedFileContents();

    default Map<String, String> getChangedFileContents() {
        return changedFileContents();
    }

    Async<Void> save(Object target);

    @Override
    default void close() {
    }

    static ServerSettingsDataController unavailable() {
        return new UnavailableServerSettingsDataController();
    }
}
