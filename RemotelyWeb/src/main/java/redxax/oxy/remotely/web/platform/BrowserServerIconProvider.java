package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.ui.server.ServerIconProvider;
import restudio.rebase.ui.widgets.IconCustomizerWidget;
import restudio.rescreen.platform.Async;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.ImportedIconLibrary;
import restudio.rescreen.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class BrowserServerIconProvider implements ServerIconProvider {
    private static final Set<String> SOFTWARE_ICONS = Set.of("vanilla", "fabric", "forge", "neoforge", "paper", "purpur", "quilt", "spigot", "bukkit", "leaf", "velocity", "waterfall");

    private final Supplier<BrowserRemotelyConfigStore> config;
    private final Map<String, Identifier> defaults = new LinkedHashMap<>();
    private final Map<String, CachedIcon> rendered = new LinkedHashMap<>();

    BrowserServerIconProvider(Supplier<BrowserRemotelyConfigStore> config) {
        this.config = config;
    }

    @Override
    public Identifier getIconId(Object server) {
        return getQuickIconId(server);
    }

    @Override
    public Identifier getQuickIconId(Object server) {
        String id = serverId(server);
        BrowserRemotelyConfigStore store = config.get();
        BrowserRemotelyConfigStore.IconSelection selection = store == null ? null : store.getServerIcon(id);
        if (selection != null) {
            Identifier image = selection.image();
            if (!selection.source().isBlank()) {
                Identifier restored = ScreenManager.getInstance().imageAssets().registerRemoteImage(selection.source());
                if (restored != null) {
                    image = restored;
                }
            }
            String signature = image + ":" + selection.tint() + ':' + selection.source().hashCode();
            CachedIcon cached = rendered.get(id);
            if (cached == null || !cached.signature().equals(signature)) {
                cached = new CachedIcon(signature, IconCustomizerWidget.render(image, selection.tint()));
                rendered.put(id, cached);
            }
            return cached.icon();
        }
        if (server instanceof ServerModels.ClientServerView view) {
            return defaultIcon(firstNonBlank(view.software, view.loader));
        }
        if (server instanceof LogicalServer logical) {
            return defaultIcon(firstNonBlank(logical.software(), logical.loader()));
        }
        return defaultIcon("");
    }

    @Override
    public Customization getCustomization(Object server) {
        String id = serverId(server);
        BrowserRemotelyConfigStore store = config.get();
        BrowserRemotelyConfigStore.IconSelection selection = store == null ? null : store.getServerIcon(id);
        if (selection == null) {
            return ServerIconProvider.super.getCustomization(server);
        }
        Identifier image = selection.image();
        if (!selection.source().isBlank()) {
            Identifier restored = ScreenManager.getInstance().imageAssets().registerRemoteImage(selection.source());
            if (restored != null) {
                image = restored;
            }
        }
        ImportedIconLibrary.Entry imported = ImportedIconLibrary.findBySource(selection.source());
        return new Customization(image, selection.tint(), getQuickIconId(server), selection.source(), imported != null ? imported.id() : "");
    }

    @Override
    public void loadIconIdAsync(Object server, Consumer<Identifier> onLoaded) {
        if (onLoaded != null) {
            onLoaded.accept(getQuickIconId(server));
        }
    }

    @Override
    public void loadRemoteIconAsync(Object server, Runnable onComplete) {
        if (onComplete != null) {
            onComplete.run();
        }
    }

    @Override
    public Optional<?> resolveIconPath(Object server, boolean loadRemote, Runnable onLoaded) {
        return Optional.empty();
    }

    @Override
    public Async<Void> customizeIcon(Object server, Object remoteHost, Identifier iconId, Runnable onComplete) {
        if (serverId(server).isBlank() || iconId == null) {
            return Async.failed(new IllegalArgumentException("Server Icon Is Unavailable"));
        }
        rendered.put(serverId(server), new CachedIcon(iconId.toString(), iconId));
        complete(onComplete);
        return Async.completed(null);
    }

    @Override
    public Async<Void> customizeIcon(Object server, Object remoteHost, Customization customization, Runnable onComplete) {
        String id = serverId(server);
        BrowserRemotelyConfigStore store = config.get();
        if (id.isBlank() || customization == null || customization.image() == null || store == null) {
            return Async.failed(new IllegalArgumentException("Server Icon Is Unavailable"));
        }
        store.setServerIcon(id, customization.image(), customization.tint(), customization.source());
        Identifier renderedIcon = customization.rendered();
        if (renderedIcon == null) {
            renderedIcon = IconCustomizerWidget.render(customization.image(), customization.tint());
        }
        rendered.put(id, new CachedIcon(customization.image() + ":" + customization.tint() + ':' + customization.source().hashCode(), renderedIcon));
        complete(onComplete);
        return Async.completed(null);
    }

    @Override
    public void clearCache(Object server) {
        rendered.remove(serverId(server));
    }

    @Override
    public void clearAllRemoteTracking() {
        rendered.clear();
    }

    @Override
    public void setDefaultIcons(Map<String, Identifier> icons) {
        defaults.clear();
        if (icons != null) {
            defaults.putAll(icons);
        }
    }

    private Identifier defaultIcon(String software) {
        String key = software == null ? "" : software.trim().toLowerCase(Locale.ROOT);
        Identifier configured = defaults.get(key);
        if (configured != null) {
            return configured;
        }
        if (SOFTWARE_ICONS.contains(key)) {
            return Identifier.icon(key + ".png");
        }
        return defaults.getOrDefault("unknown", Identifier.icon("unknown.png"));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String serverId(Object server) {
        if (server instanceof ServerModels.ClientServerView view) {
            return view.identifier == null || view.identifier.isBlank() ? view.uuid == null ? "" : view.uuid : view.identifier;
        }
        if (server instanceof LogicalServer logical) {
            return logical.software() + ":" + logical.loader();
        }
        return "";
    }

    private static void complete(Runnable onComplete) {
        if (onComplete != null) {
            onComplete.run();
        }
    }

    private record CachedIcon(String signature, Identifier icon) {
    }
}
