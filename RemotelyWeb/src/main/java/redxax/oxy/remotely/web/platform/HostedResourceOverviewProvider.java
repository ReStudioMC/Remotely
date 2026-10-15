package redxax.oxy.remotely.web.platform;

import restudio.rescreen.platform.Async;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.util.VersionUtil;
import restudio.rebase.resource.marketplace.ResourceBrowserContext;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProvider;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProviderAdapter;
import restudio.rebase.resource.marketplace.ResourceOverviewContext;
import restudio.rebase.resource.marketplace.ResourceOverviewProvider;
import restudio.rebase.resource.provider.AsyncResourceProvider;
import restudio.rebase.resource.provider.Author;
import restudio.rebase.resource.provider.OnlineResource;
import restudio.rebase.resource.provider.OnlineResourceVersion;
import restudio.rebase.resource.provider.ResourceProviderException;
import restudio.rebase.resource.provider.ResourceCompatibilityTokens;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.platform.GalleryMediaProvider;
import restudio.rescreen.platform.browser.BrowserGalleryMediaProvider;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.util.Notification;
import restudio.rescreen.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class HostedResourceOverviewProvider implements ResourceOverviewProvider {
    private final HostedResourceContext context;
    private final AsyncResourceProvider source;
    private final ResourceMarketplaceProvider.Card card;
    private final OnlineResource resource;
    private final ResourceType type;
    private final Mode mode;
    private Snapshot snapshot;
    private Runnable changed;
    private Runnable capabilityChanged;
    private Consumer<ResourceBrowserContext.ResourceChange> resourceChanged;
    private final Runnable actionChanged;
    private final Set<Async<?>> pendingRequests = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Runnable> refreshCallbacks = new ArrayList<>();
    private Async<State> initialization;
    private Async<State> refreshRequest;
    private long lifecycle;
    private long activity;
    private long revision;
    private long versionBatchGeneration;
    private long markdownWorkGeneration;
    private boolean active;
    private boolean reading;
    private boolean scheduled;
    private boolean refreshRequested;
    private boolean capabilitiesRequested;
    private boolean notifyRequested;
    private boolean disposed;

    private record Snapshot(State state, boolean installsModpack) {
    }

    HostedResourceOverviewProvider(HostedResourceContext context, ResourceMarketplaceProvider marketplace,
                                    ResourceMarketplaceProvider.Card card, ResourceType type, boolean replacement, Runnable actionChanged) {
        this.context = Objects.requireNonNull(context, "context");
        this.card = Objects.requireNonNull(card, "card");
        this.source = ((ResourceMarketplaceProviderAdapter) marketplace).source(card.provider());
        this.type = type == null ? ResourceType.getTypeFromString(card.type()) : type;
        this.resource = resource(card);
        this.actionChanged = actionChanged;
        this.mode = replacement ? Mode.REPLACEMENT
                : this.type == ResourceType.MODPACK && context.hasLinkedModpack() ? Mode.LINKED
                : context.hasInstance() ? Mode.STANDARD : Mode.INSTALL;
        this.snapshot = snapshot(new State(mode, "", ""));
    }

    @Override
    public String name() {
        return source.getName();
    }

    @Override
    public OnlineResource resource() {
        return resource;
    }

    @Override
    public ResourceType resourceType() {
        return type;
    }

    @Override
    public boolean serverContext() {
        return context.isServer();
    }

    @Override
    public GalleryMediaProvider galleryMedia() {
        return BrowserGalleryMediaProvider.INSTANCE;
    }

    @Override
    public Async<OnlineResource> details() {
        return request(() -> source.getResourceDetailsAsync(card.id()));
    }

    @Override
    public Async<List<OnlineResourceVersion>> versions(List<String> loaders, List<String> gameVersions) {
        return request(() -> source.getResourceVersionsAsync(card.id(), loaders, gameVersions, type));
    }

    @Override
    public Async<List<VersionUtil.McVersion>> gameVersionCatalog() {
        return request(source::getGameVersionCatalogAsync);
    }

    @Override
    public Async<Author> author(String identifier) {
        return request(() -> source.getAuthorAsync(identifier));
    }

    @Override
    public Async<Identifier> avatar(Author author) {
        return request(() -> Async.completed(author == null || author.avatarUrl == null || author.avatarUrl.isBlank()
                ? null : ScreenManager.getInstance().imageAssets().registerRemoteImage(author.avatarUrl)));
    }

    @Override
    public synchronized State state() {
        return snapshot.state();
    }

    @Override
    public synchronized boolean installsModpack() {
        return snapshot.installsModpack();
    }

    @Override
    public synchronized Async<State> initialize(ReScreen screen, Runnable changed) {
        dispose();
        disposed = false;
        active = true;
        this.changed = changed;
        initialization = Async.pending();
        attach();
        requestRefresh(lifecycle, activity, true, true);
        return initialization;
    }

    @Override
    public synchronized void setActive(boolean active) {
        if (disposed || initialization == null || this.active == active) return;
        this.active = active;
        activity++;
        revision++;
        detach();
        reading = false;
        scheduled = false;
        refreshRequested = true;
        capabilitiesRequested = true;
        notifyRequested = true;
        Async<State> request = refreshRequest;
        refreshRequest = null;
        if (request != null) request.cancel();
        if (active) {
            attach();
            queueRefresh();
        }
    }

    @Override
    public synchronized void dispose() {
        lifecycle++;
        activity++;
        revision++;
        disposed = true;
        active = false;
        detach();
        changed = null;
        reading = false;
        scheduled = false;
        refreshRequested = false;
        capabilitiesRequested = false;
        notifyRequested = false;
        refreshRequest = null;
        refreshCallbacks.clear();
        if (initialization != null) initialization.cancel();
        initialization = null;
        cancelVersionWidgetBatches();
        cancelMarkdownWork();
        List<Async<?>> requests = List.copyOf(pendingRequests);
        pendingRequests.clear();
        requests.forEach(Async::cancel);
    }

    @Override
    public synchronized void scheduleVersionWidgetBatch(Runnable task) {
        if (task == null || disposed) return;
        long epoch = lifecycle;
        long generation = versionBatchGeneration;
        publish(epoch, () -> {
            if (generation == versionBatchGeneration) task.run();
        });
    }

    @Override
    public synchronized void cancelVersionWidgetBatches() {
        versionBatchGeneration++;
    }

    @Override
    public boolean supportsIncrementalMarkdown() {
        return true;
    }

    @Override
    public synchronized void scheduleMarkdownWork(Runnable task) {
        if (task == null || disposed) return;
        long epoch = lifecycle;
        long generation = markdownWorkGeneration;
        publish(epoch, () -> {
            if (generation == markdownWorkGeneration) task.run();
        });
    }

    @Override
    public synchronized void cancelMarkdownWork() {
        markdownWorkGeneration++;
    }

    @Override
    public Availability availability(Action action, OnlineResourceVersion version, OnlineResourceVersion.VersionFile file) {
        if (state().mode() == Mode.LINKED) return Availability.unavailable("Linked Modpack Is Read Only");
        if (action == Action.DOWNLOAD_LATEST && state().mode() == Mode.STANDARD && context.hasInstance()) {
            return Availability.supported();
        }
        ResourceOverviewContext.ActionRequest request = request(action, version, file);
        ResourceOverviewContext.Availability availability = context.availability(request);
        return new Availability(availability.available(), availability.reason());
    }

    @Override
    public synchronized void perform(ReScreen screen, Action action, OnlineResourceVersion version, OnlineResourceVersion.VersionFile file,
                        Runnable refreshed) {
        if (disposed || !active) return;
        if (state().mode() == Mode.LINKED) {
            context.log("Resource Action Failed", new UnsupportedOperationException("Linked Modpack Is Read Only"));
            return;
        }
        long epoch = lifecycle;
        Async<Void> operation = context.action(request(action, version, file), () -> refreshAfterOperation(epoch, refreshed));
        publish(epoch, this::notifyChanged);
        track(operation, epoch).whenComplete((ignored, failure) -> {
            if (!current(epoch)) return;
            notifyChanged();
            if (!current(epoch)) return;
            if (failure != null) context.log("Resource Action Failed", failure);
            else if (actionChanged != null) actionChanged.run();
        });
    }

    @Override
    public boolean compatible(OnlineResourceVersion version) {
        return type != ResourceType.WORLD || ResourceCompatibilityTokens.matches(version,
                context.providerLoaderTokens(ResourceType.WORLD), context.versionId(), ResourceType.WORLD);
    }

    @Override
    public synchronized void installWorld(ReScreen screen, OnlineResourceVersion version, String name, boolean replace,
                             boolean allowIncompatible, Runnable refreshed) {
        if (disposed || !active || version == null) return;
        if (!allowIncompatible && !ResourceCompatibilityTokens.matches(version,
                context.providerLoaderTokens(ResourceType.WORLD), context.versionId(), ResourceType.WORLD)) {
            new Notification("World Incompatible", "Select A Compatible World Version", Notification.Type.ERROR);
            return;
        }
        Notification progress = new Notification("Installing World", "Downloading And Extracting World Archive", Notification.Type.INFO);
        progress.update().loading(true).commit();
        long epoch = lifecycle;
        var operation = track(context.installWorld(card, version(version), name, replace), epoch);
        operation.onCancel(() -> ScreenManager.getInstance().execute(() -> progress.update().loading(false).autoSlideOut(true).commit()));
        publish(epoch, this::notifyChanged);
        operation.whenComplete((path, failure) -> {
            if (!current(epoch)) return;
            notifyChanged();
            if (!current(epoch)) return;
            if (failure != null) {
                progress.update().message("World Install Failed").description(this.failure(failure))
                        .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                context.log("World Install Failed", failure);
                return;
            }
            progress.update().message("World Installed").description(path.fileName())
                    .type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
            refreshAfterOperation(epoch, refreshed);
            if (actionChanged != null) actionChanged.run();
        });
    }

    @Override
    public void openExternal(String url) {
        context.openExternal(url);
    }

    @Override
    public String failure(Throwable error) {
        Throwable current = ResourceProviderException.unwrap(error);
        String message = current == null ? null : current.getMessage();
        if (message == null || message.isBlank()) message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "Resource Request Failed" : message;
    }

    private ResourceOverviewContext.ActionRequest request(Action action, OnlineResourceVersion version,
                                                          OnlineResourceVersion.VersionFile file) {
        ResourceMarketplaceProvider.Version mappedVersion = version(version);
        ResourceMarketplaceProvider.VersionFile mappedFile = file(file);
        ResourceMarketplaceProvider.Action mappedAction = switch (action) {
            case DELETE -> ResourceMarketplaceProvider.Action.DELETE;
            case DOWNLOAD_LATEST -> state().mode() == Mode.REPLACEMENT
                    ? ResourceMarketplaceProvider.Action.CHANGE_MODPACK : ResourceMarketplaceProvider.Action.UPDATE;
            case INSTALL, INSTALL_INCOMPATIBLE -> state().mode() == Mode.REPLACEMENT
                    ? ResourceMarketplaceProvider.Action.CHANGE_MODPACK : ResourceMarketplaceProvider.Action.INSTALL;
        };
        return new ResourceOverviewContext.ActionRequest(mappedAction, card, mappedVersion, mappedFile);
    }

    private State map(ResourceOverviewContext.State value) {
        if (value == null) return state();
        Mode mappedMode = mode == Mode.REPLACEMENT || mode == Mode.INSTALL ? mode : switch (value.mode()) {
            case INSTALL -> Mode.INSTALL;
            case REPLACEMENT -> Mode.REPLACEMENT;
            case LINKED -> Mode.LINKED;
            case STANDARD -> Mode.STANDARD;
        };
        return new State(mappedMode, value.installedVersionId(), value.currentFile());
    }

    private Snapshot snapshot(State state) {
        return new Snapshot(state, installsModpack(state));
    }

    private boolean installsModpack(State state) {
        return state.mode() == Mode.INSTALL
                || state.mode() == Mode.REPLACEMENT && context.hasInstance() && !context.hasLinkedModpack();
    }

    private void attach() {
        long epoch = lifecycle;
        long stamp = activity;
        resourceChanged = event -> requestRefresh(epoch, stamp, false, false);
        capabilityChanged = () -> requestRefresh(epoch, stamp, true, true);
        context.addResourceListener(resourceChanged);
        context.addCapabilityListener(capabilityChanged);
    }

    private void detach() {
        if (resourceChanged != null) context.removeResourceListener(resourceChanged);
        if (capabilityChanged != null) context.removeCapabilityListener(capabilityChanged);
        resourceChanged = null;
        capabilityChanged = null;
    }

    private synchronized void requestRefresh(long epoch, long stamp, boolean capabilities, boolean notify) {
        if (!current(epoch, stamp)) return;
        revision++;
        refreshRequested = true;
        capabilitiesRequested |= capabilities;
        notifyRequested |= notify;
        queueRefresh();
    }

    private void queueRefresh() {
        if (disposed || !active || reading || scheduled || !refreshRequested) return;
        scheduled = true;
        long epoch = lifecycle;
        long stamp = activity;
        publish(epoch, () -> readState(epoch, stamp));
    }

    private void readState(long epoch, long stamp) {
        if (!current(epoch, stamp)) return;
        scheduled = false;
        if (reading || !refreshRequested) return;
        reading = true;
        refreshRequested = false;
        long readRevision = revision;
        boolean capabilities = capabilitiesRequested;
        capabilitiesRequested = false;
        try {
            Async<Void> preparation = capabilities ? context.refreshModpackCapabilities(card) : Async.completed(null);
            Async<State> request = track(preparation.thenCompose(ignored -> {
                synchronized (this) {
                    if (!current(epoch, stamp) || readRevision != revision) return Async.failed(new Async.Cancellation());
                    return context.state(card).thenApply(this::map);
                }
            }), epoch);
            refreshRequest = request;
            request.whenComplete((value, failure) -> finishRead(epoch, stamp, readRevision, value, failure));
        } catch (Throwable failure) {
            finishRead(epoch, stamp, readRevision, null, failure);
        }
    }

    private synchronized void finishRead(long epoch, long stamp, long readRevision, State value, Throwable failure) {
        if (!current(epoch, stamp)) return;
        reading = false;
        refreshRequest = null;
        if (readRevision != revision) {
            queueRefresh();
            return;
        }
        boolean notify = notifyRequested;
        notifyRequested = false;
        if (failure == null) {
            Snapshot next = snapshot(value);
            if (!snapshot.equals(next)) {
                snapshot = next;
                notify = true;
            }
            if (initialization != null) initialization.complete(snapshot.state());
        } else {
            boolean initializing = initialization != null && !initialization.isDone();
            if (initializing) initialization.fail(failure);
            else context.log("Resource State Unavailable", failure);
            notify = true;
        }
        if (!current(epoch, stamp)) return;
        List<Runnable> callbacks = List.copyOf(refreshCallbacks);
        refreshCallbacks.clear();
        if (notify) notifyChanged();
        for (Runnable callback : callbacks) {
            if (!current(epoch, stamp)) break;
            callback.run();
        }
        queueRefresh();
    }

    private void refreshAfterOperation(long epoch, Runnable refreshed) {
        publish(epoch, () -> {
            if (refreshed != null) refreshCallbacks.add(refreshed);
            revision++;
            refreshRequested = true;
            queueRefresh();
        });
    }

    private void notifyChanged() {
        if (active) {
            if (changed != null) changed.run();
        } else notifyRequested = true;
    }

    private synchronized boolean current(long epoch) {
        return !disposed && epoch == lifecycle;
    }

    private boolean current(long epoch, long stamp) {
        return current(epoch) && active && stamp == activity;
    }

    private void publish(long epoch, Runnable task) {
        ScreenManager.getInstance().execute(() -> {
            synchronized (this) {
                if (current(epoch)) task.run();
            }
        });
    }

    private static OnlineResource resource(ResourceMarketplaceProvider.Card card) {
        OnlineResource value = new OnlineResource();
        value.id = card.id();
        value.slug = card.slug();
        value.projectType = card.type();
        value.title = card.title();
        value.description = card.description();
        value.authors = card.authors();
        value.downloads = card.downloads();
        value.followers = card.followers();
        value.iconUrl = card.iconUrl();
        value.bannerUrl = card.bannerUrl();
        value.categories = card.categories();
        value.supportedMcVersions = card.gameVersions();
        value.loaders = card.loaders();
        value.clientSide = card.clientSide();
        value.serverSide = card.serverSide();
        return value;
    }

    private static ResourceMarketplaceProvider.Version version(OnlineResourceVersion value) {
        if (value == null) return null;
        List<ResourceMarketplaceProvider.VersionFile> files = value.files == null ? List.of() : value.files.stream()
                .filter(Objects::nonNull).map(HostedResourceOverviewProvider::file).toList();
        return new ResourceMarketplaceProvider.Version(value.id, value.name, value.versionNumber, value.versionType,
                value.datePublished, value.gameVersions, value.loaders, value.changelog,
                files.isEmpty() ? null : files.getFirst().name(), files.isEmpty() ? 0 : files.getFirst().size(), files, List.of());
    }

    private static ResourceMarketplaceProvider.VersionFile file(OnlineResourceVersion.VersionFile value) {
        return value == null ? null : new ResourceMarketplaceProvider.VersionFile(value.id, value.filename, value.size,
                value.primary, value.isServerPack, value.url);
    }

    private synchronized <T> Async<T> request(Supplier<Async<T>> start) {
        if (disposed) {
            Async<T> cancelled = Async.pending();
            cancelled.cancel();
            return cancelled;
        }
        return track(start.get(), lifecycle);
    }

    private synchronized <T> Async<T> track(Async<T> request, long epoch) {
        Objects.requireNonNull(request, "request");
        Async<T> result = Async.pending();
        result.onCancel(request::cancel);
        if (!current(epoch)) {
            result.cancel();
            return result;
        }
        pendingRequests.add(result);
        result.whenComplete((ignored, failure) -> {
            synchronized (this) {
                pendingRequests.remove(result);
            }
        });
        request.whenComplete((value, failure) -> publish(epoch, () -> {
            if (failure == null) result.complete(value);
            else result.fail(failure);
        }));
        return result;
    }
}
