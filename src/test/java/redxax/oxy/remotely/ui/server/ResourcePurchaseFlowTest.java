package redxax.oxy.remotely.ui.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import restudio.rebase.resource.ResourcePoolClient;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.resource.ResourcePricePreview;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePurchaseFlowTest {
    @BeforeAll
    static void theme() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
    }

    @Test
    void startsWithCatalogResourcesAndShowsTotalAndRenewalWithOnlyRamVisible() throws Exception {
        Fixture fixture = new Fixture(null, "50", "3", "8192", "3", "400");
        assertTrue(row(fixture.flow, "resource-0").visible);
        assertFalse(row(fixture.flow, "resource-1").visible);
        assertFalse(row(fixture.flow, "resource-2").visible);
        assertFalse(row(fixture.flow, "resource-3").visible);
        assertEquals("$22.40 / 30 Days", card(fixture.flow, "summary").name);
        assertTrue(card(fixture.flow, "summary").description.contains("8 GB RAM"));
        assertTrue(card(fixture.flow, "summary").description.contains("4 Shared CPU"));
        assertTrue(card(fixture.flow, "summary").description.contains("64 GB Storage"));
        assertTrue(row(fixture.flow, "renewal").visible);
        assertEquals("Renews At $22.40 Every 30 Days", card(fixture.flow, "renewal").name);
        enter(fixture.flow, 0, "12");
        assertEquals("6", slider(fixture.flow, 1).label);
        assertEquals("64", slider(fixture.flow, 2).label);
        assertEquals("$30.40 / 30 Days", card(fixture.flow, "summary").name);
    }

    @Test
    void showsConfiguredStorageSurchargeEvenWithAdvancedClosed() throws Exception {
        Fixture fixture = new Fixture(null, "50", "3", "8192", "3", "400");
        enter(fixture.flow, 0, "4");
        assertEquals("2", slider(fixture.flow, 1).label);
        assertTrue(row(fixture.flow, "disk-terms").visible);
        assertTrue(card(fixture.flow, "disk-terms").description.contains("3x Unit Rate"));
        assertTrue(card(fixture.flow, "disk-terms").description.contains("32 Extra GB Storage In This Price"));
        assertEquals("$20.80 / 30 Days", card(fixture.flow, "summary").name);
    }

    @Test
    void retainsIndependentValuesAcrossAdvancedAndRamEditsUntilCpuFollowingIsChosen() throws Exception {
        Fixture fixture = new Fixture(null, "50", "3", "8192", "3", "400");
        click(fixture.flow, "advanced", 0);
        enter(fixture.flow, 1, "6");
        enter(fixture.flow, 2, "96");
        assertTrue(card(fixture.flow, "cpu-terms").description.contains("2 Extra CPU Equivalents In This Price"));
        click(fixture.flow, "advanced", 0);
        enter(fixture.flow, 0, "16");
        assertEquals("6", slider(fixture.flow, 1).label);
        assertEquals("96", slider(fixture.flow, 2).label);
        assertTrue(card(fixture.flow, "resource-0").description.contains("Custom CPU"));
        click(fixture.flow, "advanced", 0);
        click(fixture.flow, "cpu-follow", 0);
        assertEquals("8", slider(fixture.flow, 1).label);
        assertEquals("96", slider(fixture.flow, 2).label);
        enter(fixture.flow, 0, "12");
        assertEquals("6", slider(fixture.flow, 1).label);
    }

    @Test
    void usesCatalogAllowanceAndMultipliersWithoutAssumingCurrentBusinessValues() throws Exception {
        Fixture fixture = new Fixture(null, "75", "2.5", "4096", "4", "600");
        enter(fixture.flow, 0, "3");
        assertEquals("2.25", slider(fixture.flow, 1).label);
        assertTrue(card(fixture.flow, "disk-terms").description.contains("4x Unit Rate"));
        click(fixture.flow, "advanced", 0);
        enter(fixture.flow, 1, "4");
        assertTrue(card(fixture.flow, "cpu-terms").description.contains("2.5x Unit Rate"));
        assertTrue(card(fixture.flow, "cpu-terms").description.contains("1.75 Extra CPU Equivalents"));
        click(fixture.flow, "cpu-follow", 0);
        enter(fixture.flow, 0, "32");
        assertEquals("16", slider(fixture.flow, 1).label);
    }

    @Test
    void preservesCustomCatalogCpuUntilExplicitlyFollowingRam() throws Exception {
        Fixture fixture = new Fixture(null, "50", "3", "8192", "3", "600");
        assertEquals("6", slider(fixture.flow, 1).label);
        enter(fixture.flow, 0, "12");
        assertEquals("6", slider(fixture.flow, 1).label);
        enter(fixture.flow, 0, "16");
        assertEquals("6", slider(fixture.flow, 1).label);
    }

    @Test
    void preservesEveryExistingCustomCapacityDuringExpansion() throws Exception {
        Fixture fixture = new Fixture(new ResourcePoolModels.Resources("16384", "1000", "98304", "8192"), "50", "3", "8192", "3", "400");
        assertTrue(row(fixture.flow, "resource-1").visible);
        assertEquals("16", slider(fixture.flow, 0).label);
        assertEquals("10", slider(fixture.flow, 1).label);
        assertEquals("96", slider(fixture.flow, 2).label);
        assertEquals("8", slider(fixture.flow, 3).label);
        click(fixture.flow, "advanced", 0);
        enter(fixture.flow, 0, "24");
        assertEquals("10", slider(fixture.flow, 1).label);
        assertEquals("96", slider(fixture.flow, 2).label);
        assertEquals("8", slider(fixture.flow, 3).label);
        assertTrue(card(fixture.flow, "renewal").description.contains("Before Unused Time Credit"));
    }

    @Test
    void selectingLargerExpansionConfigurationKeepsExistingCustomResources() throws Exception {
        Fixture fixture = new Fixture(new ResourcePoolModels.Resources("16384", "1000", "98304", "8192"), "50", "3", "8192", "3", "400");
        ResourcePoolModels.Offer current = fixture.controller.snapshot().offers().getFirst();
        ResourcePoolModels.Offer larger = new ResourcePoolModels.Offer("larger", "Larger Catalog Configuration", current.planId(), current.planName(), current.location(), current.locationLabel(),
                current.cpuClass(), current.cpuClassLabel(), "24576", "1200", "65536", "0", "4000", "USD", "Every 30 Days");
        UUID poolId = fixture.controller.snapshot().pools().getFirst().pool().id();
        ResourcePurchaseFlow flow = new ResourcePurchaseFlow(fixture.controller, List.of(current, larger), poolId, 800, 600, Runnable::run, () -> false,
                (intent, selected, quote) -> { throw new AssertionError("Checkout Must Not Run"); });
        @SuppressWarnings("unchecked")
        DropDownWidget<ResourcePoolModels.Offer> selector = (DropDownWidget<ResourcePoolModels.Offer>) row(flow, "offer").getWidgets().getFirst();
        selector.setSelectedItem(larger);
        assertEquals("24", slider(flow, 0).label);
        assertEquals("12", slider(flow, 1).label);
        assertEquals("96", slider(flow, 2).label);
        assertEquals("8", slider(flow, 3).label);
    }

    @Test
    void keepsValidationAndReviewBackCancelWithoutSubmittingCheckout() throws Exception {
        Fixture fixture = new Fixture(null, "50", "3", "8192", "3", "400");
        click(fixture.flow, "advanced", 0);
        enter(fixture.flow, 1, "2.12");
        assertFalse(button(fixture.flow, "form-actions", 0).active);
        enter(fixture.flow, 1, "6");
        click(fixture.flow, "form-actions", 0);
        assertEquals(1, fixture.requests.size());
        assertEquals("600", fixture.requests.getFirst().resources().cpuQuotaPercent());
        assertEquals("Review Purchase", fixture.flow.popup().getTitle(), button(fixture.flow, "status", 0).getMessage());
        click(fixture.flow, "review-actions", 0);
        assertEquals("6", slider(fixture.flow, 1).label);
        assertTrue(row(fixture.flow, "resource-1").visible);
        enter(fixture.flow, 0, "12");
        click(fixture.flow, "form-actions", 0);
        assertEquals(2, fixture.requests.size());
        assertFalse(fixture.requests.getFirst().requestId().equals(fixture.requests.getLast().requestId()));
        click(fixture.flow, "review-actions", 0);
        click(fixture.flow, "form-actions", 1);
        click(fixture.flow, "form-actions", 0);
        assertEquals(2, fixture.requests.size());
    }

    @Test
    void cancelRejectsLateQuoteAndTermChangesPreserveManualCpu() throws Exception {
        Fixture fixture = new Fixture(null, "50", "3", "8192", "3", "400");
        click(fixture.flow, "advanced", 0);
        enter(fixture.flow, 1, "6");
        fixture.updateRate("75");
        fixture.flow.tick();
        assertEquals("6", slider(fixture.flow, 1).label);
        fixture.delayQuote = true;
        click(fixture.flow, "form-actions", 0);
        assertTrue(button(fixture.flow, "form-actions", 1).active);
        click(fixture.flow, "form-actions", 1);
        fixture.pending.complete(new GsonBuilder().serializeNulls().create().toJson(fixture.lastQuote));
        assertEquals("Buy Resources", fixture.flow.popup().getTitle());
        assertEquals(1, fixture.requests.size());
    }

    private static PopupWidget.PopupRow row(ResourcePurchaseFlow flow, String id) {
        return flow.popup().getRows().stream().filter(value -> value.id.equals(id)).findFirst().orElseThrow();
    }

    private static MountableButtonWidget card(ResourcePurchaseFlow flow, String id) {
        return (MountableButtonWidget) row(flow, id).getWidgets().getFirst();
    }

    private static DoubleSliderWidget slider(ResourcePurchaseFlow flow, int index) {
        return (DoubleSliderWidget) card(flow, "resource-" + index).mountedWidgets.getFirst();
    }

    private static AnimatedButton button(ResourcePurchaseFlow flow, String row, int index) {
        return (AnimatedButton) row(flow, row).getWidgets().get(index);
    }

    private static void click(ResourcePurchaseFlow flow, String row, int index) {
        button(flow, row, index).onClick(0, 0, 0);
    }

    private static void enter(ResourcePurchaseFlow flow, int index, String value) throws Exception {
        Field handler = DoubleSliderWidget.class.getDeclaredField("textCommitHandler");
        handler.setAccessible(true);
        @SuppressWarnings("unchecked")
        Consumer<String> commit = (Consumer<String>) handler.get(slider(flow, index));
        commit.accept(value);
    }

    private static void field(ResourcePoolController controller, String name, Object value) throws Exception {
        Field field = ResourcePoolController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static final class Fixture {
        private final ResourcePoolController controller;
        private final ResourcePurchaseFlow flow;
        private final List<ResourcePoolModels.QuoteRequest> requests = new ArrayList<>();
        private ResourcePoolModels.Rate rate;
        private boolean delayQuote;
        private Async<String> pending;
        private ResourcePoolModels.Quote lastQuote;

        private Fixture(ResourcePoolModels.Resources existing, String cpuAllowance, String cpuMultiplier, String diskAllowance, String diskMultiplier, String cpu) throws Exception {
            ResourcePoolModels.Domain domain = new ResourcePoolModels.Domain("eu", "shared");
            UUID planId = UUID.randomUUID();
            UUID poolId = existing == null ? null : UUID.randomUUID();
            ResourcePoolModels.Offer offer = new ResourcePoolModels.Offer("catalog", "Catalog Configuration", planId.toString(), "Catalog", "eu", "Europe", "shared", "Shared", "8192", cpu, "65536", "0", "2240", "USD", "Every 30 Days");
            rate = new ResourcePoolModels.Rate(domain, "USD", 30, "100", "200", "10", "5",
                    new ResourcePoolModels.Resources("1024", "100", "1024", "0"), new ResourcePoolModels.Resources("32768", "1600", "131072", "65536"),
                    new ResourcePoolModels.Resources("1024", "25", "1024", "1024"), "1", List.of(), cpuAllowance, cpuMultiplier, diskAllowance, diskMultiplier);
            ResourcePoolClient client = new ResourcePoolClient((method, path, body) -> {
                assertEquals("POST", method);
                assertEquals("/billing/resource-pools/quotes", path);
                ResourcePoolModels.QuoteRequest request = new Gson().fromJson(body, ResourcePoolModels.QuoteRequest.class);
                requests.add(request);
                ResourcePricePreview.Price preview = new ResourcePricePreview(rate).price(request.resources(), Instant.now());
                ResourcePoolModels.Price price = new ResourcePoolModels.Price("USD", 30, request.resources(), preview.subtotalCents(), "0", "0", "0", preview.subtotalCents(), preview.discountCents(), preview.firstPeriodCents(), preview.renewalCents(), "");
                lastQuote = new ResourcePoolModels.Quote(request.requestId(), offer.id(), planId, "product", domain, rate.pricingRevision(), price, Instant.now().toString(), Instant.now().plusSeconds(900).toString(), poolId, "0", preview.firstPeriodCents());
                if (delayQuote) {
                    pending = Async.pending();
                    return pending;
                }
                return Async.completed(new GsonBuilder().serializeNulls().create().toJson(lastQuote));
            });
            controller = new ResourcePoolController(client, new Scheduler(), () -> "account");
            List<ResourcePoolController.PoolView> pools = existing == null ? List.of() : List.of(new ResourcePoolController.PoolView(
                    new ResourcePoolModels.Pool(poolId, domain, new ResourcePoolModels.Balance(existing, new ResourcePoolModels.Resources("0", "0", "0", "0"), existing, new ResourcePoolModels.Resources("0", "0", "0", "0"))), List.of(), List.of(), Map.of()));
            field(controller, "snapshot", new ResourcePoolController.Snapshot("account", pools, List.of(offer), List.of(), false, "", 0));
            field(controller, "rates", List.of(rate));
            field(controller, "availability", List.of(new ResourcePoolModels.Availability(domain, poolId, rate.maximum(), Instant.now().plusSeconds(600))));
            flow = new ResourcePurchaseFlow(controller, List.of(offer), poolId, 800, 600, Runnable::run, () -> false,
                    (intent, selected, quote) -> { throw new AssertionError("Checkout Must Not Run"); });
        }

        private void updateRate(String cpuAllowance) throws Exception {
            rate = new ResourcePoolModels.Rate(rate.domain(), rate.currency(), rate.periodDays(), rate.ramGiBCents(), rate.cpuThreadCents(), rate.diskGiBCents(), rate.backupGiBCents(),
                    rate.minimum(), rate.maximum(), rate.step(), "2", rate.automaticDiscounts(), cpuAllowance, rate.excessCpuMultiplier(), rate.diskMiBPerGiB(), rate.excessDiskMultiplier());
            field(controller, "rates", List.of(rate));
        }
    }

    private static final class Scheduler implements TaskScheduler {
        public void execute(Runnable task) { task.run(); }
        public ScheduledTask schedule(Runnable task, Duration delay) {
            return new ScheduledTask() {
                public boolean cancel() { return true; }
                public boolean isCancelled() { return false; }
            };
        }
        public ScheduledTask scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) { return schedule(task, initialDelay); }
    }
}
