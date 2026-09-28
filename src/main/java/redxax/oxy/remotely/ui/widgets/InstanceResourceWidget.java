package redxax.oxy.remotely.ui.widgets;

import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rebase.ui.screens.resources.ResourceContainerProvider;
import restudio.rebase.ui.screens.resources.ResourceUpdatePopup;
import restudio.rebase.ui.widgets.resources.ResourceWidget;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;
import restudio.rescreen.util.Identifier;
import redxax.oxy.remotely.ResourceTogglePendingException;

import java.util.Locale;

import static restudio.rescreen.util.SoundUtils.playSound;

public class InstanceResourceWidget extends ResourceWidget<ResourceContainerItem> {
    private final ResourceContainerProvider provider;
    private final Runnable refreshCallback;
    private final ReScreen parentScreen;
    private volatile boolean iconLoading = false;

    public InstanceResourceWidget(ReScreen parentScreen, ResourceContainerProvider provider, ResourceContainerItem resource, Runnable refreshCallback) {
        this(parentScreen, provider, resource, refreshCallback, new Adapter(provider));
    }

    private InstanceResourceWidget(ReScreen parentScreen, ResourceContainerProvider provider, ResourceContainerItem resource,
                                   Runnable refreshCallback, Adapter adapter) {
        super(resource, adapter);
        adapter.owner = this;
        this.parentScreen = parentScreen;
        this.provider = provider;
        this.refreshCallback = refreshCallback;
        ensureImage();
    }

    private void ensureImage() {
        if (iconLoading || resource.getIconId() != null) return;
        if (resource.getProjectId() == null || resource.getProviderName() == null) return;
        iconLoading = true;
        provider.resolveIcon(resource, icon -> ScreenManager.getInstance().execute(() -> {
            if (icon != null) resource.setIconId(icon);
            refresh();
            iconLoading = false;
        }));
    }

    private void showUpdateDialog() {
        if (resource.getProjectId() == null || resource.getProviderName() == null) {
            new Notification("Cannot Check Update", "Resource Has No Project ID", Notification.Type.WARN);
            return;
        }
        provider.checkUpdate(resource).whenComplete((update, failure) -> ScreenManager.getInstance().execute(() -> {
            if (failure != null) {
                new Notification("Check Failed", failure.getMessage(), Notification.Type.ERROR);
                return;
            }
            if (update == null) {
                resource.availableUpdate = null;
                refresh();
                return;
            }
            resource.availableUpdate = update;
            refresh();
            ResourceUpdatePopup.showOne(parentScreen, provider, resource, update, null, refreshCallback);
        }));
    }

    private void open(int button) {
        if (button == 0 && resource.getProjectId() != null && resource.getProviderName() != null) {
            playSound(Sound.CREATE);
            provider.openResource(parentScreen, resource, refreshCallback);
        }
    }

    @Override
    protected void refreshFromResource() {
        super.refreshFromResource();
        if (getRenderingMode() == RenderingMode.UPDATE) {
            return;
        }
        var toggleCapability = provider.capability(ResourceContainerProvider.CAPABILITY_TOGGLE);
        toggleButton.setActive(toggleCapability.available());
        toggleButton.setHint(toggleCapability.available() ? "Toggle Resource" : toggleCapability.detail());
        var updateCapability = provider.capability(ResourceContainerProvider.CAPABILITY_UPDATE_SELECTION);
        updateButton.setActive(updateCapability.available());
        updateButton.setHint(updateCapability.available() ? "Update Available" : updateCapability.detail());
    }

    private static class Adapter implements ResourceAdapter<ResourceContainerItem> {
        private final ResourceContainerProvider provider;
        private InstanceResourceWidget owner;

        private Adapter(ResourceContainerProvider provider) {
            this.provider = provider;
        }

        @Override
        public String name(ResourceContainerItem resource) {
            return resource.getName();
        }

        @Override
        public String description(ResourceContainerItem resource) {
            return resource.getDescription();
        }

        @Override
        public String version(ResourceContainerItem resource) {
            return resource.getVersion();
        }

        @Override
        public String author(ResourceContainerItem resource) {
            return resource.getAuthor();
        }

        @Override
        public Identifier iconId(ResourceContainerItem resource) {
            if (owner != null) owner.ensureImage();
            return resource.getIconId();
        }

        @Override
        public boolean enabled(ResourceContainerItem resource) {
            return resource.isEnabled();
        }

        @Override
        public boolean updateAvailable(ResourceContainerItem resource) {
            return resource.availableUpdate != null;
        }

        @Override
        public void toggle(ResourceContainerItem resource, ToggleWidget toggle, RenderingMode renderingMode) {
            if (renderingMode == RenderingMode.UPDATE || resource.isModpack()) {
                return;
            }
            boolean originalState = resource.isEnabled();
            if (!provider.available(ResourceContainerProvider.CAPABILITY_TOGGLE)) {
                toggle.setValue(originalState);
                return;
            }
            String originalPath = resource.path();
            boolean enabled = !originalState;
            provider.toggle(resource, enabled).thenRun(() -> ScreenManager.getInstance().execute(() -> {
                if (resource.isEnabled() == enabled) return;
                boolean disabled = originalPath.toLowerCase(Locale.ROOT).endsWith(".disabled");
                String path = enabled && disabled ? originalPath.substring(0, originalPath.length() - ".disabled".length())
                        : !enabled && !disabled ? originalPath + ".disabled" : originalPath;
                resource.path(path);
                resource.setEnabled(enabled);
            })).exceptionally(e -> {
                ScreenManager.getInstance().execute(() -> {
                    Throwable failure = e.getCause() != null ? e.getCause() : e;
                    if (failure instanceof ResourceTogglePendingException) {
                        new Notification("Resource Toggle Still Running", "Refresh To Check Its State", Notification.Type.INFO);
                    } else {
                        new Notification("Failed To Toggle Resource", failure.getMessage(), Notification.Type.ERROR);
                    }
                    toggle.setValue(originalState);
                });
                return null;
            });
        }

        @Override
        public void update(ResourceContainerItem resource) {
            if (owner != null) {
                owner.showUpdateDialog();
            }
        }

        @Override
        public void open(ResourceContainerItem resource, int button) {
            if (owner != null) {
                owner.open(button);
            }
        }
    }
}
