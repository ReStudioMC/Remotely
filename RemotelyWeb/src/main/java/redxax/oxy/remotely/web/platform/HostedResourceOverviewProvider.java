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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class HostedResourceOverviewProvider implements ResourceOverviewProvider {
    private final HostedResourceContext context;
    private final AsyncResourceProvider source;
    private final ResourceMarketplaceProvider.Card card;
    private final OnlineResource resource;
    private final ResourceType type;
    private State state;
    private Runnable changed;
    private Consumer<ResourceBrowserContext.ResourceChange> resourceChanged;
    private final Runnable actionChanged;
    private final Set<Async<?>> pendingRequests = Collections.newSetFromMap(new IdentityHashMap<>());
    private long versionBatchGeneration;
    private long markdownWorkGeneration;
    private boolean disposed;

    HostedResourceOverviewProvider(HostedResourceContext context, ResourceMarketplaceProvider marketplace,
                                    ResourceMarketplaceProvider.Card card, ResourceType type, boolean replacement, Runnable actionChanged) {
        this.context = Objects.requireNonNull(context, "context");
        this.card = Objects.requireNonNull(card, "card");
        this.source = ((ResourceMarketplaceProviderAdapter) marketplace).source(card.provider());
        this.type = type == null ? ResourceType.getTypeFromString(card.type()) : type;
        this.resource = resource(card);
        this.actionChanged = actionChanged;
        Mode initialMode = replacement ? Mode.REPLACEMENT
                : this.type == ResourceType.MODPACK && context.hasLinkedModpack() ? Mode.LINKED
                : context.hasInstance() ? Mode.STANDARD : Mode.INSTALL;
        this.state = new State(initialMode, "", "");
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
        return track(source.getResourceDetailsAsync(card.id()));
    }

    @Override
    public Async<List<OnlineResourceVersion>> versions(List<String> loaders, List<String> gameVersions) {
        return track(source.getResourceVersionsAsync(card.id(), loaders, gameVersions, type));
    }

    @Override
    public Async<List<VersionUtil.McVersion>> gameVersionCatalog() {
        return track(source.getGameVersionCatalogAsync());
    }

    @Override
    public Async<Author> author(String identifier) {
        return track(source.getAuthorAsync(identifier));
    }

    @Override
    public Async<Identifier> avatar(Author author) {
        if (author == null || author.avatarUrl == null || author.avatarUrl.isBlank()) return Async.completed(null);
        return Async.completed(ScreenManager.getInstance().imageAssets().registerRemoteImage(author.avatarUrl));
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public Async<State> initialize(ReScreen screen, Runnable changed) {
        this.changed = changed;
        this.resourceChanged = event -> {
            if (!disposed && this.changed != null) this.changed.run();
        };
        context.addResourceListener(resourceChanged);
        if (changed != null) context.addCapabilityListener(changed);
        return track(context.refreshModpackCapabilities(card).thenCompose(ignored -> context.state(card)).thenApply(value -> state = map(value)));
    }

    @Override
    public void dispose() {
        disposed = true;
        if (resourceChanged != null) context.removeResourceListener(resourceChanged);
        if (changed != null) context.removeCapabilityListener(changed);
        resourceChanged = null;
        changed = null;
        cancelVersionWidgetBatches();
        cancelMarkdownWork();
        List<Async<?>> requests;
        synchronized (pendingRequests) {
            requests = List.copyOf(pendingRequests);
            pendingRequests.clear();
        }
        requests.forEach(Async::cancel);
    }

    @Override
    public void scheduleVersionWidgetBatch(Runnable task) {
        if (task == null || disposed) return;
        long generation = versionBatchGeneration;
        ScreenManager.getInstance().execute(() -> {
            if (!disposed && generation == versionBatchGeneration) task.run();
        });
    }

    @Override
    public void cancelVersionWidgetBatches() {
        versionBatchGeneration++;
    }

    @Override
    public boolean supportsIncrementalMarkdown() {
        return true;
    }

    @Override
    public void scheduleMarkdownWork(Runnable task) {
        if (task == null || disposed) return;
        long generation = markdownWorkGeneration;
        ScreenManager.getInstance().execute(() -> {
            if (!disposed && generation == markdownWorkGeneration) task.run();
        });
    }

    @Override
    public void cancelMarkdownWork() {
        markdownWorkGeneration++;
    }

    @Override
    public Availability availability(Action action, OnlineResourceVersion version, OnlineResourceVersion.VersionFile file) {
        if (state.mode() == Mode.LINKED) return Availability.unavailable("Linked Modpack Is Read Only");
        if (action == Action.DOWNLOAD_LATEST && state.mode() == Mode.STANDARD && context.hasInstance()) {
            return Availability.supported();
        }
        ResourceOverviewContext.ActionRequest request = request(action, version, file);
        ResourceOverviewContext.Availability availability = context.availability(request);
        return new Availability(availability.available(), availability.reason());
    }

    @Override
    public void perform(ReScreen screen, Action action, OnlineResourceVersion version, OnlineResourceVersion.VersionFile file,
                        Runnable refreshed) {
        if (disposed) return;
        if (state.mode() == Mode.LINKED) {
            context.log("Resource Action Failed", new UnsupportedOperationException("Linked Modpack Is Read Only"));
            return;
        }
        Async<Void> operation = context.action(request(action, version, file), () -> {
            if (disposed) return;
            track(context.state(card)).whenComplete((value, failure) -> {
                if (disposed) return;
                if (failure == null && value != null) state = map(value);
                if (refreshed != null) refreshed.run();
            });
        });
        track(operation).whenComplete((ignored, failure) -> {
            if (disposed) return;
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
    public void installWorld(ReScreen screen, OnlineResourceVersion version, String name, boolean replace,
                             boolean allowIncompatible, Runnable refreshed) {
        if (disposed || version == null) return;
        if (!allowIncompatible && !ResourceCompatibilityTokens.matches(version,
                context.providerLoaderTokens(ResourceType.WORLD), context.versionId(), ResourceType.WORLD)) {
            new Notification("World Incompatible", "Select A Compatible World Version", Notification.Type.ERROR);
            return;
        }
        Notification progress = new Notification("Installing World", "Downloading And Extracting World Archive", Notification.Type.INFO);
        progress.update().loading(true).commit();
        context.installWorld(card, version(version), name, replace).whenComplete((path, failure) ->
                ScreenManager.getInstance().execute(() -> {
                    if (failure != null) {
                        progress.update().message("World Install Failed").description(this.failure(failure))
                                .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
                        context.log("World Install Failed", failure);
                        return;
                    }
                    progress.update().message("World Installed").description(path.fileName())
                            .type(Notification.Type.SUCCESS).loading(false).autoSlideOut(true).commit();
                    if (disposed) return;
                    context.state(card).whenComplete((value, refreshFailure) -> {
                        if (disposed) return;
                        if (refreshFailure == null && value != null) state = map(value);
                        if (refreshed != null) refreshed.run();
                    });
                    if (actionChanged != null) actionChanged.run();
                }));
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
            case DOWNLOAD_LATEST -> state.mode() == Mode.REPLACEMENT
                    ? ResourceMarketplaceProvider.Action.CHANGE_MODPACK : ResourceMarketplaceProvider.Action.UPDATE;
            case INSTALL, INSTALL_INCOMPATIBLE -> state.mode() == Mode.REPLACEMENT
                    ? ResourceMarketplaceProvider.Action.CHANGE_MODPACK : ResourceMarketplaceProvider.Action.INSTALL;
        };
        return new ResourceOverviewContext.ActionRequest(mappedAction, card, mappedVersion, mappedFile);
    }

    private State map(ResourceOverviewContext.State value) {
        if (value == null) return state;
        Mode mode = state.mode() == Mode.REPLACEMENT || state.mode() == Mode.INSTALL || state.mode() == Mode.LINKED
                ? state.mode() : switch (value.mode()) {
            case INSTALL -> Mode.INSTALL;
            case REPLACEMENT -> Mode.REPLACEMENT;
            case LINKED -> Mode.LINKED;
            case STANDARD -> Mode.STANDARD;
        };
        return new State(mode, value.installedVersionId(), value.currentFile());
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

    private <T> Async<T> track(Async<T> request) {
        Objects.requireNonNull(request, "request");
        boolean cancel;
        synchronized (pendingRequests) {
            cancel = disposed;
            if (!cancel) pendingRequests.add(request);
        }
        if (cancel) {
            request.cancel();
            return request;
        }
        request.whenComplete((ignored, failure) -> {
            synchronized (pendingRequests) {
                pendingRequests.remove(request);
            }
        });
        return request;
    }
}
