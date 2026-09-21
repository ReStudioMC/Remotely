package redxax.oxy.remotely.flow.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public class ReSyncProjectMetadata {
    private String serverId;
    private List<FolderEntry> folders = new ArrayList<>();
    private List<ResourceEntry> resources = new ArrayList<>();
    private List<InstalledBundleEntry> installedBundles = new ArrayList<>();
    private List<OpenDocumentEntry> openDocuments = new ArrayList<>();
    private String selectedResourceKey = "";

    public ReSyncProjectMetadata() {
    }

    public ReSyncProjectMetadata(String serverId) {
        this.serverId = serverId;
    }

    public String getServerId() {
        return serverId;
    }

    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    public List<FolderEntry> getFolders() {
        if (folders == null) {
            folders = new ArrayList<>();
        }
        return folders;
    }

    public void setFolders(List<FolderEntry> folders) {
        this.folders = folders != null ? folders : new ArrayList<>();
    }

    public List<ResourceEntry> getResources() {
        if (resources == null) {
            resources = new ArrayList<>();
        }
        return resources;
    }

    public void setResources(List<ResourceEntry> resources) {
        this.resources = resources != null ? resources : new ArrayList<>();
    }

    public List<InstalledBundleEntry> getInstalledBundles() {
        if (installedBundles == null) {
            installedBundles = new ArrayList<>();
        }
        return installedBundles;
    }

    public void setInstalledBundles(List<InstalledBundleEntry> installedBundles) {
        this.installedBundles = installedBundles != null ? installedBundles : new ArrayList<>();
    }

    public List<OpenDocumentEntry> getOpenDocuments() {
        if (openDocuments == null) {
            openDocuments = new ArrayList<>();
        }
        return openDocuments;
    }

    public void setOpenDocuments(List<OpenDocumentEntry> openDocuments) {
        this.openDocuments = openDocuments != null ? openDocuments : new ArrayList<>();
    }

    public String getSelectedResourceKey() {
        return selectedResourceKey;
    }

    public void setSelectedResourceKey(String selectedResourceKey) {
        this.selectedResourceKey = selectedResourceKey != null ? selectedResourceKey : "";
    }

    public void ensureDefaultFolders() {
        ensureFolder("Blueprints", "", 0);
        ensureFolder("Blueprints/Flows", "Blueprints", 0);
        ensureFolder("Blueprints/Functions", "Blueprints", 1);
        ensureFolder("Blueprints/Commands", "Blueprints", 2);
        ensureFolder("Content", "", 1);
        ensureFolder("Content/Items", "Content", 0);
        ensureFolder("Content/Armor", "Content", 1);
        ensureFolder("Content/Blocks", "Content", 2);
        ensureFolder("Content/Recipes", "Content", 3);
        ensureFolder("Content/Advancements", "Content", 4);
        ensureFolder("Content/Dialogs", "Content", 5);
        ensureFolder("Content/Component Builders", "Content", 6);
        ensureFolder("GUIs", "", 2);
        ensureFolder("Customization", "", 3);
        ensureFolder("Customization/Chat", "Customization", 0);
        ensureFolder("Customization/MOTDs", "Customization", 1);
        ensureFolder("Customization/Messages", "Customization", 2);
        ensureFolder("Customization/Scoreboards", "Customization", 3);
        ensureFolder("Customization/Tabs", "Customization", 4);
        ensureFolder("Text", "", 4);
        ensureFolder("Text/Lists", "Text", 0);
        ensureFolder("Text/Maps", "Text", 1);
        ensureFolder("Text/Animations", "Text", 2);
        ensureFolder("Text/Templates", "Text", 3);
        ensureFolder("Worlds", "", 5);
        ensureFolder("WorldGen", "", 6);
        ensureFolder("Groups", "", 7);
    }

    public FolderEntry ensureFolder(String path, String parentPath, int sortOrder) {
        String normalizedPath = normalizePath(path);
        for (FolderEntry folder : getFolders()) {
            if (normalizedPath.equals(folder.getPath())) {
                return folder;
            }
        }
        FolderEntry folder = new FolderEntry();
        folder.setPath(normalizedPath);
        folder.setParentPath(normalizePath(parentPath));
        folder.setName(lastSegment(normalizedPath));
        folder.setSortOrder(sortOrder);
        getFolders().add(folder);
        sortFolders();
        return folder;
    }

    public ResourceEntry ensureResource(String type, String id, String displayName, String defaultFolder) {
        String key = resourceKey(type, id);
        for (ResourceEntry entry : getResources()) {
            if (key.equals(entry.key())) {
                if (displayName != null && !displayName.isBlank()) {
                    entry.setDisplayName(displayName);
                }
                if (entry.getPath() == null || entry.getPath().isBlank()) {
                    entry.setPath(normalizePath(defaultFolder));
                }
                return entry;
            }
        }
        ResourceEntry entry = new ResourceEntry();
        entry.setType(type);
        entry.setId(id);
        entry.setDisplayName(displayName == null || displayName.isBlank() ? id : displayName);
        entry.setPath(normalizePath(defaultFolder));
        entry.setSortOrder(getResources().size());
        getResources().add(entry);
        return entry;
    }

    public ResourceEntry findResource(String type, String id) {
        String key = resourceKey(type, id);
        for (ResourceEntry entry : getResources()) {
            if (key.equals(entry.key())) {
                return entry;
            }
        }
        return null;
    }

    public boolean deduplicateResources() {
        Set<String> keys = new HashSet<>();
        return getResources().removeIf(entry -> entry == null || !keys.add(entry.key()));
    }

    public InstalledBundleEntry findInstalledBundle(String marketplaceSlug, String listingSlug) {
        String key = bundleKey(marketplaceSlug, listingSlug);
        for (InstalledBundleEntry entry : getInstalledBundles()) {
            if (key.equals(entry.key())) {
                return entry;
            }
        }
        return null;
    }

    public FolderEntry findFolder(String path) {
        String normalizedPath = normalizePath(path);
        for (FolderEntry folder : getFolders()) {
            if (normalizedPath.equals(folder.getPath())) {
                return folder;
            }
        }
        return null;
    }

    public void moveResource(String type, String id, String folderPath) {
        ResourceEntry entry = findResource(type, id);
        if (entry != null) {
            entry.setPath(normalizePath(folderPath));
        }
    }

    public void moveFolder(String path, String parentPath) {
        FolderEntry folder = findFolder(path);
        if (folder != null) {
            folder.setParentPath(normalizePath(parentPath));
        }
    }

    public void setFolderCollapsed(String path, boolean collapsed) {
        FolderEntry folder = findFolder(path);
        if (folder != null) {
            folder.setCollapsed(collapsed);
        }
    }

    public boolean isFolderCollapsed(String path) {
        FolderEntry folder = findFolder(path);
        return folder != null && folder.isCollapsed();
    }

    public void addOpenDocument(String type, String id, String displayName) {
        String key = resourceKey(type, id);
        for (OpenDocumentEntry entry : getOpenDocuments()) {
            if (key.equals(entry.key())) {
                entry.setActive(true);
            } else {
                entry.setActive(false);
            }
        }
        if (getOpenDocuments().stream().noneMatch(entry -> key.equals(entry.key()))) {
            OpenDocumentEntry entry = new OpenDocumentEntry();
            entry.setType(type);
            entry.setId(id);
            entry.setDisplayName(displayName == null || displayName.isBlank() ? id : displayName);
            entry.setActive(true);
            getOpenDocuments().add(entry);
        }
    }

    private void sortFolders() {
        getFolders().sort(Comparator.comparing(FolderEntry::getParentPath).thenComparingInt(FolderEntry::getSortOrder).thenComparing(FolderEntry::getName));
    }

    public static String resourceKey(String type, String id) {
        return safe(type).toUpperCase(Locale.ROOT) + ":" + safe(id);
    }

    public static String bundleKey(String marketplaceSlug, String listingSlug) {
        return safe(marketplaceSlug) + ":" + safe(listingSlug);
    }

    public static String normalizePath(String path) {
        String normalized = safe(path).replace('\\', '/').replaceAll("/+", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String lastSegment(String path) {
        String normalizedPath = normalizePath(path);
        int index = normalizedPath.lastIndexOf('/');
        return index >= 0 ? normalizedPath.substring(index + 1) : normalizedPath;
    }

    private static String safe(String text) {
        return text != null ? text : "";
    }

    public static class FolderEntry {
        private String path = "";
        private String parentPath = "";
        private String name = "";
        private int sortOrder;
        private boolean collapsed;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = normalizePath(path);
        }

        public String getParentPath() {
            return parentPath;
        }

        public void setParentPath(String parentPath) {
            this.parentPath = normalizePath(parentPath);
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name != null ? name : "";
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public boolean isCollapsed() {
            return collapsed;
        }

        public void setCollapsed(boolean collapsed) {
            this.collapsed = collapsed;
        }
    }

    public static class ResourceEntry {
        private String type = "";
        private String id = "";
        private String displayName = "";
        private String path = "";
        private int sortOrder;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type != null ? type : "";
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id != null ? id : "";
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName != null ? displayName : "";
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = normalizePath(path);
        }

        public int getSortOrder() {
            return sortOrder;
        }

        public void setSortOrder(int sortOrder) {
            this.sortOrder = sortOrder;
        }

        public String key() {
            return resourceKey(type, id);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof ResourceEntry that)) {
                return false;
            }
            return Objects.equals(key(), that.key());
        }

        @Override
        public int hashCode() {
            return Objects.hash(key());
        }
    }

    public static class InstalledBundleEntry {
        private String marketplaceSlug = "";
        private String listingSlug = "";
        private String title = "";
        private String versionId = "";
        private String version = "";
        private String rootPath = "";
        private String iconMediaId = "";
        private boolean enabled = true;
        private List<String> resourceKeys = new ArrayList<>();

        public String getMarketplaceSlug() {
            return marketplaceSlug;
        }

        public void setMarketplaceSlug(String marketplaceSlug) {
            this.marketplaceSlug = marketplaceSlug != null ? marketplaceSlug : "";
        }

        public String getListingSlug() {
            return listingSlug;
        }

        public void setListingSlug(String listingSlug) {
            this.listingSlug = listingSlug != null ? listingSlug : "";
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title != null ? title : "";
        }

        public String getVersionId() {
            return versionId;
        }

        public void setVersionId(String versionId) {
            this.versionId = versionId != null ? versionId : "";
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version != null ? version : "";
        }

        public String getRootPath() {
            return rootPath;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = normalizePath(rootPath);
        }

        public String getIconMediaId() {
            return iconMediaId;
        }

        public void setIconMediaId(String iconMediaId) {
            this.iconMediaId = iconMediaId != null ? iconMediaId : "";
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getResourceKeys() {
            if (resourceKeys == null) {
                resourceKeys = new ArrayList<>();
            }
            return resourceKeys;
        }

        public void setResourceKeys(List<String> resourceKeys) {
            this.resourceKeys = resourceKeys != null ? resourceKeys : new ArrayList<>();
        }

        public String key() {
            return bundleKey(marketplaceSlug, listingSlug);
        }
    }

    public static class OpenDocumentEntry {
        private String type = "";
        private String id = "";
        private String displayName = "";
        private boolean active;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type != null ? type : "";
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id != null ? id : "";
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName != null ? displayName : "";
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public String key() {
            return resourceKey(type, id);
        }
    }
}
