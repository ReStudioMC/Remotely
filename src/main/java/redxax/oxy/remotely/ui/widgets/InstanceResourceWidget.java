package redxax.oxy.remotely.ui.widgets;

import redxax.oxy.remotely.servers.ReProxyManager;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rebase.ui.screens.resources.ResourceContainerProvider;
import restudio.rebase.ui.screens.resources.ResourceForwarding;
import restudio.rebase.ui.screens.resources.ResourceEnablePopup;
import restudio.rebase.ui.screens.resources.ResourceDependencies;
import restudio.rebase.ui.screens.resources.ResourceUpdatePopup;
import restudio.rebase.ui.widgets.resources.ResourceWidget;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Sound;
import restudio.rescreen.util.Identifier;
import redxax.oxy.remotely.ResourceTogglePendingException;

import java.util.Locale;
import java.util.Objects;

import static restudio.rescreen.util.SoundUtils.playSound;

public class InstanceResourceWidget extends ResourceWidget<ResourceContainerItem> {
    private static final Identifier CONNECT_ICON = Identifier.icon("reverse.png");
    private static final Identifier DISCONNECT_ICON = Identifier.icon("closeReverse.png");
    private static final Identifier LOADING_ICON = Identifier.animatedIcon("loadingBlue");
    private final ResourceContainerProvider provider;
    private final IconButton forwardButton;
    private ResourceForwarding.State forwardState;
    private String forwardName;
    private boolean forwardBusy;
    private boolean forwardStateBusy;
    private String forwardWaitingHint = "";
    private boolean cleanedUp;
    private long forwardGeneration;
    private Async<Void> toggleRequest;
    private ResourceDependencies.Stamp toggleStamp;
    private final Runnable refreshCallback;
    private final ReScreen parentScreen;
    private volatile boolean iconLoading = false;
    private String iconSource;
    private long iconGeneration;

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
        this.forwardButton = new IconButton.Builder()
                .identifier(CONNECT_ICON)
                .onClick(this::toggleForwarding)
                .size(18, 18)
                .visible(false)
                .entranceAnimation(false)
                .build();
        mountedWidgets.add(mountedWidgets.indexOf(toggleButton), forwardButton);
        refreshForwarding();
        ensureImage();
    }

    private void refreshForwarding() {
        if (forwardButton == null) return;
        if (getRenderingMode() == RenderingMode.UPDATE) {
            forwardButton.setVisible(false);
            forwardState = null;
            return;
        }
        ResourceForwarding.State state = provider.forwarding().state(resource);
        String name = resource.getName();
        if (Objects.equals(state, forwardState) && Objects.equals(name, forwardName) && forwardBusy == forwardStateBusy) return;
        forwardState = state;
        forwardName = name;
        forwardStateBusy = forwardBusy;
        boolean busy = state.busy() || forwardBusy;
        forwardButton.setVisible(state.visible() || busy);
        forwardButton.setActive(state.visible() || busy);
        forwardButton.setIcon(busy ? LOADING_ICON : state.connected() ? DISCONNECT_ICON : CONNECT_ICON);
        String hint = forwardBusy && !state.busy() ? "Updating Connection"
                : state.hint().isBlank() ? (state.connected() ? "Disconnect " : "Connect ") + name : state.hint();
        if (!state.address().isBlank() && !hint.contains(state.address())) hint += " | " + state.address();
        forwardButton.setHint(hint);
        notifyForwardingWait(state);
    }

    private void toggleForwarding() {
        if (cleanedUp || forwardBusy) return;
        ResourceForwarding forwarding = provider.forwarding();
        ResourceForwarding.State state = forwarding.state(resource);
        if (!state.visible()) return;
        long generation = ++forwardGeneration;
        forwardBusy = true;
        forwardWaitingHint = "";
        refreshForwarding();
        try {
            forwarding.toggle(resource).whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> finishForwarding(generation, failure)));
            refreshForwarding();
        } catch (RuntimeException failure) {
            finishForwarding(generation, failure);
        }
    }

    private void finishForwarding(long generation, Throwable failure) {
        if (cleanedUp || generation != forwardGeneration) return;
        if (failure != null && !cancelled(failure)) new Notification("Plugin Connection Failed", forwardingFailure(failure), Notification.Type.ERROR);
        if (failure == null) new Notification("Plugin Settings Applied", provider.forwarding().state(resource).hint(), Notification.Type.INFO);
        forwardBusy = false;
        refresh();
        if (failure == null && refreshCallback != null) refreshCallback.run();
    }

    private void notifyForwardingWait(ResourceForwarding.State state) {
        String hint = state.hint();
        if (!forwardBusy || !state.busy() || !(hint.startsWith("Stop ") || hint.startsWith("Start ") || hint.startsWith("Choose ") || hint.startsWith("Enable "))
                || hint.equals(forwardWaitingHint)) return;
        forwardWaitingHint = hint;
        new Notification("Plugin Connection Queued", hint, Notification.Type.INFO);
    }

    private static boolean cancelled(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) if (cause instanceof Async.Cancellation) return true;
        return false;
    }

    private static String forwardingFailure(Throwable failure) {
        return ReProxyManager.failureMessage(failure);
    }

    @Override
    public boolean mouseClicked(ReMouseEvent event) {
        if (isVisible() && (isActive() || ClickableWhenInactive) && forwardButton.isVisible()
                && isOverHeader(event.x(), event.y()) && forwardButton.isMouseOver(event.x(), event.y())) {
            dispatchMouseClicked(forwardButton, event);
            return event.finish(true);
        }
        return super.mouseClicked(event);
    }

    @Override
    public void setRenderingMode(RenderingMode mode) {
        super.setRenderingMode(mode);
        refreshForwarding();
    }

    @Override
    public void setResource(ResourceContainerItem resource) {
        if (toggleRequest != null && !ResourceDependencies.Stamp.of(resource).equals(toggleStamp)) {
            toggleRequest.cancel();
            toggleRequest = null;
        }
        cleanedUp = false;
        iconGeneration++;
        iconLoading = false;
        iconSource = null;
        forwardGeneration++;
        forwardBusy = false;
        forwardWaitingHint = "";
        forwardState = null;
        super.setResource(resource);
    }

    @Override
    public void cleanup() {
        if (toggleRequest != null) toggleRequest.cancel();
        toggleRequest = null;
        cleanedUp = true;
        iconGeneration++;
        forwardGeneration++;
        super.cleanup();
    }

    private void ensureImage() {
        if (cleanedUp || iconLoading) return;
        String source = resource.getIconUrl();
        boolean remote = source != null && !source.isBlank();
        if (remote ? source.equals(iconSource) : resource.getIconId() != null) return;
        if (!remote && (resource.getProjectId() == null || resource.getProviderName() == null)) return;
        iconLoading = true;
        iconSource = source;
        ResourceContainerItem pending = resource;
        long generation = ++iconGeneration;
        provider.resolveIcon(pending, icon -> ScreenManager.getInstance().execute(() -> {
            if (cleanedUp || generation != iconGeneration || pending != resource) return;
            iconLoading = false;
            if (icon != null) pending.setIconId(icon);
            refresh();
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
        refreshForwarding();
        if (getRenderingMode() == RenderingMode.UPDATE) {
            return;
        }
        var toggleCapability = provider.capability(ResourceContainerProvider.CAPABILITY_TOGGLE);
        toggleButton.setActive(toggleCapability.available() && (toggleRequest == null || toggleRequest.isDone()));
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
            if (owner != null && owner.toggleRequest != null && !owner.toggleRequest.isDone()) {
                toggle.setValue(originalState);
                return;
            }
            Async<Void> request = ResourceEnablePopup.toggle(owner == null ? null : owner.parentScreen, provider, resource, enabled);
            if (owner != null) {
                owner.toggleRequest = request;
                owner.toggleStamp = ResourceDependencies.Stamp.of(resource);
            }
            request.thenRun(() -> ScreenManager.getInstance().execute(() -> {
                if (resource.isEnabled() == enabled) return;
                boolean disabled = originalPath.toLowerCase(Locale.ROOT).endsWith(".disabled");
                String path = enabled && disabled ? originalPath.substring(0, originalPath.length() - ".disabled".length())
                        : !enabled && !disabled ? originalPath + ".disabled" : originalPath;
                resource.path(path);
                resource.setEnabled(enabled);
            })).exceptionally(e -> {
                ScreenManager.getInstance().execute(() -> {
                    if (cancelled(e)) {
                        toggle.setValue(resource.isEnabled());
                        return;
                    }
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
