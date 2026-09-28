package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import restudio.rebase.backend.CapabilityDescriptor;
import restudio.rescreen.platform.Async;
import restudio.rebase.resource.ResourceIndexOrchestrator;
import restudio.rebase.resource.ResourceType;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProvider;
import restudio.rebase.resource.marketplace.ResourceMarketplaceProviderAdapter;
import restudio.rebase.resource.provider.OnlineResourceVersion;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rebase.ui.screens.resources.ResourceContainerProvider;
import restudio.rebase.ui.screens.resources.ResourceContainerItem;
import restudio.rebase.ui.screens.resources.ResourceContainerUpdate;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.Notification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class HostedResourceContainerProvider implements ResourceContainerProvider {
    private final BrowserServerScreenHost screenHost;
    private final ServerModels.ClientServerView server;
    private final ResourceMarketplaceProviderAdapter marketplace;
    private final HostedResourceContext resourceContext;
    private final Map<String, ResourceMarketplaceProvider.Card> cards = new LinkedHashMap<>();
    private final Map<String, String> icons = new LinkedHashMap<>();
    private final Map<String, Identifier> iconIds = new LinkedHashMap<>();
    private Identifier missingIcon;
    private final Map<Consumer<List<ResourceContainerItem>>, Consumer<HostedResourceContext.CanonicalResourceInventory>> snapshotListeners = new LinkedHashMap<>();
    private String lastResourceWarning = "";

    HostedResourceContainerProvider(BrowserServerScreenHost screenHost, ServerModels.ClientServerView server,
                                     ResourceMarketplaceProviderAdapter marketplace, HostedResourceContext resourceContext) {
        this.screenHost = screenHost;
        this.server = server;
        this.marketplace = marketplace;
        this.resourceContext = resourceContext;
    }

    @Override
    public Object instance() {
        return server;
    }

    @Override
    public Async<List<ResourceContainerItem>> load(boolean force) {
        return resourceContext.canonicalResourceInventory(force).thenApply(this::resourceSnapshot);
    }

    @Override
    public void addResourceSnapshotListener(Consumer<List<ResourceContainerItem>> listener) {
        if (listener == null || snapshotListeners.containsKey(listener)) return;
        Consumer<HostedResourceContext.CanonicalResourceInventory> bridge = inventory -> listener.accept(resourceSnapshot(inventory));
        snapshotListeners.put(listener, bridge);
        resourceContext.addCanonicalInventoryListener(bridge);
    }

    @Override
    public void removeResourceSnapshotListener(Consumer<List<ResourceContainerItem>> listener) {
        Consumer<HostedResourceContext.CanonicalResourceInventory> bridge = snapshotListeners.remove(listener);
        if (bridge != null) resourceContext.removeCanonicalInventoryListener(bridge);
    }

    private List<ResourceContainerItem> resourceSnapshot(HostedResourceContext.CanonicalResourceInventory inventory) {
        Map<String, String> previousIcons = new LinkedHashMap<>(icons);
        cards.clear();
        icons.clear();
        if (inventory == null) {
            releaseIcons();
            return List.of();
        }
        String warning = inventory.warning();
        if (!warning.equals(lastResourceWarning)) {
            lastResourceWarning = warning;
            if (!warning.isBlank()) new Notification("Some Resources Could Not Be Loaded",
                    "Showing Available Resources. " + warning, Notification.Type.WARN);
        }
        List<ResourceContainerItem> resources = inventory.entries().stream().filter(Objects::nonNull)
                .map(this::resource).toList();
        List<ResourceContainerItem> nested = hierarchy(resources, inventory.modpack());
        HostedResourceContext.ModpackProfile profile = inventory.modpack();
        if (profile != null && !nested.isEmpty() && profile.provider() != null && !profile.provider().isBlank()
                && profile.projectId() != null && !profile.projectId().isBlank()) {
            ResourceMarketplaceProvider.Card card = new ResourceMarketplaceProvider.Card(profile.provider(), profile.projectId(),
                    profile.projectId(), ResourceType.MODPACK.getModrinthProjectType(), profile.name(), nested.getFirst().getDescription(),
                    List.of(), 0, 0, null, null, true, false);
            cards.put(nested.getFirst().path(), card);
        }
        List<Map.Entry<String, Identifier>> staleIcons = iconIds.entrySet().stream().filter(entry -> {
            String key = entry.getKey();
            String previous = previousIcons.get(key);
            String current = icons.get(key);
            return !(Objects.equals(previous, current) && current != null && !current.isBlank());
        }).toList();
        staleIcons.forEach(entry -> releaseIcon(entry.getKey(), entry.getValue()));
        return nested;
    }

    @Override
    public Async<Void> delete(ResourceContainerItem resource, boolean permanent) {
        ResourceMarketplaceProvider.Card card = card(resource);
        if (card != null && resourceContext.supports(ResourceMarketplaceProvider.Action.DELETE, card)) {
            return resourceContext.action(ResourceMarketplaceProvider.Action.DELETE, card, null, null);
        }
        if (!fileCapability("files.delete").available()) return failed("Resource Removal Is Unavailable");
        return screenHost.capabilities(server).deleteFiles(server, directory(resource), List.of(resource.getFileName()))
                .thenRun(resourceContext::invalidateFileCache);
    }

    @Override
    public Async<Boolean> detach(ResourceContainerItem resource) {
        return resourceContext.detachResource(key(resource));
    }

    @Override
    public Async<Map<String, OnlineResourceVersion>> checkUpdates(List<ResourceContainerItem> resources,
                                                                  BiConsumer<Integer, Integer> progress) {
        Map<String, OnlineResourceVersion> updates = new LinkedHashMap<>();
        List<ResourceContainerItem> values = resources == null ? List.of() : resources;
        for (int index = 0; index < values.size(); index++) {
            ResourceContainerItem resource = values.get(index);
            if (resource != null && resource.availableUpdate != null && resource.getProjectId() != null) {
                updates.put(resource.getProjectId(), resource.availableUpdate);
            }
            if (progress != null) progress.accept(index + 1, values.size());
        }
        return Async.completed(updates);
    }

    @Override
    public Async<OnlineResourceVersion> checkUpdate(ResourceContainerItem resource) {
        return Async.completed(resource == null ? null : resource.availableUpdate);
    }

    @Override
    public Async<Void> update(ResourceContainerItem resource, OnlineResourceVersion version,
                              BiConsumer<Long, Long> progress, Runnable refreshed, boolean backup) {
        ResourceMarketplaceProvider.Card card = card(resource);
        if (card == null || !resourceContext.supports(ResourceMarketplaceProvider.Action.UPDATE, card)) {
            return failed("Resource Update Is Unavailable");
        }
        Async<Void> prepared = backup ? resourceContext.createResourceBackup("Pre-Update backup | " + resource.getName())
                : Async.completed(null);
        return prepared.thenCompose(ignored -> resourceContext.action(ResourceMarketplaceProvider.Action.UPDATE, card, null, refreshed));
    }

    @Override
    public Async<Void> updateAll(List<ResourceContainerUpdate> updates, BiConsumer<String, Integer> progress,
                                 Runnable refreshed, boolean backup) {
        List<ResourceContainerUpdate> values = updates == null ? List.of() : updates;
        if (values.isEmpty()) return Async.completed(null);
        Async<Void> result = backup ? resourceContext.createResourceBackup("Pre-Update backup for " + values.size() + " resources")
                : Async.completed(null);
        for (int index = 0; index < values.size(); index++) {
            ResourceContainerUpdate update = values.get(index);
            int percentage = values.isEmpty() ? 100 : (index + 1) * 100 / values.size();
            result = result.thenCompose(ignored -> update(update.resource, update.newVersion, null, null, false))
                    .thenRun(() -> {
                        if (progress != null) progress.accept("Updating " + update.resource.getName(), percentage);
                    });
        }
        return result.thenRun(() -> {
            if (refreshed != null) refreshed.run();
        });
    }

    @Override
    public Async<Void> toggle(ResourceContainerItem resource, boolean enabled) {
        if (resource == null || !fileCapability("resources.toggle").available()) return failed("Resource Toggle Is Unavailable");
        String source = key(resource);
        boolean disabled = source.toLowerCase(Locale.ROOT).endsWith(".disabled");
        String target = enabled && disabled ? source.substring(0, source.length() - ".disabled".length())
                : !enabled && !disabled ? source + ".disabled" : source;
        if (source.equals(target)) {
            resource.setEnabled(enabled);
            return Async.completed(null);
        }
        return screenHost.serverApi().toggleResource(server.identifier, source, enabled)
                .handle((ignored, failure) -> failure)
                .thenCompose(failure -> {
                    if (failure == null) {
                        applyToggle(resource, source, target, enabled);
                        return Async.completed(null);
                    }
                    String directory = directory(resource);
                    String sourceName = source.substring(source.lastIndexOf('/') + 1);
                    String targetName = target.substring(target.lastIndexOf('/') + 1);
                    return screenHost.serverApi().listResourceFiles(server.identifier, directory)
                            .handle((files, readFailure) -> readFailure == null && files != null
                                    && files.stream().anyMatch(file -> file != null && targetName.equals(file.name))
                                    && files.stream().noneMatch(file -> file != null && sourceName.equals(file.name)))
                            .thenCompose(confirmed -> {
                                if (!confirmed) return Async.failed(failure);
                                applyToggle(resource, source, target, enabled);
                                return Async.completed(null);
                            });
                });
    }

    private void applyToggle(ResourceContainerItem resource, String source, String target, boolean enabled) {
        resource.path(target);
        resource.setEnabled(enabled);
        ResourceMarketplaceProvider.Card card = cards.remove(source);
        if (card != null) cards.put(target, card);
        String icon = icons.remove(source);
        if (icon != null) icons.put(target, icon);
        Identifier iconId = iconIds.remove(source);
        if (iconId != null) iconIds.put(target, iconId);
        resourceContext.invalidateFileCache();
    }

    @Override
    public Map<String, List<String>> groups() {
        return resourceContext.resourceGroups();
    }

    @Override
    public void saveGroups(Map<String, List<String>> groups) {
        resourceContext.saveResourceGroups(groups);
    }

    @Override
    public void openBrowser(ReScreen parent) {
        if (parent != null && screenHost != null && server != null) {
            screenHost.openServerResourceBrowser(parent, server, HostedResourceContext.defaultResourceType(server.loader));
        }
    }

    @Override
    public void openResource(ReScreen parent, ResourceContainerItem resource, Runnable refreshed) {
        ResourceMarketplaceProvider.Card card = card(resource);
        if (card != null && card.provider() != null) {
            resourceContext.openResource(parent, marketplace, card, resource.getType(), true, screenHost, false, false, refreshed);
        }
    }

    @Override
    public void resolveIcon(ResourceContainerItem resource, Consumer<Identifier> resolved) {
        if (resolved == null) return;
        String resourceKey = key(resource);
        String icon = icons.get(resourceKey);
        Identifier current = iconIds.get(resourceKey);
        if (icon == null || icon.isBlank()) {
            if (current != null) {
                releaseIcon(resourceKey, current);
            }
            resolved.accept(missingIcon());
            return;
        }
        if (current != null) {
            releaseIcon(resourceKey, current);
            current = null;
        }
        if (current == null && screenHost != null && screenHost.application() != null) {
            current = screenHost.application().registerRemoteImage(icon);
            if (current != null) iconIds.put(resourceKey, current);
        }
        resolved.accept(current);
    }

    private Identifier missingIcon() {
        if (missingIcon == null) missingIcon = Identifier.icon("missing.png");
        return missingIcon;
    }

    @Override
    public void stopWatching() {
        releaseIcons();
        resourceContext.invalidate();
    }

    private void releaseIcons() {
        if (screenHost == null || screenHost.application() == null) {
            iconIds.clear();
            return;
        }
        Map<String, Identifier> owned = new LinkedHashMap<>(iconIds);
        iconIds.clear();
        owned.values().forEach(screenHost.application()::releaseRemoteImage);
    }

    private void releaseIcon(String resourceKey, Identifier icon) {
        iconIds.remove(resourceKey, icon);
        if (screenHost != null && screenHost.application() != null) screenHost.application().releaseRemoteImage(icon);
    }

    @Override
    public CapabilityDescriptor capability(String id) {
        if (CAPABILITY_TOGGLE.equals(id)) return fileCapability("resources.toggle");
        if (CAPABILITY_DELETE.equals(id)) return fileCapability("files.delete");
        if (CAPABILITY_OPEN.equals(id)) return CapabilityDescriptor.supported(id);
        return resourceContext.capability(id);
    }

    private ResourceContainerItem resource(ResourceIndexOrchestrator.ResolvedEntry source) {
        ResourceType type = resourceType(source.directoryPath());
        String path = source.path();
        ResourceIndexOrchestrator.ResolvedMetadata metadata = source.metadata();
        String filename = source.fileName();
        ResourceContainerItem resource = new ResourceContainerItem(null, type, filename, source.enabled());
        resource.path(path);
        resource.setFileHash(source.hash());
        if (metadata == null) metadata = rememberedMetadata(path);
        if (metadata != null) {
            resource.setName(metadata.name() == null || metadata.name().isBlank() ? filename : metadata.name());
            resource.setDescription(metadata.description());
            resource.setVersion(metadata.version());
            resource.setAuthors(metadata.authors());
            resource.setProviderName(metadata.provider());
            resource.setProjectId(metadata.projectId());
            resource.setVersionId(metadata.versionId());
            resource.availableUpdate(metadata.availableUpdate());
        }
        if (metadata != null && metadata.provider() != null && !metadata.provider().isBlank()
                && metadata.projectId() != null && !metadata.projectId().isBlank()) {
            ResourceMarketplaceProvider.Card card = new ResourceMarketplaceProvider.Card(metadata.provider(), metadata.projectId(),
                    metadata.projectId(), type.getModrinthProjectType(), resource.getName(), resource.getDescription(), resource.getAuthors(),
                    0, 0, metadata.iconUrl(), null, true, metadata.availableUpdate() != null);
            cards.put(resource.path(), card);
        }
        if (metadata != null && metadata.iconUrl() != null && !metadata.iconUrl().isBlank()) icons.put(resource.path(), metadata.iconUrl());
        else resource.setIconId(missingIcon());
        return resource;
    }

    private ResourceIndexOrchestrator.ResolvedMetadata rememberedMetadata(String path) {
        ResourceMarketplaceProvider.Card card = cards.get(path);
        if (card == null || card.iconUrl() == null || card.iconUrl().isBlank()) return null;
        return new ResourceIndexOrchestrator.ResolvedMetadata(card.provider(), card.id(), null, null,
                card.title(), card.description(), card.authors(), String.join(", ", card.gameVersions() == null ? List.of() : card.gameVersions()),
                card.iconUrl(), null);
    }

    private static ResourceType resourceType(String directory) {
        String value = directory == null ? "" : directory.toLowerCase();
        if (value.contains("resourcepack")) return ResourceType.RESOURCE_PACK;
        if (value.contains("shaderpack")) return ResourceType.SHADER_PACK;
        if (value.contains("datapack")) return ResourceType.DATA_PACK;
        return value.contains("plugin") ? ResourceType.PLUGIN : ResourceType.MOD;
    }

    static List<ResourceContainerItem> hierarchy(List<ResourceContainerItem> resources,
                                                 HostedResourceContext.ModpackProfile profile) {
        List<ResourceContainerItem> flat = resources == null ? List.of() : resources.stream().filter(value -> value != null).toList();
        if (profile == null) return flat;
        List<ResourceContainerItem> children = flat.stream().filter(resource -> profile.owns(resource.path())).toList();
        List<ResourceContainerItem> independent = flat.stream().filter(resource -> !profile.owns(resource.path())).toList();
        ResourceContainerItem parent = new ResourceContainerItem(".meta/modpack-profile.json", ResourceType.MODPACK,
                "modpack-profile.json", true);
        parent.setModpack(true);
        parent.setName(profile.name());
        parent.setDescription(modpackDescription(children));
        parent.setVersion(profile.version());
        parent.setProviderName(profile.provider());
        parent.setProjectId(profile.projectId());
        parent.setVersionId(profile.versionId());
        parent.setChildren(children);
        List<ResourceContainerItem> nested = new ArrayList<>();
        nested.add(parent);
        nested.addAll(independent);
        return List.copyOf(nested);
    }

    private static String modpackDescription(List<ResourceContainerItem> children) {
        int mods = 0;
        int resourcePacks = 0;
        int shaders = 0;
        int other = 0;
        for (ResourceContainerItem child : children) {
            switch (child.getType()) {
                case MOD -> mods++;
                case RESOURCE_PACK -> resourcePacks++;
                case SHADER_PACK -> shaders++;
                default -> other++;
            }
        }
        return "Mods: " + mods + " · Resource Packs: " + resourcePacks + " · Shaders: " + shaders + " · Other: " + other;
    }

    private ResourceMarketplaceProvider.Card card(ResourceContainerItem resource) {
        return cards.get(key(resource));
    }

    private CapabilityDescriptor fileCapability(String action) {
        if (screenHost == null || server == null) return CapabilityDescriptor.unavailable(action, "Server File Capability Is Unavailable");
        var availability = screenHost.capabilities(server).availability(server, action);
        return availability.available() ? CapabilityDescriptor.supported(action)
                : CapabilityDescriptor.unavailable(action, availability.reason().isBlank()
                ? "Server File Capability Is Unavailable" : availability.reason());
    }

    private static String key(ResourceContainerItem resource) {
        return resource == null ? "" : resource.path();
    }

    private static String directory(ResourceContainerItem resource) {
        String path = key(resource).replace('\\', '/');
        int separator = path.lastIndexOf('/');
        return separator < 0 ? "/" : path.substring(0, separator);
    }

    private static <T> Async<T> failed(String message) {
        return Async.failed(new UnsupportedOperationException(message));
    }
}
