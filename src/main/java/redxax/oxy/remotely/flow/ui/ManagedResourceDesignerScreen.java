package redxax.oxy.remotely.flow.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redxax.oxy.remotely.data.flow.DesignerSaveNotifications;
import redxax.oxy.remotely.data.flow.FlowManager;
import redxax.oxy.remotely.data.flow.ManagedResourceCatalog;
import redxax.oxy.remotely.data.flow.ManagedResourceEditorModel;
import redxax.oxy.remotely.data.flow.ReSyncResourceType;
import redxax.oxy.remotely.flow.ui.studio.StudioScreen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.util.Notification;

import java.util.List;
import java.util.stream.Collectors;

public class ManagedResourceDesignerScreen extends FocusedJsonResourceDesignerScreen {
    private static final String DOCUMENT_FIELD = "managedResourceDocument";

    private final ManagedResourceCatalog.Descriptor descriptor;
    private final ManagedResourceEditorModel model;

    public ManagedResourceDesignerScreen(StudioScreen owner, String type, String resourceId, JsonObject resource,
                                         String serverId, Object parent) {
        this(owner, type, resourceId, resource, serverId, parent, resource != null);
    }

    public ManagedResourceDesignerScreen(StudioScreen owner, String type, String resourceId, JsonObject resource,
                                         String serverId, Object parent, boolean contentAvailable) {
        super(owner, type, resourceId, resource, serverId, parent);
        FlowManager manager = FlowManager.getInstance();
        descriptor = manager != null ? manager.managedResourceDescriptor(serverId, type)
            : ManagedResourceCatalog.opaque(type, "The managed resource catalog is unavailable.");
        model = ManagedResourceEditorModel.open(serverId, descriptor, resourceId, resource, contentAvailable)
            .orElse(null);
    }

    public ManagedResourceCatalog.Descriptor managedResourceDescriptor() {
        return descriptor;
    }

    public ManagedResourceEditorModel managedResourceModel() {
        return model;
    }

    @Override
    protected List<String> editorFields() {
        return model != null && model.contentAvailable() ? List.of(DOCUMENT_FIELD) : List.of();
    }

    @Override
    protected boolean customCodeField(String field) {
        return DOCUMENT_FIELD.equals(field);
    }

    @Override
    protected int customCodeFieldHeight(String field) {
        return DOCUMENT_FIELD.equals(field) ? 260 : -1;
    }

    @Override
    protected String jsonPathText(String field) {
        return DOCUMENT_FIELD.equals(field) ? gson.toJson(resource) : super.jsonPathText(field);
    }

    @Override
    protected boolean handleSpecialJsonTextWrite(String field, String value) {
        if (!DOCUMENT_FIELD.equals(field)) {
            return false;
        }
        if (readOnly() || value == null || value.isBlank()) {
            return true;
        }
        try {
            JsonElement parsed = JsonParser.parseString(value);
            if (!parsed.isJsonObject()) {
                return true;
            }
            JsonObject replacement = parsed.getAsJsonObject();
            if (replacement.has("id") && !replacement.get("id").isJsonNull()
                && !id.equals(replacement.get("id").getAsString())) {
                return true;
            }
            resource.keySet().clear();
            replacement.entrySet().forEach(entry -> resource.add(entry.getKey(), entry.getValue().deepCopy()));
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    @Override
    protected String jsonResourceDescription(String field, String label) {
        if (DOCUMENT_FIELD.equals(field)) {
            return "Exact Resource Document\nFields and absent values are preserved as published by the server.";
        }
        return super.jsonResourceDescription(field, label);
    }

    @Override
    protected String resourceDisplayName() {
        return descriptor.displayName();
    }

    @Override
    protected String resourceSummary() {
        if (model == null) {
            return "Identity Unavailable";
        }
        if (model.opaque()) {
            return "Opaque · " + descriptor.unavailableReason();
        }
        if (readOnly()) {
            return "Read Only · " + readOnlyReason();
        }
        return "Managed Resource · " + availableOperations();
    }

    @Override
    protected void appendResourcePanelWidgets(List<AnimatedWidget> widgets, int rowWidth) {
        String availability = availableOperations();
        if (!availability.isBlank()) {
            widgets.add(studioPanelState.hint("Available: " + availability, rowWidth));
        }
        if (readOnly()) {
            widgets.add(studioPanelState.hint("Read Only: " + readOnlyReason(), rowWidth));
        }
    }

    @Override
    protected boolean resourcePanelSaveButtonVisible() {
        return !readOnly();
    }

    @Override
    public List<AnimatedWidget> headerButtons() {
        return readOnly() ? List.of() : super.headerButtons();
    }

    @Override
    protected void save() {
        if (readOnly()) {
            new Notification("Save", readOnlyReason(), Notification.Type.ERROR);
            return;
        }
        ReSyncResourceType resourceType = ReSyncResourceType.byTypeId(type);
        FlowManager manager = FlowManager.getInstance();
        if (resourceType == null || manager == null || serverId == null) {
            new Notification("Save", "Managed Resource Transport Unavailable", Notification.Type.ERROR);
            return;
        }
        DesignerSaveNotifications.SaveTicket ticket = DesignerSaveNotifications.startExact(serverId, resourceType,
            id, descriptor.displayName());
        ticket.whenFinished((saved, current) -> ScreenManager.getInstance().execute(() -> {
            if (!saved || !current) {
                resourceDraft.discard(ticket);
                return;
            }
            FlowManager.ResourceReadLease lease = manager.snapshotResource(serverId, type, id);
            if (lease == null || !resourceDraft.acknowledge(ticket, lease::materialize)) {
                resourceDraft.discard(ticket);
            }
        }));
        if (!resourceDraft.capture(type, id, ticket, snapshot -> manager.saveManagedJsonResource(serverId, type, id,
            snapshot.serialize(payload -> gson.fromJson(payload, JsonObject.class)), ticket))) {
            DesignerSaveNotifications.failExact(ticket, "Save Snapshot Rejected");
        }
    }

    private boolean readOnly() {
        return model == null || model.readOnly() || !exactTransportIdentity();
    }

    private String readOnlyReason() {
        if (model == null) {
            return "Exact Resource Identity Is Unavailable";
        }
        if (!model.contentAvailable()) {
            return "Resource Content Is Unavailable";
        }
        if (model.locator().isEmpty()) {
            return "Exact Resource Owner Is Unavailable";
        }
        if (descriptor.opaque() || !descriptor.available()) {
            return descriptor.unavailableReason();
        }
        if (!descriptor.supports(ManagedResourceCatalog.Operation.SAVE)
            && !descriptor.supports(ManagedResourceCatalog.Operation.UPDATE)) {
            String saveReason = descriptor.operation(ManagedResourceCatalog.Operation.SAVE).reason();
            return !saveReason.isBlank() ? saveReason
                : descriptor.operation(ManagedResourceCatalog.Operation.UPDATE).reason();
        }
        if (ReSyncResourceType.byTypeId(type) == null) {
            return "Managed Resource Transport Is Unavailable For This Type";
        }
        if (!exactTransportIdentity()) {
            return "Managed Resource Transport Requires An Exact Top-Level ID";
        }
        return "Resource Is Read Only";
    }

    private boolean exactTransportIdentity() {
        if (ReSyncResourceType.byTypeId(type) == null || !resource.has("id") || resource.get("id").isJsonNull()) {
            return false;
        }
        try {
            return id.equals(resource.get("id").getAsString());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String availableOperations() {
        return ManagedResourceCatalog.editorOperations().stream().filter(descriptor::supports)
            .map(operation -> title(operation.id())).collect(Collectors.joining(", "));
    }

    private String title(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
