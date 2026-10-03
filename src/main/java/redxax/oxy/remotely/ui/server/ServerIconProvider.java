package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.util.BrowserSafeState;
import restudio.rescreen.platform.Async;
import restudio.rescreen.util.Identifier;
import restudio.rebase.restudio.api.models.ServerModels;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public interface ServerIconProvider {
    int ORIGINAL_TINT = -1;
    List<Integer> ICON_TINTS = List.of(0xFFFFFF, 0xFF6F61, 0x6FCF97, 0x6CC4F1, 0xFFC800, 0x9B51E0, 0xDF3E23, 0xd6f264, 0x7FFBFF);

    record Customization(Identifier image, int tint, Identifier rendered, String source, String libraryId) {
        public Customization {
            source = source == null ? "" : source;
            libraryId = libraryId == null ? "" : libraryId;
        }

        public Customization(Identifier image, int tint, Identifier rendered, String source) {
            this(image, tint, rendered, source, "");
        }

        public Customization(Identifier image, int tint, Identifier rendered) {
            this(image, tint, rendered, "", "");
        }
    }

    record LogicalServer(String software, String loader) {
        public LogicalServer {
            software = software == null ? "" : software;
            loader = loader == null ? "" : loader;
        }
    }

    Identifier getIconId(Object server);

    Identifier getQuickIconId(Object server);

    default Customization getCustomization(Object server) {
        Identifier icon = getIconId(server);
        return icon == null ? null : new Customization(icon, ORIGINAL_TINT, icon, "", "");
    }

    default Identifier getLogicalIconId(String software, String loader) {
        return getQuickIconId(new LogicalServer(software, loader));
    }

    void loadIconIdAsync(Object server, Consumer<Identifier> onLoaded);

    void loadRemoteIconAsync(Object server, Runnable onComplete);

    Optional<?> resolveIconPath(Object server, boolean loadRemote, Runnable onLoaded);

    Async<Void> customizeIcon(Object server, Object remoteHost, Identifier iconId, Runnable onComplete);

    default Async<Void> customizeIcon(Object server, Object remoteHost, Customization customization, Runnable onComplete) {
        if (customization == null) {
            return Async.failed(new IllegalArgumentException("Server Icon Is Unavailable"));
        }
        return customizeIcon(server, remoteHost, customization.rendered(), onComplete);
    }

    void clearCache(Object server);

    void clearAllRemoteTracking();

    default void close() {
    }

    default List<Identifier> loadIconAssetIds() {
        return IntStream.rangeClosed(1, 9).mapToObj(index -> Identifier.icon("ic_" + index + ".png")).toList();
    }

    default List<Integer> loadIconTints() {
        return ICON_TINTS;
    }

    void setDefaultIcons(Map<String, Identifier> icons);

    static ServerIconProvider logical() {
        return LogicalServerIconProvider.INSTANCE;
    }

    final class LogicalServerIconProvider implements ServerIconProvider {
        private static final Set<String> SOFTWARE_ICONS = Set.of("vanilla", "fabric", "forge", "neoforge", "paper", "purpur", "quilt", "spigot", "bukkit", "leaf", "velocity", "waterfall");
        private static final LogicalServerIconProvider INSTANCE = new LogicalServerIconProvider();
        private final Map<String, Identifier> defaultIconIds = BrowserSafeState.map();
        private final Map<String, Identifier> customIconIds = BrowserSafeState.map();

        private LogicalServerIconProvider() {
        }

        @Override
        public Identifier getIconId(Object server) {
            return getQuickIconId(server);
        }

        @Override
        public Identifier getQuickIconId(Object server) {
            Identifier custom = customIconIds.get(serverKey(server));
            if (custom != null) {
                return custom;
            }
            if (server instanceof LogicalServer logical) {
                return defaultIcon(logical.software(), logical.loader());
            }
            if (server instanceof ServerModels.ClientServerView view) {
                return defaultIcon(view.loader, view.loader);
            }
            return defaultIcon("", "");
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
            String key = serverKey(server);
            if (!key.isBlank() && iconId != null) {
                customIconIds.put(key, iconId);
            }
            if (onComplete != null) {
                onComplete.run();
            }
            return Async.completed(null);
        }

        @Override
        public void clearCache(Object server) {
        }

        @Override
        public void clearAllRemoteTracking() {
        }

        @Override
        public void setDefaultIcons(Map<String, Identifier> icons) {
            defaultIconIds.clear();
            if (icons != null) {
                defaultIconIds.putAll(icons);
            }
        }

        private Identifier defaultIcon(String software, String loader) {
            String normalizedSoftware = software == null ? "" : software.trim().toLowerCase(Locale.ROOT);
            String normalizedLoader = loader == null ? "" : loader.trim().toLowerCase(Locale.ROOT);
            String key = normalizedSoftware.isBlank() ? normalizedLoader : normalizedSoftware;
            Identifier configured = defaultIconIds.get(key);
            if (configured != null) {
                return configured;
            }
            if (SOFTWARE_ICONS.contains(key)) {
                return Identifier.icon(key + ".png");
            }
            return defaultIconIds.getOrDefault("unknown", Identifier.icon("unknown.png"));
        }

        private String serverKey(Object server) {
            if (server instanceof ServerModels.ClientServerView view) {
                if (view.identifier != null && !view.identifier.isBlank()) return view.identifier;
                return view.uuid == null ? "" : view.uuid;
            }
            if (server instanceof LogicalServer logical) {
                return logical.software() + ":" + logical.loader();
            }
            return "";
        }
    }
}
