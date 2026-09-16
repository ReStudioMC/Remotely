package redxax.oxy.remotely.flow.ui;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.OptionCatalogCache;
import redxax.oxy.remotely.data.flow.OptionCatalogLoader;
import restudio.rescreen.ui.widgets.ItemSelectorWidget;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.InspectorFieldId;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.identity.ResourceTypeId;
import restudio.resync.flow.identity.ServerId;
import restudio.resync.flow.identity.ServerResourceLocator;
import restudio.resync.flow.protocol.OptionItem;
import restudio.resync.flow.type.TypeExpr;
import restudio.resync.flow.type.TypeReference;
import restudio.resync.flow.type.TypedValue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CoreOptionCatalogSelectorTest {
    @Test
    void selectionReturnsTheExactAdvertisedTypedValueAndDisablesUnavailableOptions() {
        ServerId server = ServerId.deterministic("core-option-selector");
        OwnerId owner = OwnerId.of("owner");
        ContractRef<ResourceTypeId> resourceType = ContractRef.of(owner, ResourceTypeId.of("resource"));
        TypeExpr.ResourceType type = new TypeExpr.ResourceType(
            new TypeReference(owner.canonicalText(), resourceType.id().value()));
        TypedValue availableValue = TypedValue.locator(type,
            new ServerResourceLocator(server, resourceType, "available"));
        TypedValue unavailableValue = TypedValue.locator(type,
            new ServerResourceLocator(server, resourceType, "unavailable"));
        ContractRef<CapabilityId> query = ContractRef.of(owner, CapabilityId.of("options"));
        ContractRef<InspectorFieldId> source = ContractRef.of(owner, InspectorFieldId.of("options"));
        OptionItem available = new OptionItem(availableValue, "Available", "Available resource", true, null);
        OptionItem unavailable = new OptionItem(unavailableValue, "Unavailable", "Unavailable resource", false,
            "Disabled");
        OptionCatalogCache.CompletedCoreCatalog completed = new OptionCatalogCache.CompletedCoreCatalog(source, query,
            1L, "options:fixture", List.of(available, unavailable), List.of());
        OptionCatalogLoader.CoreSnapshot catalog = new OptionCatalogLoader.CoreSnapshot(null, completed,
            completed.items(), false, "available", "");
        AtomicReference<TypedValue> selected = new AtomicReference<>();

        ItemSelectorWidget.AsyncItemSnapshot snapshot = OptionCatalogSelector.snapshot(catalog, () -> null,
            selected::set, "No Resources");
        snapshot.items().getFirst().action().run();

        assertSame(availableValue, selected.get());
        assertNull(snapshot.items().getLast().action());
    }
}
