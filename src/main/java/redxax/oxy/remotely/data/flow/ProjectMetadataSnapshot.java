package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.flow.data.ReSyncProjectMetadata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class ProjectMetadataSnapshot {
    record Folder(String path, String parentPath, String name, int sortOrder, boolean collapsed) {
    }

    record Resource(String type, String id, String displayName, String path, int sortOrder) {
        String key() {
            return ReSyncProjectMetadata.resourceKey(type, id);
        }
    }

    record Bundle(String marketplaceSlug, String listingSlug, String title, String versionId, String version,
                  String rootPath, String iconMediaId, boolean enabled, List<String> resourceKeys) {
        Bundle {
            resourceKeys = List.copyOf(resourceKeys);
        }

        String key() {
            return ReSyncProjectMetadata.bundleKey(marketplaceSlug, listingSlug);
        }
    }

    record Document(String type, String id, String displayName, boolean active) {
        String key() {
            return ReSyncProjectMetadata.resourceKey(type, id);
        }
    }

    static final class Editor {
        private final ProjectMetadataSnapshot base;
        private final Map<String, Folder> folders = new HashMap<>();
        private final Map<String, Resource> resources = new HashMap<>();
        private final Map<String, Bundle> bundles = new HashMap<>();
        private final Map<String, Document> documents = new HashMap<>();
        private final Set<String> removedFolders = new HashSet<>();
        private final Set<String> removedResources = new HashSet<>();
        private final Set<String> removedBundles = new HashSet<>();
        private final Set<String> removedDocuments = new HashSet<>();
        private final Map<String, Long> folderOrders = new HashMap<>();
        private final Map<String, Long> resourceOrders = new HashMap<>();
        private final Map<String, Long> bundleOrders = new HashMap<>();
        private final Map<String, Long> documentOrders = new HashMap<>();
        private final Set<String> removedFolderOrders = new HashSet<>();
        private final Set<String> removedResourceOrders = new HashSet<>();
        private final Set<String> removedBundleOrders = new HashSet<>();
        private final Set<String> removedDocumentOrders = new HashSet<>();
        private String selectedResourceKey;
        private int nextFolderSortOrder;
        private int nextResourceSortOrder;
        private long nextFolderOrder;
        private long nextResourceOrder;
        private long nextBundleOrder;
        private long nextDocumentOrder;

        private Editor(ProjectMetadataSnapshot base) {
            this.base = base;
            selectedResourceKey = base.selectedResourceKey;
            nextFolderSortOrder = base.nextFolderSortOrder;
            nextResourceSortOrder = base.nextResourceSortOrder;
            nextFolderOrder = base.nextFolderOrder;
            nextResourceOrder = base.nextResourceOrder;
            nextBundleOrder = base.nextBundleOrder;
            nextDocumentOrder = base.nextDocumentOrder;
        }

        Resource resource(String type, String id) {
            return resourceByKey(ReSyncProjectMetadata.resourceKey(type, id));
        }

        Resource resourceByKey(String key) {
            if (removedResources.contains(key)) return null;
            Resource resource = resources.get(key);
            return resource != null ? resource : base.resources.get(key);
        }

        Folder folder(String path) {
            String normalized = ReSyncProjectMetadata.normalizePath(path);
            if (removedFolders.contains(normalized)) return null;
            Folder folder = folders.get(normalized);
            return folder != null ? folder : base.folders.get(normalized);
        }

        List<Resource> resources() {
            return materialize(base.resources, resources, removedResources, base.resourceOrders, resourceOrders, removedResourceOrders);
        }

        List<Folder> folders() {
            return materialize(base.folders, folders, removedFolders, base.folderOrders, folderOrders, removedFolderOrders);
        }

        Bundle bundle(String key) {
            if (removedBundles.contains(key)) return null;
            Bundle bundle = bundles.get(key);
            return bundle != null ? bundle : base.bundles.get(key);
        }

        void put(Bundle bundle) {
            String key = bundle.key();
            assignOrder(key, base.bundleOrders, bundleOrders, removedBundleOrders, () -> nextBundleOrder++);
            removedBundles.remove(key);
            bundles.put(key, bundle);
        }

        void removeBundle(String key) {
            bundles.remove(key);
            removedBundles.add(key);
            bundleOrders.remove(key);
            removedBundleOrders.add(key);
        }

        void put(Resource resource) {
            String key = resource.key();
            assignOrder(key, base.resourceOrders, resourceOrders, removedResourceOrders, () -> nextResourceOrder++);
            removedResources.remove(key);
            resources.put(key, resource);
        }

        void put(Folder folder) {
            assignOrder(folder.path(), base.folderOrders, folderOrders, removedFolderOrders, () -> nextFolderOrder++);
            removedFolders.remove(folder.path());
            folders.put(folder.path(), folder);
        }

        void removeResource(String key) {
            resources.remove(key);
            removedResources.add(key);
            resourceOrders.remove(key);
            removedResourceOrders.add(key);
        }

        void renameResource(String oldKey, Resource resource) {
            Long order = resourceOrders.containsKey(oldKey) ? resourceOrders.get(oldKey) : base.resourceOrders.get(oldKey);
            removeResource(oldKey);
            removeResource(resource.key());
            put(resource);
            if (order != null) resourceOrders.put(resource.key(), order);
        }

        void removeFolder(String path) {
            String normalized = ReSyncProjectMetadata.normalizePath(path);
            folders.remove(normalized);
            removedFolders.add(normalized);
            folderOrders.remove(normalized);
            removedFolderOrders.add(normalized);
        }

        void put(Document document) {
            String key = document.key();
            assignOrder(key, base.documentOrders, documentOrders, removedDocumentOrders, () -> nextDocumentOrder++);
            removedDocuments.remove(key);
            documents.put(key, document);
        }

        Document document(String key) {
            if (removedDocuments.contains(key)) return null;
            Document document = documents.get(key);
            return document != null ? document : base.documents.get(key);
        }

        void removeDocument(String key) {
            documents.remove(key);
            removedDocuments.add(key);
            documentOrders.remove(key);
            removedDocumentOrders.add(key);
        }

        void renameDocument(String oldKey, Document document) {
            Long order = documentOrders.containsKey(oldKey) ? documentOrders.get(oldKey) : base.documentOrders.get(oldKey);
            removeDocument(oldKey);
            removeDocument(document.key());
            put(document);
            if (order != null) documentOrders.put(document.key(), order);
        }

        List<Document> documents() {
            return materialize(base.documents, documents, removedDocuments, base.documentOrders, documentOrders, removedDocumentOrders);
        }

        String selectedResourceKey() {
            return selectedResourceKey;
        }

        void selectedResourceKey(String selectedResourceKey) {
            this.selectedResourceKey = selectedResourceKey != null ? selectedResourceKey : "";
        }

        int nextFolderSortOrder() {
            return nextFolderSortOrder++;
        }

        int nextResourceSortOrder() {
            return nextResourceSortOrder++;
        }

        ProjectMetadataSnapshot freeze() {
            return new ProjectMetadataSnapshot(base.serverId,
                base.folders.overlay(folders, removedFolders),
                base.resources.overlay(resources, removedResources),
                base.bundles.overlay(bundles, removedBundles),
                base.documents.overlay(documents, removedDocuments),
                base.folderOrders.overlay(folderOrders, removedFolderOrders),
                base.resourceOrders.overlay(resourceOrders, removedResourceOrders),
                base.bundleOrders.overlay(bundleOrders, removedBundleOrders),
                base.documentOrders.overlay(documentOrders, removedDocumentOrders),
                selectedResourceKey, nextFolderSortOrder, nextResourceSortOrder,
                nextFolderOrder, nextResourceOrder, nextBundleOrder, nextDocumentOrder);
        }

        private void assignOrder(String key, OverlayMap<String, Long> baseOrders, Map<String, Long> changedOrders,
                                 Set<String> removedOrders, LongSupplier nextOrder) {
            if (!changedOrders.containsKey(key) && baseOrders.get(key) == null) changedOrders.put(key, nextOrder.getAsLong());
            removedOrders.remove(key);
        }
    }

    private static final class OverlayMap<K extends Comparable<K>, V> {
        private record Node<K, V>(K key, V value, Node<K, V> left, Node<K, V> right, int height) {
        }

        private final Node<K, V> root;

        private OverlayMap(OverlayMap<K, V> parent, Map<K, V> values, Set<K> removed) {
            Node<K, V> next = parent != null ? parent.root : null;
            for (K key : removed) next = remove(next, key);
            for (Map.Entry<K, V> entry : values.entrySet()) next = put(next, entry.getKey(), entry.getValue());
            root = next;
        }

        private OverlayMap(Node<K, V> root) {
            this.root = root;
        }

        private V get(K key) {
            Node<K, V> current = root;
            while (current != null) {
                int compared = key.compareTo(current.key());
                if (compared == 0) return current.value();
                current = compared < 0 ? current.left() : current.right();
            }
            return null;
        }

        private OverlayMap<K, V> overlay(Map<K, V> changed, Set<K> removed) {
            if (changed.isEmpty() && removed.isEmpty()) return this;
            Node<K, V> next = root;
            for (K key : removed) next = remove(next, key);
            for (Map.Entry<K, V> entry : changed.entrySet()) next = put(next, entry.getKey(), entry.getValue());
            return new OverlayMap<>(next);
        }

        private Map<K, V> materialize() {
            LinkedHashMap<K, V> result = new LinkedHashMap<>();
            materialize(root, result);
            return result;
        }

        private void materialize(Node<K, V> node, Map<K, V> result) {
            if (node == null) return;
            materialize(node.left(), result);
            result.put(node.key(), node.value());
            materialize(node.right(), result);
        }

        private Node<K, V> put(Node<K, V> node, K key, V value) {
            if (node == null) return node(key, value, null, null);
            int compared = key.compareTo(node.key());
            if (compared == 0) return node(key, value, node.left(), node.right());
            return compared < 0
                ? balance(node(node.key(), node.value(), put(node.left(), key, value), node.right()))
                : balance(node(node.key(), node.value(), node.left(), put(node.right(), key, value)));
        }

        private Node<K, V> remove(Node<K, V> node, K key) {
            if (node == null) return null;
            int compared = key.compareTo(node.key());
            if (compared < 0) return balance(node(node.key(), node.value(), remove(node.left(), key), node.right()));
            if (compared > 0) return balance(node(node.key(), node.value(), node.left(), remove(node.right(), key)));
            if (node.left() == null) return node.right();
            if (node.right() == null) return node.left();
            Node<K, V> successor = minimum(node.right());
            return balance(node(successor.key(), successor.value(), node.left(), remove(node.right(), successor.key())));
        }

        private Node<K, V> minimum(Node<K, V> node) {
            Node<K, V> current = node;
            while (current.left() != null) current = current.left();
            return current;
        }

        private Node<K, V> balance(Node<K, V> node) {
            int balance = height(node.left()) - height(node.right());
            if (balance > 1) {
                if (height(node.left().left()) < height(node.left().right())) {
                    Node<K, V> left = rotateLeft(node.left());
                    node = node(node.key(), node.value(), left, node.right());
                }
                return rotateRight(node);
            }
            if (balance < -1) {
                if (height(node.right().right()) < height(node.right().left())) {
                    Node<K, V> right = rotateRight(node.right());
                    node = node(node.key(), node.value(), node.left(), right);
                }
                return rotateLeft(node);
            }
            return node;
        }

        private Node<K, V> rotateRight(Node<K, V> node) {
            Node<K, V> left = node.left();
            Node<K, V> moved = node(node.key(), node.value(), left.right(), node.right());
            return node(left.key(), left.value(), left.left(), moved);
        }

        private Node<K, V> rotateLeft(Node<K, V> node) {
            Node<K, V> right = node.right();
            Node<K, V> moved = node(node.key(), node.value(), node.left(), right.left());
            return node(right.key(), right.value(), moved, right.right());
        }

        private Node<K, V> node(K key, V value, Node<K, V> left, Node<K, V> right) {
            return new Node<>(key, value, left, right, Math.max(height(left), height(right)) + 1);
        }

        private int height(Node<K, V> node) {
            return node != null ? node.height() : 0;
        }
    }

    private final String serverId;
    private final OverlayMap<String, Folder> folders;
    private final OverlayMap<String, Resource> resources;
    private final OverlayMap<String, Bundle> bundles;
    private final OverlayMap<String, Document> documents;
    private final OverlayMap<String, Long> folderOrders;
    private final OverlayMap<String, Long> resourceOrders;
    private final OverlayMap<String, Long> bundleOrders;
    private final OverlayMap<String, Long> documentOrders;
    private final String selectedResourceKey;
    private final int nextFolderSortOrder;
    private final int nextResourceSortOrder;
    private final long nextFolderOrder;
    private final long nextResourceOrder;
    private final long nextBundleOrder;
    private final long nextDocumentOrder;
    private volatile long browserPresentationStamp;

    private ProjectMetadataSnapshot(String serverId, OverlayMap<String, Folder> folders,
                                    OverlayMap<String, Resource> resources, OverlayMap<String, Bundle> bundles,
                                    OverlayMap<String, Document> documents, OverlayMap<String, Long> folderOrders,
                                    OverlayMap<String, Long> resourceOrders, OverlayMap<String, Long> bundleOrders,
                                    OverlayMap<String, Long> documentOrders, String selectedResourceKey,
                                    int nextFolderSortOrder, int nextResourceSortOrder, long nextFolderOrder,
                                    long nextResourceOrder, long nextBundleOrder, long nextDocumentOrder) {
        this.serverId = serverId != null ? serverId : "";
        this.folders = folders;
        this.resources = resources;
        this.bundles = bundles;
        this.documents = documents;
        this.folderOrders = folderOrders;
        this.resourceOrders = resourceOrders;
        this.bundleOrders = bundleOrders;
        this.documentOrders = documentOrders;
        this.selectedResourceKey = selectedResourceKey != null ? selectedResourceKey : "";
        this.nextFolderSortOrder = nextFolderSortOrder;
        this.nextResourceSortOrder = nextResourceSortOrder;
        this.nextFolderOrder = nextFolderOrder;
        this.nextResourceOrder = nextResourceOrder;
        this.nextBundleOrder = nextBundleOrder;
        this.nextDocumentOrder = nextDocumentOrder;
    }

    static ProjectMetadataSnapshot from(ReSyncProjectMetadata metadata) {
        ReSyncProjectMetadata source = metadata != null ? metadata : new ReSyncProjectMetadata("");
        Map<String, Folder> folders = new LinkedHashMap<>();
        Map<String, Long> folderOrders = new LinkedHashMap<>();
        int nextFolderSortOrder = 0;
        for (ReSyncProjectMetadata.FolderEntry folder : source.getFolders()) {
            Folder value = new Folder(folder.getPath(), folder.getParentPath(), folder.getName(), folder.getSortOrder(), folder.isCollapsed());
            folders.put(value.path(), value);
            folderOrders.putIfAbsent(value.path(), (long) folderOrders.size());
            nextFolderSortOrder = Math.max(nextFolderSortOrder, value.sortOrder() + 1);
        }
        Map<String, Resource> resources = new LinkedHashMap<>();
        Map<String, Long> resourceOrders = new LinkedHashMap<>();
        int nextResourceSortOrder = 0;
        for (ReSyncProjectMetadata.ResourceEntry resource : source.getResources()) {
            Resource value = new Resource(resource.getType(), resource.getId(), resource.getDisplayName(), resource.getPath(), resource.getSortOrder());
            resources.put(value.key(), value);
            resourceOrders.putIfAbsent(value.key(), (long) resourceOrders.size());
            nextResourceSortOrder = Math.max(nextResourceSortOrder, value.sortOrder() + 1);
        }
        Map<String, Bundle> bundles = new LinkedHashMap<>();
        Map<String, Long> bundleOrders = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.InstalledBundleEntry bundle : source.getInstalledBundles()) {
            Bundle value = new Bundle(bundle.getMarketplaceSlug(), bundle.getListingSlug(), bundle.getTitle(), bundle.getVersionId(),
                bundle.getVersion(), bundle.getRootPath(), bundle.getIconMediaId(), bundle.isEnabled(), bundle.getResourceKeys());
            bundles.put(value.key(), value);
            bundleOrders.putIfAbsent(value.key(), (long) bundleOrders.size());
        }
        Map<String, Document> documents = new LinkedHashMap<>();
        Map<String, Long> documentOrders = new LinkedHashMap<>();
        for (ReSyncProjectMetadata.OpenDocumentEntry document : source.getOpenDocuments()) {
            Document value = new Document(document.getType(), document.getId(), document.getDisplayName(), document.isActive());
            documents.put(value.key(), value);
            documentOrders.putIfAbsent(value.key(), (long) documentOrders.size());
        }
        return new ProjectMetadataSnapshot(source.getServerId(), new OverlayMap<>(null, folders, Set.of()),
            new OverlayMap<>(null, resources, Set.of()), new OverlayMap<>(null, bundles, Set.of()),
            new OverlayMap<>(null, documents, Set.of()), new OverlayMap<>(null, folderOrders, Set.of()),
            new OverlayMap<>(null, resourceOrders, Set.of()), new OverlayMap<>(null, bundleOrders, Set.of()),
            new OverlayMap<>(null, documentOrders, Set.of()), source.getSelectedResourceKey(), nextFolderSortOrder,
            nextResourceSortOrder, folderOrders.size(), resourceOrders.size(), bundleOrders.size(), documentOrders.size());
    }

    static ProjectMetadataSnapshot rebase(ProjectMetadataSnapshot base, ProjectMetadataSnapshot local,
                                          ProjectMetadataSnapshot server) {
        if (local == null) return server;
        ProjectMetadataSnapshot previous = base != null ? base : from(new ReSyncProjectMetadata(server.serverId));
        Editor edit = server.edit();
        rebase(previous.folders.materialize(), local.ordered(local.folders, local.folderOrders), edit::put, edit::removeFolder);
        rebase(previous.resources.materialize(), local.ordered(local.resources, local.resourceOrders), edit::put, edit::removeResource);
        rebase(previous.bundles.materialize(), local.ordered(local.bundles, local.bundleOrders), edit::put, edit::removeBundle);
        rebase(previous.documents.materialize(), local.ordered(local.documents, local.documentOrders), edit::put, edit::removeDocument);
        if (!Objects.equals(previous.selectedResourceKey, local.selectedResourceKey)) edit.selectedResourceKey(local.selectedResourceKey);
        return edit.freeze();
    }

    String serverId() {
        return serverId;
    }

    Resource resource(String type, String id) {
        return resources.get(ReSyncProjectMetadata.resourceKey(type, id));
    }

    Resource resource(String key) {
        return resources.get(key);
    }

    Folder folder(String path) {
        return folders.get(ReSyncProjectMetadata.normalizePath(path));
    }

    List<Resource> resources() {
        return List.copyOf(ordered(resources, resourceOrders).values());
    }

    List<Folder> folders() {
        return List.copyOf(ordered(folders, folderOrders).values());
    }

    List<Bundle> bundles() {
        return List.copyOf(ordered(bundles, bundleOrders).values());
    }

    long browserPresentationStamp() {
        long stamp = browserPresentationStamp;
        if (stamp != 0L) {
            return stamp;
        }
        synchronized (this) {
            if (browserPresentationStamp != 0L) {
                return browserPresentationStamp;
            }
            long value = 0xcbf29ce484222325L;
            for (Folder folder : folders()) {
                value = presentationText(value, folder.path());
                value = presentationText(value, folder.parentPath());
                value = presentationText(value, folder.name());
                value = presentationPart(value, folder.sortOrder());
                value = presentationPart(value, folder.collapsed() ? 1 : 0);
            }
            value = presentationPart(value, -1);
            for (Resource resource : resources()) {
                value = presentationText(value, resource.type());
                value = presentationText(value, resource.id());
                value = presentationText(value, resource.displayName());
                value = presentationText(value, resource.path());
                value = presentationPart(value, resource.sortOrder());
            }
            value = presentationPart(value, -2);
            for (Bundle bundle : bundles()) {
                value = presentationText(value, bundle.marketplaceSlug());
                value = presentationText(value, bundle.listingSlug());
                value = presentationText(value, bundle.title());
                value = presentationText(value, bundle.versionId());
                value = presentationText(value, bundle.version());
                value = presentationText(value, bundle.rootPath());
                value = presentationText(value, bundle.iconMediaId());
                value = presentationPart(value, bundle.enabled() ? 1 : 0);
                value = presentationPart(value, bundle.resourceKeys().size());
                for (String key : bundle.resourceKeys()) {
                    value = presentationText(value, key);
                }
            }
            browserPresentationStamp = value == 0L ? 1L : value;
            return browserPresentationStamp;
        }
    }

    private static long presentationText(long stamp, String value) {
        if (value == null) {
            return presentationPart(stamp, -1);
        }
        long result = presentationPart(stamp, value.length());
        for (int index = 0; index < value.length(); index++) {
            result = presentationPart(result, value.charAt(index));
        }
        return result;
    }

    private static long presentationPart(long stamp, int value) {
        return (stamp ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
    }

    Bundle bundle(String marketplaceSlug, String listingSlug) {
        return bundles.get(ReSyncProjectMetadata.bundleKey(marketplaceSlug, listingSlug));
    }

    Editor edit() {
        return new Editor(this);
    }

    ReSyncProjectMetadata materialize() {
        ReSyncProjectMetadata metadata = new ReSyncProjectMetadata(serverId);
        metadata.setSelectedResourceKey(selectedResourceKey);
        List<ReSyncProjectMetadata.FolderEntry> folderEntries = new ArrayList<>();
        for (Folder folder : ordered(folders, folderOrders).values()) {
            ReSyncProjectMetadata.FolderEntry entry = new ReSyncProjectMetadata.FolderEntry();
            entry.setPath(folder.path());
            entry.setParentPath(folder.parentPath());
            entry.setName(folder.name());
            entry.setSortOrder(folder.sortOrder());
            entry.setCollapsed(folder.collapsed());
            folderEntries.add(entry);
        }
        metadata.setFolders(folderEntries);
        List<ReSyncProjectMetadata.ResourceEntry> resourceEntries = new ArrayList<>();
        for (Resource resource : ordered(resources, resourceOrders).values()) {
            ReSyncProjectMetadata.ResourceEntry entry = new ReSyncProjectMetadata.ResourceEntry();
            entry.setType(resource.type());
            entry.setId(resource.id());
            entry.setDisplayName(resource.displayName());
            entry.setPath(resource.path());
            entry.setSortOrder(resource.sortOrder());
            resourceEntries.add(entry);
        }
        metadata.setResources(resourceEntries);
        List<ReSyncProjectMetadata.InstalledBundleEntry> bundleEntries = new ArrayList<>();
        for (Bundle bundle : ordered(bundles, bundleOrders).values()) {
            ReSyncProjectMetadata.InstalledBundleEntry entry = new ReSyncProjectMetadata.InstalledBundleEntry();
            entry.setMarketplaceSlug(bundle.marketplaceSlug());
            entry.setListingSlug(bundle.listingSlug());
            entry.setTitle(bundle.title());
            entry.setVersionId(bundle.versionId());
            entry.setVersion(bundle.version());
            entry.setRootPath(bundle.rootPath());
            entry.setIconMediaId(bundle.iconMediaId());
            entry.setEnabled(bundle.enabled());
            entry.setResourceKeys(bundle.resourceKeys());
            bundleEntries.add(entry);
        }
        metadata.setInstalledBundles(bundleEntries);
        List<ReSyncProjectMetadata.OpenDocumentEntry> documentEntries = new ArrayList<>();
        for (Document document : ordered(documents, documentOrders).values()) {
            ReSyncProjectMetadata.OpenDocumentEntry entry = new ReSyncProjectMetadata.OpenDocumentEntry();
            entry.setType(document.type());
            entry.setId(document.id());
            entry.setDisplayName(document.displayName());
            entry.setActive(document.active());
            documentEntries.add(entry);
        }
        metadata.setOpenDocuments(documentEntries);
        return metadata;
    }

    private static <K extends Comparable<K>, V> List<V> materialize(OverlayMap<K, V> base, Map<K, V> changed, Set<K> removed,
                                                                    OverlayMap<K, Long> baseOrders, Map<K, Long> changedOrders,
                                                                    Set<K> removedOrders) {
        LinkedHashMap<K, V> values = new LinkedHashMap<>(base.materialize());
        removed.forEach(values::remove);
        values.putAll(changed);
        Map<K, Long> orders = new HashMap<>(baseOrders.materialize());
        removedOrders.forEach(orders::remove);
        orders.putAll(changedOrders);
        return values.entrySet().stream()
            .sorted((left, right) -> compareOrder(left.getKey(), right.getKey(), orders))
            .map(Map.Entry::getValue)
            .toList();
    }

    private <V> LinkedHashMap<String, V> ordered(OverlayMap<String, V> values, OverlayMap<String, Long> orders) {
        Map<String, Long> materializedOrders = orders.materialize();
        LinkedHashMap<String, V> ordered = new LinkedHashMap<>();
        values.materialize().entrySet().stream()
            .sorted((left, right) -> compareOrder(left.getKey(), right.getKey(), materializedOrders))
            .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    private static <K extends Comparable<K>> int compareOrder(K left, K right, Map<K, Long> orders) {
        int compared = Long.compare(orders.getOrDefault(left, Long.MAX_VALUE), orders.getOrDefault(right, Long.MAX_VALUE));
        return compared != 0 ? compared : left.compareTo(right);
    }

    private static <V> void rebase(Map<String, V> base, Map<String, V> local, Consumer<V> put, Consumer<String> remove) {
        for (String key : base.keySet()) {
            if (!local.containsKey(key)) remove.accept(key);
        }
        for (Map.Entry<String, V> entry : local.entrySet()) {
            if (!Objects.equals(base.get(entry.getKey()), entry.getValue())) put.accept(entry.getValue());
        }
    }
}
