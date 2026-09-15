package redxax.oxy.remotely.ui.server;

import redxax.oxy.remotely.RemotelyClient;
import redxax.oxy.remotely.ui.widgets.ReactorPlanWidget;
import restudio.rebase.resource.ResourcePoolModels;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.theme.Accent;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.rescreen.ReScreen;
import restudio.rescreen.ui.rescreen.layout.ManagedLayout;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.IconButton;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ResourcePoolScreen extends ReScreen {
    private final Screen parent;
    private final RemotelyClient remotelyClient;
    private final ResourcePoolController controller;
    private Container content;
    private boolean handoff;
    private boolean closed;

    public ResourcePoolScreen(Screen parent, RemotelyClient remotelyClient) {
        this.parent = parent;
        this.remotelyClient = remotelyClient;
        TaskScheduler scheduler = remotelyClient != null && remotelyClient.getComposition() != null
                ? remotelyClient.getComposition().scheduler() : TaskScheduler.unavailable();
        controller = new ResourcePoolController(remotelyClient.getApiClient(), scheduler,
                () -> host().accountIdentity().subjectId());
    }

    private ServerScreenHost host() {
        return remotelyClient.getHost().serverScreenHost(remotelyClient);
    }

    ResourcePoolController controller() {
        return controller;
    }

    public String getDesktopAppId() {
        return "resource-pools";
    }

    public String getDesktopAppTitle() {
        return "Resources";
    }

    public String getDesktopAppIconPath() {
        return "resources.png";
    }

    @Override
    public void init() {
        super.init();
        handoff = false;
        closed = false;
        header().addLeft("close.png", this::close, "Back")
                .addRight("reload.png", controller::refresh, "Refresh")
                .build();
        content = createContainer("resource_pools", 6, 38, width - 12, Math.max(80, height - 44))
                .columns(1).padding(8).layout(new ManagedLayout()).scrolling(true).backgroundDrawing(false);
        setActiveContainer(content);
        controller.listen(snapshot -> host().application().execute(() -> {
            if (!closed) render(snapshot);
        }));
        controller.refresh();
    }

    private void render(ResourcePoolController.Snapshot snapshot) {
        if (content == null) {
            return;
        }
        content.clearWidgets();
        if (snapshot.loading() && snapshot.pools().isEmpty()) {
            content.addWidget(summary("Loading Resources", "Checking Pool Capacity And Servers", "calm"));
            return;
        }
        if (!snapshot.message().isBlank()) {
            content.addWidget(summary("Resources Need Attention", snapshot.message(), "danger"));
        }
        if (!snapshot.loading() && !snapshot.offers().isEmpty()) {
            content.addWidget(new IconButton.Builder().size(rowWidth(), 28).label("Add Resources")
                    .hint("Review Available Resource Offers").imagePath("Reactor.png")
                    .accentType(ThemeManager.getAccent("nice")).onClick(() -> showOffers(snapshot.offers())).build());
        }
        renderPurchases(snapshot);
        if (snapshot.pools().isEmpty()) {
            content.addWidget(summary("No Resource Pools", "Capacity Appears After Payment And Review", "warning"));
            renderPageAction("Resource Pools", "Load More Pools", snapshot.poolPages(), controller::loadMorePools);
            return;
        }
        for (ResourcePoolController.PoolView view : snapshot.pools()) {
            renderPool(view);
        }
        renderPageAction("Resource Pools", "Load More Pools", snapshot.poolPages(), controller::loadMorePools);
    }

    private void renderPurchases(ResourcePoolController.Snapshot snapshot) {
        if (snapshot.purchases().isEmpty()) {
            renderPageAction("Resource Purchases", "Load More Purchases", snapshot.purchasePages(),
                    controller::loadMorePurchases);
            return;
        }
        content.addWidget(summary("Resource Purchases", "Payment And Review Do Not Reserve Capacity", "calm"));
        for (ResourcePoolModels.PurchaseStatus purchase : snapshot.purchases()) {
            ResourcePoolModels.Offer offer = offer(snapshot.offers(), purchase.offerId());
            String label = offer == null ? "Resource Purchase" : offer.label();
            String hint = purchaseHint(purchase);
            String accent = switch (purchase.status()) {
                case ACTIVE -> "nice";
                case PENDING -> "warning";
                case NEEDS_REVIEW -> "danger";
                case PAYMENT_FAILED, CANCELLED, REFUNDED, EXPIRED, FAILED -> "danger";
            };
            IconButton.Builder row = new IconButton.Builder().size(rowWidth(), 28)
                    .label(label + " • " + title(purchase.status().name())).hint(hint).imagePath("Reactor.png")
                    .accentType(ThemeManager.getAccent(accent));
            if (purchase.checkoutUrl() != null && !purchase.checkoutUrl().isBlank()
                    && (purchase.status() == ResourcePoolModels.PurchaseState.PENDING
                    || purchase.status() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW)) {
                row.onClick(() -> showPurchase(purchase, offer));
            }
            content.addWidget(row.build());
        }
        renderPageAction("Resource Purchases", "Load More Purchases", snapshot.purchasePages(),
                controller::loadMorePurchases);
    }

    private static String purchaseHint(ResourcePoolModels.PurchaseStatus purchase) {
        return switch (purchase.status()) {
            case PENDING -> "Awaiting Payment Or Review • Capacity Is Not Yet Available";
            case NEEDS_REVIEW -> text(purchase.reviewReason(), "Purchase Needs Review • Capacity Is Not Yet Available");
            case ACTIVE -> "Resources Granted To The Pool";
            case PAYMENT_FAILED -> text(purchase.failureReason(), "Payment Failed • No Capacity Granted");
            case CANCELLED -> "Purchase Cancelled • No Capacity Granted";
            case REFUNDED -> "Purchase Refunded • Capacity May Be Removed";
            case EXPIRED -> "Checkout Expired • No Capacity Granted";
            case FAILED -> text(purchase.failureReason(), "Purchase Failed • No Capacity Granted");
        };
    }

    private void renderPool(ResourcePoolController.PoolView view) {
        ResourcePoolModels.Pool pool = view.pool();
        content.addWidget(summary("Resource Pool • " + pool.domain().location(), pool.domain().cpuClass(), "calm"));
        content.addWidget(balance("Entitled", pool.balance().entitled(), "nice"));
        content.addWidget(balance("Committed", pool.balance().committed(), "calm"));
        content.addWidget(balance("Available", pool.balance().available(), "nice"));
        if (!zero(pool.balance().deficit())) {
            content.addWidget(balance("Deficit", pool.balance().deficit(), "danger"));
        }
        content.addWidget(new IconButton.Builder().size(rowWidth(), 26).label("Create Server")
                .hint("Configure A Server Using This Pool").imagePath("newFile.png")
                .accentType(ThemeManager.getAccent("nice")).onClick(() -> create(pool.id())).build());
        if (view.drafts().isEmpty()) {
            content.addWidget(summary("No Server Drafts", "New Servers Appear Here During Activation", "calm"));
        } else {
            for (ResourcePoolModels.Draft draft : view.drafts()) {
                content.addWidget(draftRow(draft));
            }
        }
        renderPageAction("Server Drafts", "Load More Drafts", view.draftPages(),
                () -> controller.loadMoreDrafts(pool.id()));
        if (view.allocations().isEmpty()) {
            content.addWidget(summary("No Assigned Servers", "Activated Servers Appear Here", "calm"));
        } else {
            for (ResourcePoolModels.Allocation allocation : view.allocations()) {
                ResourcePoolModels.Progress progress = allocation.currentRequestId() == null
                        ? view.progress().values().stream().filter(value -> value.operation().serverId().equals(allocation.serverId()))
                        .findFirst().orElse(null) : view.progress().get(allocation.currentRequestId());
                content.addWidget(allocationRow(allocation, progress));
            }
        }
        renderPageAction("Pool Servers", "Load More Servers", view.allocationPages(),
                () -> controller.loadMoreAllocations(pool.id()));
    }

    private void renderPageAction(String name, String label, ResourcePoolController.PageState pages, Runnable action) {
        if (!pages.message().isBlank()) {
            content.addWidget(summary(name + " Need Attention", pages.message(), "danger"));
        }
        if (!pages.hasMore()) {
            return;
        }
        content.addWidget(new IconButton.Builder().size(rowWidth(), 24)
                .label(pages.loading() ? "Loading More" : label).hint(pages.loading() ? "Loading The Next Page" : label)
                .imagePath("down.png").accentType(ThemeManager.getAccent("calm")).active(!pages.loading())
                .onClick(action).build());
    }

    private AnimatedButton balance(String label, ResourcePoolModels.Resources resources, String accent) {
        return summary(label + " • " + resources.ramMiB() + " MiB RAM • " + resources.cpuQuotaPercent() + "% CPU",
                resources.diskMiB() + " MiB Disk • " + resources.backupMiB() + " MiB Backup", accent);
    }

    private IconButton draftRow(ResourcePoolModels.Draft draft) {
        String state = title(draft.state().name());
        String hint = draftHint(draft);
        String accent = switch (draft.state()) {
            case DRAFT -> "calm";
            case ACTIVATING -> "warning";
            case UNKNOWN -> "danger";
            case ACTIVE -> "nice";
        };
        IconButton.Builder builder = new IconButton.Builder().size(rowWidth(), 30)
                .label(draft.metadata().name() + " • " + state).hint(hint).imagePath("server.png")
                .accentType(ThemeManager.getAccent(accent));
        if (draft.state() == ResourcePoolModels.DraftState.DRAFT) {
            builder.onClick(() -> showDraftActivation(draft));
        }
        return builder.build();
    }

    private String draftHint(ResourcePoolModels.Draft draft) {
        if (draft.state() == ResourcePoolModels.DraftState.UNKNOWN) {
            return draft.reason() == null || draft.reason().isBlank() ? "Needs Review • Reservation Retained" : draft.reason();
        }
        if (draft.reserved() != null) {
            return draft.reserved().ramMiB() + " MiB RAM • " + draft.reserved().cpuQuotaPercent()
                    + "% CPU • " + (draft.state() == ResourcePoolModels.DraftState.ACTIVE ? "Active" : "Reservation Retained");
        }
        return "Ready To Review And Activate";
    }

    private IconButton allocationRow(ResourcePoolModels.Allocation allocation, ResourcePoolModels.Progress progress) {
        boolean pendingRestart = pendingRestart(allocation);
        String state = pendingRestart ? "Pending Restart" : title(allocation.state().name());
        String hint = "Desired " + compute(allocation.desired()) + " • Reserved " + compute(allocation.reserved())
                + " • Effective " + compute(allocation.effective());
        if (progress != null) {
            hint += " • " + progressLabel(progress);
        }
        String accent = allocation.state() == ResourcePoolModels.AllocationState.ACTIVE && !pendingRestart ? "nice"
                : allocation.state() == ResourcePoolModels.AllocationState.DISABLED ? "danger" : "warning";
        return new IconButton.Builder().size(rowWidth(), 32).label(allocation.serverId() + " • " + state)
                .hint(hint).imagePath("server.png").accentType(ThemeManager.getAccent(accent))
                .onClick(() -> showAllocation(allocation, progress)).build();
    }

    private void showOffers(List<ResourcePoolModels.Offer> offers) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Add Resources").width(430).virtualizeRows(true)
                .onClose(() -> popup[0].hide());
        for (ResourcePoolModels.Offer offer : offers) {
            ReactorPlanWidget card = new ReactorPlanWidget(0, 0, 380, 64, null);
            card.setContent(offer.label(), offer.locationLabel() + " • " + offer.cpuClassLabel(),
                    offerSpecs(offer), price(offer));
            card.setOnAction(() -> {
                popup[0].hide();
                showOfferReview(offer);
            });
            builder.addRow(new PopupWidget.PopupRow.Builder("", card).minHeight(64).build());
        }
        popup[0] = show(builder.build());
    }

    private void showOfferReview(ResourcePoolModels.Offer offer) {
        ResourcePoolController.PurchaseIntent intent;
        try {
            intent = controller.beginPurchase(offer);
        } catch (RuntimeException failure) {
            new Notification("Purchase Needs Attention", ResourcePoolController.message(failure), Notification.Type.ERROR);
            return;
        }
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Review Resource Purchase").width(400)
                .addTitleAction("Continue", () -> {
                    popup[0].hide();
                    submitCheckout(intent, offer);
                }, "Create Checkout", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> {
                    controller.discardPurchase(intent);
                    popup[0].hide();
                });
        builder.addRow(detail("Offer", offer.label()));
        builder.addRow(detail("Plan", offer.planName()));
        builder.addRow(detail("Domain", offer.locationLabel() + " • " + offer.cpuClassLabel()));
        builder.addRow(detail("Compute", offer.ramMiB() + " MiB RAM • " + offer.cpuQuotaPercent() + "% CPU"));
        builder.addRow(detail("Storage", offer.diskMiB() + " MiB Disk • " + offer.backupMiB() + " MiB Backup"));
        builder.addRow(detail("Price", price(offer)));
        builder.addRow(detail("Availability", "Capacity Is Granted Only After Payment And Review"));
        popup[0] = show(builder.build());
    }

    private void submitCheckout(ResourcePoolController.PurchaseIntent intent, ResourcePoolModels.Offer offer) {
        Notification notice = operationNotice("Creating Checkout", offer.label());
        controller.checkout(intent).whenComplete((purchase, failure) -> host().application().execute(() -> {
            if (!closed) {
                finishCheckout(notice, purchase, failure, offer);
            }
        }));
    }

    private void finishCheckout(Notification notice, ResourcePoolController.PurchaseResult purchase, Throwable failure,
                                ResourcePoolModels.Offer offer) {
        if (failure != null) {
            notice.update().message("Checkout Needs Attention").description(ResourcePoolController.message(failure))
                    .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            controller.refresh();
            return;
        }
        String message = switch (purchase.state()) {
            case ACTIVE -> "Resources Active";
            case PENDING -> "Checkout Ready";
            case NEEDS_REVIEW -> "Purchase Needs Review";
            case PAYMENT_FAILED -> "Payment Failed";
            case CANCELLED -> "Purchase Cancelled";
            case REFUNDED -> "Purchase Refunded";
            case EXPIRED -> "Checkout Expired";
            case FAILED -> "Purchase Failed";
        };
        String description = switch (purchase.state()) {
            case ACTIVE -> "Capacity Is Available In The Resource Pool";
            case PENDING -> "Complete Checkout And Review Before Capacity Is Available";
            case NEEDS_REVIEW -> text(purchase.reviewReason(), "Capacity Is Not Yet Available");
            case PAYMENT_FAILED, FAILED -> text(purchase.failureReason(), "No Capacity Was Granted");
            case CANCELLED, EXPIRED -> "No Capacity Was Granted";
            case REFUNDED -> "Capacity May Be Removed From The Resource Pool";
        };
        Notification.Type type = purchase.state() == ResourcePoolModels.PurchaseState.ACTIVE ? Notification.Type.SUCCESS
                : purchase.state() == ResourcePoolModels.PurchaseState.PENDING ? Notification.Type.INFO
                : purchase.state() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW ? Notification.Type.WARN
                : Notification.Type.ERROR;
        notice.update().message(message).description(description).type(type).loading(false).autoSlideOut(true).commit();
        if (purchase.checkoutUrl() != null && !purchase.checkoutUrl().isBlank()
                && (purchase.state() == ResourcePoolModels.PurchaseState.PENDING
                || purchase.state() == ResourcePoolModels.PurchaseState.NEEDS_REVIEW)) {
            showCheckoutLink(purchase.checkoutUrl(), offer, title(purchase.state().name()));
        }
        controller.refresh();
    }

    private void showPurchase(ResourcePoolModels.PurchaseStatus purchase, ResourcePoolModels.Offer offer) {
        String label = offer == null ? "Resource Purchase" : offer.label();
        showCheckoutLink(purchase.checkoutUrl(), offer, label + " • " + title(purchase.status().name()));
    }

    private void showCheckoutLink(String url, ResourcePoolModels.Offer offer, String state) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Resource Checkout").width(400)
                .addTitleAction("Open Checkout", () -> {
                    popup[0].hide();
                    host().openExternal(url);
                }, "Open Secure Checkout", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> popup[0].hide());
        builder.addRow(detail("Status", state));
        if (offer != null) {
            builder.addRow(detail("Offer", offer.label()));
            builder.addRow(detail("Resources", offerSpecs(offer)));
            builder.addRow(detail("Domain", offer.locationLabel() + " • " + offer.cpuClassLabel()));
            builder.addRow(detail("Price", price(offer)));
        }
        builder.addRow(detail("Availability", "Payment And Review Must Finish Before Capacity Is Granted"));
        popup[0] = show(builder.build());
    }

    private void create(UUID poolId) {
        handoff = true;
        client.setScreen(new ServerConfigurationScreen(this, remotelyClient, controller.begin(poolId)));
    }

    private void showDraftActivation(ResourcePoolModels.Draft draft) {
        ResourceFields fields = new ResourceFields(draft.installer(), draft.runtime(), draft.retained());
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = resourcePopup("Activate " + draft.metadata().name(), fields)
                .addTitleAction("Activate", () -> {
                    popup[0].hide();
                    Notification notice = operationNotice("Submitting Activation", draft.metadata().name());
                    controller.activate(draft, fields.values(draft.metadata().gameId(), draft.metadata().profileId()))
                            .whenComplete((updated, failure) -> host().application().execute(() -> finishActivation(notice, updated, failure)));
                }, "Activate Server", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> popup[0].hide());
        popup[0] = show(builder.build());
    }

    private void showAllocation(ResourcePoolModels.Allocation allocation, ResourcePoolModels.Progress progress) {
        ResourceFields fields = new ResourceFields(allocation.desired(), allocation.desired(), allocation.retained());
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Pool Resources").width(380)
                .onClose(() -> popup[0].hide());
        builder.addRow(detail("Desired", compute(allocation.desired())));
        builder.addRow(detail("Reserved", compute(allocation.reserved())));
        builder.addRow(detail("Effective", compute(allocation.effective())));
        builder.addRow(detail("Storage", allocation.retained().diskMiB() + " MiB Disk • "
                + allocation.retained().backupMiB() + " MiB Backup"));
        if (pendingRestart(allocation)) {
            builder.addRow(detail("Restart", "Pending Restart Keeps The Reserved Compute"));
        }
        if (progress != null) {
            builder.addRow(detail("Progress", progressLabel(progress)));
        }
        if (allocation.state() == ResourcePoolModels.AllocationState.ACTIVE) {
            builder.addTitleAction("Change", () -> {
                popup[0].hide();
                showAssignment(allocation, fields);
            }, "Change Assigned Compute", PopupWidget.TitleActionRole.PRIMARY);
            builder.addTitleAction("Disable", () -> {
                popup[0].hide();
                disable(allocation);
            }, "Disable Pool Resources", PopupWidget.TitleActionRole.DESTRUCTIVE);
        } else if (allocation.state() == ResourcePoolModels.AllocationState.DISABLED) {
            builder.addTitleAction("Assign", () -> {
                popup[0].hide();
                showAssignment(allocation, fields);
            }, "Assign Pool Resources", PopupWidget.TitleActionRole.PRIMARY);
        }
        popup[0] = show(builder.build());
    }

    private void showAssignment(ResourcePoolModels.Allocation allocation, ResourceFields fields) {
        PopupWidget[] popup = new PopupWidget[1];
        PopupWidget.Builder builder = new PopupWidget.Builder("Change Resources").width(380)
                .addTitleAction("Apply", () -> {
                    popup[0].hide();
                    Notification notice = operationNotice("Submitting Resource Change", allocation.serverId());
                    controller.assign(allocation, fields.runtimeRam.getText(), fields.runtimeCpu.getText())
                            .whenComplete((progress, failure) -> host().application().execute(() -> finishMutation(notice, progress, failure, false)));
                }, "Apply Resource Change", PopupWidget.TitleActionRole.PRIMARY)
                .onClose(() -> popup[0].hide());
        builder.addRow(row("RAM MiB", fields.runtimeRam, "Desired Runtime RAM"));
        builder.addRow(row("CPU Percent", fields.runtimeCpu, "Desired Runtime CPU Quota"));
        popup[0] = show(builder.build());
    }

    private void disable(ResourcePoolModels.Allocation allocation) {
        Notification notice = operationNotice("Submitting Disable", allocation.serverId());
        controller.disable(allocation).whenComplete((progress, failure) ->
                host().application().execute(() -> finishMutation(notice, progress, failure, true)));
    }

    private void finishActivation(Notification notice, ResourcePoolModels.Draft draft, Throwable failure) {
        if (failure != null) {
            notice.update().message("Activation Needs Attention").description(ResourcePoolController.message(failure))
                    .type(Notification.Type.ERROR).loading(false).autoSlideOut(true).commit();
            controller.refresh();
            return;
        }
        Notification.Type type = draft.state() == ResourcePoolModels.DraftState.ACTIVE ? Notification.Type.SUCCESS
                : draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? Notification.Type.WARN : Notification.Type.INFO;
        String message = draft.state() == ResourcePoolModels.DraftState.ACTIVE ? "Server Active"
                : draft.state() == ResourcePoolModels.DraftState.UNKNOWN ? "Activation Needs Review" : "Activation Submitted";
        String detail = draft.state() == ResourcePoolModels.DraftState.UNKNOWN
                ? draft.reason() == null ? "Reservation Retained" : draft.reason() : draft.metadata().name();
        notice.update().message(message).description(detail).type(type).loading(false).autoSlideOut(true).commit();
        controller.refresh();
    }

    private void finishMutation(Notification notice, ResourcePoolModels.Progress progress, Throwable failure, boolean disable) {
        if (failure != null) {
            notice.update().message(disable ? "Disable Needs Attention" : "Resource Change Needs Attention")
                    .description(ResourcePoolController.message(failure)).type(Notification.Type.ERROR)
                    .loading(false).autoSlideOut(true).commit();
            controller.refresh();
            return;
        }
        boolean settled = progress.operation().state() == ResourcePoolModels.OperationState.SETTLED;
        boolean review = progress.operation().state() == ResourcePoolModels.OperationState.UNKNOWN
                || progress.hosting() != null && progress.hosting().state() == ResourcePoolModels.HostingState.NEEDS_REVIEW;
        String message = review ? "Operation Needs Review" : settled ? disable ? "Resources Disabled" : "Resources Updated"
                : disable ? "Disable Submitted" : "Resource Change Submitted";
        String detail = review && progress.hosting() != null && progress.hosting().reason() != null
                ? progress.hosting().reason() : disable && !settled ? "Resources Stay Reserved Until Disable Settles" : progressLabel(progress);
        notice.update().message(message).description(detail).type(review ? Notification.Type.WARN
                : settled ? Notification.Type.SUCCESS : Notification.Type.INFO).loading(false).autoSlideOut(true).commit();
        controller.refresh();
    }

    private PopupWidget.Builder resourcePopup(String title, ResourceFields fields) {
        PopupWidget.Builder builder = new PopupWidget.Builder(title).width(380);
        builder.addRow(row("Installer RAM MiB", fields.installerRam, "Compute Reserved During Installation"));
        builder.addRow(row("Installer CPU Percent", fields.installerCpu, "CPU Reserved During Installation"));
        builder.addRow(row("Runtime RAM MiB", fields.runtimeRam, "Compute Reserved While Active"));
        builder.addRow(row("Runtime CPU Percent", fields.runtimeCpu, "CPU Reserved While Active"));
        builder.addRow(row("Disk MiB", fields.disk, "Persistent Server Storage"));
        builder.addRow(row("Backup MiB", fields.backup, "Persistent Backup Storage"));
        return builder;
    }

    private PopupWidget.PopupRow row(String name, TextInputWidget input, String description) {
        return new PopupWidget.PopupRow.Builder(name, input).description(description).build();
    }

    private PopupWidget.PopupRow detail(String name, String value) {
        return new PopupWidget.PopupRow.Builder(name, summary(value, "", "calm")).contentWidth().build();
    }

    private PopupWidget show(PopupWidget popup) {
        popup.setX((width - popup.getWidth()) / 2);
        popup.setY((height - popup.getHeight()) / 2);
        addDrawableChild(popup);
        popup.show();
        return popup;
    }

    private Notification operationNotice(String message, String description) {
        return new Notification.Builder().message(message).description(description).type(Notification.Type.INFO)
                .loading(true).autoSlideOut(false).build();
    }

    private AnimatedButton summary(String label, String hint, String accent) {
        Accent color = ThemeManager.getAccent(accent);
        return new AnimatedButton.Builder().size(rowWidth(), 22).label(label).hint(hint).accentType(color).build();
    }

    private int rowWidth() {
        return Math.max(220, width - 44);
    }

    private static boolean zero(ResourcePoolModels.Resources resources) {
        return "0".equals(resources.ramMiB()) && "0".equals(resources.cpuQuotaPercent())
                && "0".equals(resources.diskMiB()) && "0".equals(resources.backupMiB());
    }

    private static String compute(ResourcePoolModels.Compute compute) {
        return compute.ramMiB() + " MiB RAM / " + compute.cpuQuotaPercent() + "% CPU";
    }

    private static ResourcePoolModels.Offer offer(List<ResourcePoolModels.Offer> offers, String offerId) {
        return offers.stream().filter(offer -> offer.id().equals(offerId)).findFirst().orElse(null);
    }

    private static String offerSpecs(ResourcePoolModels.Offer offer) {
        return offer.ramMiB() + " MiB RAM • " + offer.cpuQuotaPercent() + "% CPU • " + offer.diskMiB()
                + " MiB Disk • " + offer.backupMiB() + " MiB Backup";
    }

    private static String price(ResourcePoolModels.Offer offer) {
        if (offer.priceCurrency() == null || offer.billingPeriodLabel() == null) {
            return "See Checkout For Price And Billing Period";
        }
        String cents = offer.priceCents();
        String padded = cents.length() < 3 ? "0".repeat(3 - cents.length()) + cents : cents;
        return offer.priceCurrency() + " " + padded.substring(0, padded.length() - 2) + "."
                + padded.substring(padded.length() - 2) + " • " + offer.billingPeriodLabel();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean pendingRestart(ResourcePoolModels.Allocation allocation) {
        return allocation.state() == ResourcePoolModels.AllocationState.ACTIVE
                && (!allocation.desired().ramMiB().equals(allocation.effective().ramMiB())
                || !allocation.desired().cpuQuotaPercent().equals(allocation.effective().cpuQuotaPercent()));
    }

    private static String progressLabel(ResourcePoolModels.Progress progress) {
        String operation = title(progress.operation().state().name());
        if (progress.hosting() == null) {
            return operation;
        }
        String result = operation + " • " + title(progress.hosting().state().name());
        return progress.hosting().reason() == null || progress.hosting().reason().isBlank()
                ? result : result + " • " + progress.hosting().reason();
    }

    private static String title(String value) {
        String[] words = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        host().application().openParentScreen(this, parent);
    }

    @Override
    public void removed() {
        closed = true;
        if (!handoff) {
            controller.dispose();
        }
        super.removed();
    }

    private static final class ResourceFields {
        private final TextInputWidget installerRam;
        private final TextInputWidget installerCpu;
        private final TextInputWidget runtimeRam;
        private final TextInputWidget runtimeCpu;
        private final TextInputWidget disk;
        private final TextInputWidget backup;

        private ResourceFields(ResourcePoolModels.Compute installer, ResourcePoolModels.Compute runtime,
                               ResourcePoolModels.Storage storage) {
            installerRam = input(value(installer == null ? null : installer.ramMiB(), "2048"), "Installer RAM MiB");
            installerCpu = input(value(installer == null ? null : installer.cpuQuotaPercent(), "200"), "Installer CPU Percent");
            runtimeRam = input(value(runtime == null ? null : runtime.ramMiB(), "2048"), "Runtime RAM MiB");
            runtimeCpu = input(value(runtime == null ? null : runtime.cpuQuotaPercent(), "200"), "Runtime CPU Percent");
            disk = input(value(storage == null ? null : storage.diskMiB(), "10240"), "Disk MiB");
            backup = input(storage == null ? "0" : storage.backupMiB(), "Backup MiB");
        }

        private ServerScreenHost.PoolResources values(String gameId, String profileId) {
            return new ServerScreenHost.PoolResources(gameId, profileId, installerRam.getText(), installerCpu.getText(),
                    runtimeRam.getText(), runtimeCpu.getText(), disk.getText(), backup.getText());
        }

        private static TextInputWidget input(String value, String placeholder) {
            return new TextInputWidget.Builder().text(value).placeholder(placeholder).build();
        }

        private static String value(String value, String fallback) {
            return value == null || "0".equals(value) ? fallback : value;
        }
    }
}
