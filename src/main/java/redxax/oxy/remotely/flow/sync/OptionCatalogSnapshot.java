package redxax.oxy.remotely.flow.sync;

import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogItem;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;

import java.util.ArrayList;
import java.util.List;

public class OptionCatalogSnapshot {
    public static final int CURRENT_VERSION = 3;
    private int version = CURRENT_VERSION;
    private String serverId = "";
    private String ownerId = "";
    private String resourceTypeId = "";
    private String sourceId = "";
    private String contextKey = "";
    private String revision = "";
    private long sequence;
    private List<String> values = new ArrayList<>();
    private List<OptionCatalogItem> items = new ArrayList<>();
    private String status = "available";
    private String diagnostic = "";

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getServerId() {
        return serverId != null ? serverId : "";
    }

    public void setServerId(String serverId) {
        this.serverId = serverId != null ? serverId : "";
    }

    public String getOwnerId() {
        return ownerId != null ? ownerId : "";
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId != null ? ownerId : "";
    }

    public String getResourceTypeId() {
        return resourceTypeId != null ? resourceTypeId : "";
    }

    public void setResourceTypeId(String resourceTypeId) {
        this.resourceTypeId = resourceTypeId != null ? resourceTypeId : "";
    }

    public void setResourceType(ServerId serverId, ContractRef<ResourceTypeId> resourceType) {
        this.serverId = serverId != null ? serverId.canonicalText() : "";
        this.ownerId = resourceType != null ? resourceType.owner().canonicalText() : "";
        this.resourceTypeId = resourceType != null ? resourceType.id().value() : "";
    }

    public boolean isResourceCatalog() {
        return !getServerId().isBlank() || !getOwnerId().isBlank() || !getResourceTypeId().isBlank();
    }

    public OptionCatalogCache.CatalogKey catalogKey() {
        if (!isResourceCatalog()) {
            throw new IllegalStateException("The option catalog does not declare a resource type");
        }
        if (getServerId().isBlank() || getOwnerId().isBlank() || getResourceTypeId().isBlank()) {
            throw new IllegalStateException("A resource catalog requires server, owner, and type identity");
        }
        OptionCatalogCache.CatalogKey key = new OptionCatalogCache.CatalogKey(ServerId.parseCanonicalText(getServerId()),
            ContractRef.of(OwnerId.of(getOwnerId()), ResourceTypeId.of(getResourceTypeId())), getSourceId(), getContextKey());
        if (!getValues().isEmpty()) {
            throw new IllegalStateException("A resource catalog cannot use string values");
        }
        for (OptionCatalogItem item : getItems()) {
            if (item == null || item.getResource() == null || !key.matches(item.getResource())) {
                throw new IllegalStateException("Every resource option must match the catalog server and type");
            }
        }
        return key;
    }

    public String getSourceId() {
        return sourceId != null ? sourceId : "";
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId != null ? sourceId : "";
    }

    public String getContextKey() {
        return contextKey != null ? contextKey : "";
    }

    public void setContextKey(String contextKey) {
        this.contextKey = contextKey != null ? contextKey : "";
    }

    public String getRevision() {
        return revision != null ? revision : "";
    }

    public void setRevision(String revision) {
        this.revision = revision != null ? revision : "";
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = Math.max(0L, sequence);
    }

    public List<String> getValues() {
        return values != null ? values : List.of();
    }

    public void setValues(List<String> values) {
        this.values = values != null ? new ArrayList<>(values) : new ArrayList<>();
    }

    public List<OptionCatalogItem> getItems() {
        return items != null ? items : List.of();
    }

    public void setItems(List<OptionCatalogItem> items) {
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
    }

    public String getStatus() {
        return status != null && !status.isBlank() ? status : "available";
    }

    public void setStatus(String status) {
        this.status = status != null && !status.isBlank() ? status : "available";
    }

    public String getDiagnostic() {
        return diagnostic != null ? diagnostic : "";
    }

    public void setDiagnostic(String diagnostic) {
        this.diagnostic = diagnostic != null ? diagnostic : "";
    }
}
