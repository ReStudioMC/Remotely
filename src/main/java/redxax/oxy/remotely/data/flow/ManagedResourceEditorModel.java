package redxax.oxy.remotely.data.flow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ManagedResourceEditorModel {
    public enum Kind {
        OBJECT,
        ARRAY,
        STRING,
        NUMBER,
        BOOLEAN,
        NULL
    }

    public enum Route {
        GENERIC,
        SPECIALIZED,
        OPAQUE
    }

    public record Structure(String pointer, Kind kind, JsonElement value) {
        public Structure {
            pointer = Objects.requireNonNull(pointer, "Managed resource structure pointer is required");
            kind = Objects.requireNonNull(kind, "Managed resource structure kind is required");
            value = value == null ? null : value.deepCopy();
        }

        @Override
        public JsonElement value() {
            return value == null ? null : value.deepCopy();
        }
    }

    private final ServerId serverId;
    private final String resourceTypeId;
    private final String resourceId;
    private final Optional<ServerResourceLocator> locator;
    private final ManagedResourceCatalog.Descriptor descriptor;
    private final JsonObject content;
    private final boolean contentAvailable;
    private final List<Structure> structure;

    private ManagedResourceEditorModel(ServerId serverId, String resourceTypeId, String resourceId,
                                       Optional<ServerResourceLocator> locator,
                                       ManagedResourceCatalog.Descriptor descriptor, JsonObject content,
                                       boolean contentAvailable) {
        this.serverId = Objects.requireNonNull(serverId, "Managed resource server is required");
        this.resourceTypeId = Objects.requireNonNull(resourceTypeId, "Managed resource type is required");
        this.resourceId = Objects.requireNonNull(resourceId, "Managed resource ID is required");
        this.locator = Objects.requireNonNull(locator, "Managed resource locator state is required");
        this.descriptor = Objects.requireNonNull(descriptor, "Managed resource descriptor is required");
        if (!resourceTypeId.equals(descriptor.typeId())) {
            throw new IllegalArgumentException("Managed resource locator type must match its descriptor");
        }
        locator.ifPresent(value -> {
            if (!value.serverId().equals(serverId) || !value.resourceType().value().equals(resourceTypeId)
                || !value.id().equals(resourceId)) {
                throw new IllegalArgumentException("Managed resource locator must match its exact identity");
            }
        });
        this.content = content != null ? content.deepCopy() : new JsonObject();
        this.contentAvailable = contentAvailable;
        this.structure = contentAvailable ? inspect(this.content) : List.of();
    }

    public static Optional<ManagedResourceEditorModel> open(String serverId,
                                                            ManagedResourceCatalog.Descriptor descriptor,
                                                            String resourceId, JsonObject content,
                                                            boolean contentAvailable) {
        if (serverId == null || serverId.isBlank() || descriptor == null || resourceId == null
            || resourceId.isBlank() || resourceId.indexOf('/') >= 0) {
            return Optional.empty();
        }
        try {
            ServerId server = ServerId.parseCanonicalText(serverId);
            Optional<ServerResourceLocator> locator = Optional.empty();
            if (!descriptor.owner().isBlank()) {
                ContractRef<ResourceTypeId> type = ContractRef.of(OwnerId.of(descriptor.owner()),
                    ResourceTypeId.of(descriptor.typeId()));
                locator = Optional.of(new ServerResourceLocator(server, type, resourceId));
            }
            return Optional.of(new ManagedResourceEditorModel(server, descriptor.typeId(), resourceId, locator,
                descriptor, content, contentAvailable));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public ServerId serverId() {
        return serverId;
    }

    public String resourceTypeId() {
        return resourceTypeId;
    }

    public String resourceId() {
        return resourceId;
    }

    public Optional<ServerResourceLocator> locator() {
        return locator;
    }

    public ManagedResourceCatalog.Descriptor descriptor() {
        return descriptor;
    }

    public JsonObject content() {
        return content.deepCopy();
    }

    public boolean contentAvailable() {
        return contentAvailable;
    }

    public List<Structure> structure() {
        return structure;
    }

    public boolean opaque() {
        return descriptor.opaque() || !contentAvailable || locator.isEmpty();
    }

    public boolean readOnly() {
        return opaque() || !descriptor.available() || (!descriptor.supports(ManagedResourceCatalog.Operation.SAVE)
            && !descriptor.supports(ManagedResourceCatalog.Operation.UPDATE));
    }

    public Route route(Set<String> supportedPresentations) {
        if (opaque()) {
            return Route.OPAQUE;
        }
        return descriptor.preferredPresentation(supportedPresentations).isPresent()
            ? Route.SPECIALIZED : Route.GENERIC;
    }

    public Optional<String> presentation(Set<String> supportedPresentations) {
        return route(supportedPresentations) == Route.SPECIALIZED
            ? descriptor.preferredPresentation(supportedPresentations) : Optional.empty();
    }

    private static List<Structure> inspect(JsonElement root) {
        List<Structure> result = new ArrayList<>();
        inspect(root, "", result);
        return List.copyOf(result);
    }

    private static void inspect(JsonElement element, String pointer, List<Structure> result) {
        Kind kind = kind(element);
        result.add(new Structure(pointer, kind, element));
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                inspect(entry.getValue(), pointer + "/" + escape(entry.getKey()), result);
            }
            return;
        }
        JsonArray array = element.getAsJsonArray();
        for (int index = 0; index < array.size(); index++) {
            inspect(array.get(index), pointer + "/" + index, result);
        }
    }

    private static Kind kind(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Kind.NULL;
        }
        if (element.isJsonObject()) {
            return Kind.OBJECT;
        }
        if (element.isJsonArray()) {
            return Kind.ARRAY;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return Kind.BOOLEAN;
        }
        if (primitive.isNumber()) {
            return Kind.NUMBER;
        }
        return Kind.STRING;
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }
}
