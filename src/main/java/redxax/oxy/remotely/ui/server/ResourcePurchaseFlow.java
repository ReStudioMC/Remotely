package redxax.oxy.remotely.ui.server;

import restudio.rebase.resource.ResourcePoolModels;
import restudio.rebase.resource.ResourcePricePreview;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.DoubleSliderWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class ResourcePurchaseFlow {
    interface Checkout {
        void open(ResourcePoolController.PurchaseIntent intent, ResourcePoolModels.Offer offer, ResourcePoolModels.Quote quote);
    }

    private final ResourcePoolController controller;
    private final List<ResourcePoolModels.Offer> offers;
    private final UUID poolId;
    private final Consumer<Runnable> dispatch;
    private final BooleanSupplier ownerClosed;
    private final Checkout checkout;
    private final List<Amount> amounts = new ArrayList<>();
    private final Map<String, PopupWidget.PopupRow> rows = new LinkedHashMap<>();
    private final String account;
    private final AnimatedButton status = label("");
    private final MountableButtonWidget summary = information("Resources", "", "Reactor.png");
    private final MountableButtonWidget cpuTerms = information("CPU Pricing", "", "cpu.png");
    private final MountableButtonWidget diskTerms = information("Disk Pricing", "", "disk.png");
    private final AnimatedButton advanced = button("Advanced", this::toggleAdvanced);
    private final AnimatedButton followRam = button("CPU Follows RAM", this::followRam);
    private final AnimatedButton cancel = button("Cancel", this::close);
    private final MountableButtonWidget subtotal = information("Resource Price", "", "disk.png");
    private final MountableButtonWidget discount = information("Discount", "", "checkmark.png");
    private final MountableButtonWidget credit = information("Unused Time Credit", "", "goback.png");
    private final MountableButtonWidget renewal = information("Renewal", "", "Reactor.png");
    private final AnimatedButton review = button("Continue", this::requestQuote);
    private final AnimatedButton edit = button("Edit Resources", this::edit);
    private final AnimatedButton pay = button("Checkout", this::checkout);
    private final PopupWidget popup;
    private final int popupWidth;
    private final int popupHeight;
    private ResourcePoolModels.Offer offer;
    private ResourcePoolModels.Rate rate;
    private ResourcePoolModels.Availability availability;
    private ResourcePricePreview pricing;
    private ResourcePoolModels.Rate pricedRate;
    private BigDecimal cpuQuotaPerMiB = BigDecimal.ZERO;
    private Instant nextPriceChange;
    private final DropDownWidget<ResourcePoolModels.Offer> selector;
    private ResourcePoolModels.QuoteRequest pending;
    private ResourcePoolModels.Quote quote;
    private boolean busy;
    private boolean ended;
    private boolean reviewing;
    private boolean valid;
    private boolean cpuManual;
    private boolean advancedOpen;
    private boolean cpuSurcharge;
    private boolean diskSurcharge;
    private long revision;

    ResourcePurchaseFlow(ResourcePoolController controller, List<ResourcePoolModels.Offer> offers, UUID poolId,
                         int width, int height, Consumer<Runnable> dispatch, BooleanSupplier ownerClosed, Checkout checkout) {
        this.controller = controller;
        this.poolId = poolId;
        this.offers = offers.stream().filter(value -> findRate(value) != null).toList();
        this.dispatch = dispatch;
        this.ownerClosed = ownerClosed;
        this.checkout = checkout;
        account = controller.snapshot().accountId();
        popupWidth = Math.min(560, Math.max(220, width - 16));
        popupHeight = Math.min(342, Math.max(160, height - 8));
        if (this.offers.isEmpty()) throw new IllegalArgumentException("Resource Capacity Is Unavailable. Refresh And Try Again");
        popup = new PopupWidget.Builder(poolId == null ? "Buy Resources" : "Expand Pool")
                .size(popupWidth, popupHeight).padding(8).rowGap(4).setMinSize(200, 120)
                .onClose(this::close).build();
        selector = new DropDownWidget.Builder<>(this.offers).selectedItem(this.offers.getFirst())
                .displayFunction(value -> value.label() + " • " + value.locationLabel() + " • " + value.cpuClassLabel())
                .onSelectionChanged(this::select).size(300, 18).build();
        amounts.add(new Amount("Memory (GB)", "ram.png", 1024));
        amounts.add(new Amount("CPU Equivalents", "cpu.png", 100));
        amounts.add(new Amount("Disk (GB)", "disk.png", 1024));
        addRow("summary", "", summary);
        addRow("offer", "Recommended Configuration", selector);
        for (int index = 0; index < amounts.size(); index++) {
            Amount amount = amounts.get(index);
            addRow("resource-" + index, "", amount.card);
        }
        addRow("cpu-follow", "", followRam);
        addRow("cpu-terms", "", cpuTerms);
        addRow("disk-terms", "", diskTerms);
        addRow("advanced", "", advanced);
        addRow("status", "", status);
        addRow("form-actions", "", review, cancel);
        addRow("subtotal", "", subtotal);
        addRow("discount", "", discount);
        addRow("credit", "", credit);
        addRow("renewal", "", renewal);
        addRow("review-actions", "", edit, pay);
        popup.setRows(rows.values());
        select(this.offers.getFirst());
    }

    PopupWidget popup() {
        return popup;
    }

    void close() {
        ended = true;
        revision++;
        popup.hide();
    }

    private boolean current() {
        return !ended && !ownerClosed.getAsBoolean() && account.equals(controller.snapshot().accountId());
    }

    private ResourcePoolModels.Rate findRate(ResourcePoolModels.Offer candidate) {
        if (candidate == null) return null;
        return controller.rates().stream().filter(value -> value.domain().location().equals(candidate.location())
                && value.domain().cpuClass().equals(candidate.cpuClass())).findFirst().orElse(null);
    }

    private ResourcePoolModels.Availability findAvailability(ResourcePoolModels.Offer candidate) {
        if (candidate == null) return null;
        Instant now = Instant.now();
        return controller.availability().stream().filter(value -> value.current(now) && Objects.equals(value.poolId(), poolId)
                && value.domain().location().equals(candidate.location())
                && value.domain().cpuClass().equals(candidate.cpuClass())).findFirst().orElse(null);
    }

    private static boolean feasible(ResourcePoolModels.Rate rate, ResourcePoolModels.Availability available) {
        if (rate == null || available == null) return false;
        ResourcePoolModels.Resources minimum = rate.minimum();
        ResourcePoolModels.Resources maximum = available.maximum();
        return new BigInteger(minimum.ramMiB()).compareTo(limit(rate.maximum().ramMiB(), maximum.ramMiB(), false)) <= 0
                && new BigInteger(minimum.cpuQuotaPercent()).compareTo(limit(rate.maximum().cpuQuotaPercent(), maximum.cpuQuotaPercent(), true)) <= 0
                && new BigInteger(minimum.diskMiB()).compareTo(limit(rate.maximum().diskMiB(), maximum.diskMiB(), false)) <= 0;
    }

    private static BigInteger limit(String catalog, String available, boolean unlimited) {
        BigInteger capacity = new BigInteger(available);
        return unlimited && catalog.equals("0") ? capacity : new BigInteger(catalog).min(capacity);
    }

    private void select(ResourcePoolModels.Offer selected) {
        if (busy) return;
        ResourcePoolModels.Rate selectedRate = findRate(selected);
        ResourcePoolModels.Availability selectedAvailability = findAvailability(selected);
        if (selectedRate == null) {
            if (offer != null) {
                selector.setOnSelectionChanged(null);
                selector.setSelectedItem(offer);
                selector.setOnSelectionChanged(this::select);
            }
            status("Resource Pricing Is Unavailable. Refresh Resources");
            return;
        }
        offer = selected;
        cpuManual = false;
        applyRate(selectedRate, selectedAvailability);
        amounts.get(0).text = units(offer.ramMiB(), 1024);
        amounts.get(1).text = units(offer.cpuQuotaPercent(), 100);
        amounts.get(2).text = units(offer.diskMiB(), 1024);
        if (poolId != null) {
            ResourcePoolModels.Pool pool = controller.snapshot().pools().stream().map(ResourcePoolController.PoolView::pool)
                    .filter(value -> value.id().equals(poolId)).findFirst().orElseThrow(() -> new IllegalArgumentException("Refresh This Pool Before Expanding"));
            ResourcePoolModels.Resources existing = pool.balance().entitled();
            List<String> quantities = List.of(existing.ramMiB(), existing.cpuQuotaPercent(), existing.diskMiB());
            for (int index = 0; index < amounts.size(); index++) {
                Amount amount = amounts.get(index);
                BigInteger value = new BigInteger(quantities.get(index)).max(amount.value()).max(amount.minimum);
                if (amount.step.signum() > 0) {
                    BigInteger distance = value.subtract(amount.minimum);
                    value = amount.minimum.add(distance.add(amount.step).subtract(BigInteger.ONE).divide(amount.step).multiply(amount.step));
                }
                amount.text = units(value.toString(), amount.divisor);
            }
            cpuManual = true;
            advancedOpen = true;
        } else {
            BigInteger suggested = suggestedCpu();
            cpuManual = suggested != null && !suggested.equals(amounts.get(1).value());
        }
        changed();
        updateVisibility();
    }

    private void applyRate(ResourcePoolModels.Rate selected, ResourcePoolModels.Availability available) {
        rate = selected;
        availability = available;
        if (!Objects.equals(pricedRate, rate)) {
            pricing = rate == null ? null : new ResourcePricePreview(rate);
            pricedRate = rate;
            cpuQuotaPerMiB = rate == null ? BigDecimal.ZERO : new BigDecimal(rate.cpuQuotaPerGiB()).divide(BigDecimal.valueOf(1024));
        }
        if (!feasible(rate, availability)) return;
        amounts.get(0).limits(rate.minimum().ramMiB(), limit(rate.maximum().ramMiB(), available.maximum().ramMiB(), false).toString(), rate.step().ramMiB());
        amounts.get(1).limits(rate.minimum().cpuQuotaPercent(), limit(rate.maximum().cpuQuotaPercent(), available.maximum().cpuQuotaPercent(), true).toString(), rate.step().cpuQuotaPercent());
        amounts.get(2).limits(rate.minimum().diskMiB(), limit(rate.maximum().diskMiB(), available.maximum().diskMiB(), false).toString(), rate.step().diskMiB());
    }

    private void changed() {
        revision++;
        pending = null;
        quote = null;
        status("");
        review.setMessage("Continue");
        updatePreview();
    }

    void tick() {
        if (!current()) return;
        amounts.forEach(Amount::layout);
        ResourcePoolModels.Rate next = findRate(offer);
        ResourcePoolModels.Availability nextAvailability = findAvailability(offer);
        boolean changedTerms = !Objects.equals(next, rate) || !sameCapacity(availability, nextAvailability);
        if (changedTerms) {
            boolean wasReviewing = reviewing;
            reviewing = false;
            if (next == null) {
                rate = null;
                availability = nextAvailability;
            } else {
                applyRate(next, nextAvailability);
            }
            suggestCpu();
            changed();
            if (wasReviewing) status("Resource Terms Changed. Review Your Resources And Continue");
            updateVisibility();
        } else {
            availability = nextAvailability;
        }
        if (reviewing && quote != null && !Instant.parse(quote.expiresAt()).isAfter(Instant.now())) {
            edit();
            status("Your Price Expired. Continue To Refresh It");
            return;
        }
        if (busy || reviewing) return;
        if (nextPriceChange != null && !Instant.now().isBefore(nextPriceChange)) changed();
    }

    private static boolean sameCapacity(ResourcePoolModels.Availability first, ResourcePoolModels.Availability second) {
        return first == null ? second == null : second != null && first.maximum().equals(second.maximum());
    }

    private boolean capacityCurrent() {
        ResourcePoolModels.Availability latest = findAvailability(offer);
        return rate != null && rate.equals(findRate(offer)) && feasible(rate, latest) && sameCapacity(availability, latest);
    }

    private ResourcePoolModels.Resources resources() {
        return new ResourcePoolModels.Resources(amounts.get(0).raw(), amounts.get(1).raw(), amounts.get(2).raw(), "0");
    }

    private BigDecimal cpuAllowance() {
        return cpuQuotaPerMiB.multiply(new BigDecimal(amounts.get(0).raw()));
    }

    private BigInteger suggestedCpu() {
        if (cpuQuotaPerMiB.signum() <= 0 || !capacityCurrent()) return null;
        Amount cpu = amounts.get(1);
        if (cpu.step.signum() <= 0) return null;
        try {
            BigInteger quota = cpuAllowance().setScale(0, RoundingMode.DOWN).toBigIntegerExact().max(cpu.minimum).min(cpu.maximum);
            return cpu.minimum.add(quota.subtract(cpu.minimum).divide(cpu.step).multiply(cpu.step));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void suggestCpu() {
        if (cpuManual) return;
        BigInteger quota = suggestedCpu();
        if (quota != null) amounts.get(1).text = units(quota.toString(), amounts.get(1).divisor);
    }

    private void toggleAdvanced() {
        if (busy || reviewing || !current()) return;
        advancedOpen = !advancedOpen;
        updateVisibility();
    }

    private void followRam() {
        if (busy || reviewing || !current() || !capacityCurrent() || cpuQuotaPerMiB.signum() <= 0) return;
        cpuManual = false;
        suggestCpu();
        changed();
    }

    private boolean describeTerms(MountableButtonWidget card, Amount amount, String allowancePerGiB, String multiplier, String unit) {
        try {
            BigDecimal allowance = new BigDecimal(allowancePerGiB).multiply(new BigDecimal(amounts.get(0).raw())).divide(BigDecimal.valueOf(1024));
            BigDecimal excess = new BigDecimal(amount.raw()).subtract(allowance).max(BigDecimal.ZERO).divide(BigDecimal.valueOf(amount.divisor));
            BigDecimal extraRate = new BigDecimal(multiplier);
            if (allowance.signum() <= 0 || extraRate.compareTo(BigDecimal.ONE) <= 0) {
                card.setName(amount.label + " Pricing");
                card.setDescription("All Selected " + unit + " At Standard Rate");
                return false;
            }
            card.setName("Standard Rate Up To " + allowance.divide(BigDecimal.valueOf(amount.divisor)).stripTrailingZeros().toPlainString() + " " + unit);
            card.setDescription("Above: " + extraRate.stripTrailingZeros().toPlainString() + "x Unit Rate • "
                    + excess.stripTrailingZeros().toPlainString() + " Extra " + unit + " In This Price");
            return excess.signum() > 0;
        } catch (IllegalArgumentException failure) {
            card.setName(amount.label + " Pricing");
            card.setDescription("Enter Valid Resources To See RAM Allowances And Extra Charges");
            return false;
        }
    }

    private void updatePreview() {
        Instant now = Instant.now();
        boolean capacityReady = capacityCurrent();
        amounts.get(0).card.setDescription(cpuManual ? "Custom CPU • Edit In Advanced" : cpuQuotaPerMiB.signum() > 0 ? "CPU Follows RAM" : "CPU From Selected Configuration");
        amounts.get(1).card.setDescription("Shared Quota • 1 CPU Equivalent = 100%");
        amounts.get(2).card.setDescription("Server Files And Backups Share This Limit");
        followRam.setMessage(cpuManual ? "Use CPU From RAM" : "CPU Follows RAM");
        followRam.active = !busy && capacityReady && cpuQuotaPerMiB.signum() > 0;
        cpuSurcharge = rate != null && describeTerms(cpuTerms, amounts.get(1), rate.cpuQuotaPerGiB(), rate.excessCpuMultiplier(), "CPU Equivalents");
        diskSurcharge = rate != null && describeTerms(diskTerms, amounts.get(2), rate.diskMiBPerGiB(), rate.excessDiskMultiplier(), "GB Disk");
        nextPriceChange = rate == null ? null : rate.automaticDiscounts().stream().flatMap(value -> value.endsAt() == null
                        ? Stream.of(value.startsAt()) : Stream.of(value.startsAt(), value.endsAt()))
                .filter(time -> time.isAfter(now)).min(Instant::compareTo).orElse(null);
        try {
            if (pricing == null || !capacityReady) throw new IllegalArgumentException("Resource Capacity Is Unavailable. Refresh Resources");
            ResourcePoolModels.Resources selected = resources();
            ResourcePricePreview.Price price = pricing.price(selected, now);
            if (new BigInteger(price.firstPeriodCents()).signum() <= 0) throw new IllegalArgumentException("This Package Is Unavailable");
            summary.setName(money(rate.currency(), price.firstPeriodCents()) + " / " + rate.periodDays() + " Days");
            String detail = price.discountCents().equals("0") ? offer.locationLabel() + " • " + offer.cpuClassLabel()
                    : money(rate.currency(), price.subtotalCents()) + " Before Discount • Save " + money(rate.currency(), price.discountCents());
            summary.setDescription(specs(selected));
            renewal.setName("Renews At " + money(rate.currency(), price.renewalCents()) + " Every " + rate.periodDays() + " Days");
            if (poolId != null) detail += " • New Capacity Before Unused Time Credit";
            renewal.setDescription(detail);
            valid = true;
        } catch (RuntimeException failure) {
            summary.setName("Choose Your Resources");
            summary.setDescription(ResourcePoolController.message(failure));
            valid = false;
        }
        review.active = !busy && valid;
        updateVisibility();
        for (Amount amount : amounts) {
            amount.sync();
            amount.active(!busy && pricing != null && capacityReady);
        }
    }

    private void status(String message) {
        status.setMessage(message);
        if (visible("status", !message.isBlank())) fitPopup();
    }

    private boolean visible(String id, boolean value) {
        PopupWidget.PopupRow row = rows.get(id);
        if (row.visible == value) return false;
        popup.setRowVisibility(id, value);
        return true;
    }

    private void updateVisibility() {
        visible("offer", !reviewing);
        for (int index = 0; index < amounts.size(); index++) {
            boolean shown = !reviewing && (index == 0 || advancedOpen);
            visible("resource-" + index, shown);
        }
        visible("cpu-follow", !reviewing && advancedOpen && cpuQuotaPerMiB.signum() > 0);
        visible("cpu-terms", !reviewing && (advancedOpen || cpuSurcharge));
        visible("disk-terms", !reviewing && (advancedOpen || diskSurcharge));
        visible("advanced", !reviewing);
        advanced.setMessage(advancedOpen ? "Hide Advanced" : "Advanced");
        visible("form-actions", !reviewing);
        visible("subtotal", reviewing);
        visible("discount", reviewing && !"0".equals(quote.price().discountCents()));
        visible("credit", reviewing && !"0".equals(quote.creditCents()));
        visible("renewal", reviewing || valid);
        visible("review-actions", reviewing);
        popup.setTitle(reviewing ? "Review Purchase" : poolId == null ? "Buy Resources" : "Expand Pool");
        fitPopup();
    }

    private void fitPopup() {
        popup.fitContentHeight();
        popup.setHeight(Math.min(popupHeight, popup.getHeight()));
        popup.snapAnimatedHeight();
    }

    private void requestQuote() {
        if (busy || !current() || !valid) return;
        if (!capacityCurrent()) {
            changed();
            status("Resource Capacity Changed. Refresh Resources");
            return;
        }
        try {
            if (pending == null) pending = new ResourcePoolModels.QuoteRequest(UUID.randomUUID(), offer.id(), resources(), "", poolId);
        } catch (RuntimeException failure) {
            status(ResourcePoolController.message(failure));
            return;
        }
        ResourcePoolModels.QuoteRequest request = pending;
        long ticket = revision;
        setBusy(true);
        review.setMessage("Checking Price...");
        controller.quote(request).whenComplete((result, failure) -> dispatch.accept(() -> {
            if (!current()) return;
            setBusy(false);
            if (ticket != revision) return;
            if (!capacityCurrent()) {
                changed();
                status("Resource Capacity Changed. Refresh Resources");
                return;
            }
            if (failure != null) {
                status(ResourcePoolController.message(failure));
                review.setMessage("Try Again");
                return;
            }
            if (!result.price().resources().equals(request.resources()) || !result.offerId().equals(request.offerId())
                    || !Objects.equals(result.poolId(), request.poolId())) {
                status("The Price Does Not Match Your Selection. Try Again");
                pending = null;
                review.setMessage("Continue");
                return;
            }
            quote = result;
            showReview();
        }));
    }

    private void setBusy(boolean value) {
        busy = value;
        review.active = !value && valid;
        selector.active = !value;
        advanced.active = !value;
        followRam.active = !value && cpuQuotaPerMiB.signum() > 0 && capacityCurrent();
        for (Amount amount : amounts) amount.active(!value && pricing != null && capacityCurrent());
    }

    private void showReview() {
        ResourcePoolModels.Price price = quote.price();
        summary.setName("Due Today • " + money(price.currency(), quote.dueCents()));
        summary.setDescription(specs(price.resources()));
        subtotal.setDescription(money(price.currency(), price.subtotalCents()));
        discount.setDescription("-" + money(price.currency(), price.discountCents()));
        credit.setDescription("-" + money(price.currency(), quote.creditCents()));
        renewal.setName("Renews At " + money(price.currency(), price.renewalCents()) + " Every " + price.periodDays() + " Days");
        renewal.setDescription("Resources Become Available After Payment And Review");
        pay.setMessage("Checkout • " + money(price.currency(), quote.dueCents()));
        reviewing = true;
        status("");
        updateVisibility();
    }

    private void edit() {
        reviewing = false;
        changed();
        updateVisibility();
    }

    private void checkout() {
        if (!current() || busy || quote == null) return;
        if (!capacityCurrent()) {
            edit();
            status("Resource Capacity Changed. Refresh Resources");
            return;
        }
        if (!Instant.parse(quote.expiresAt()).isAfter(Instant.now())) {
            edit();
            status("Your Price Expired. Continue To Refresh It");
            return;
        }
        ResourcePoolController.PurchaseIntent intent = null;
        try {
            intent = controller.beginPurchase(offer, quote);
            checkout.open(intent, offer, quote);
            close();
        } catch (RuntimeException failure) {
            controller.discardPurchase(intent);
            edit();
            status(ResourcePoolController.message(failure));
        }
    }

    private void addRow(String id, String title, Widget... widgets) {
        rows.put(id, new PopupWidget.PopupRow.Builder(title, widgets).id(id).build());
    }

    private static AnimatedButton button(String title, Runnable action) {
        return new AnimatedButton.Builder().label(title).onClick(action).size(160, 18).animateElevation(false).build();
    }

    private static String money(String currency, String cents) {
        if (currency == null || currency.isBlank()) return "Price At Review";
        return (currency.equals("USD") ? "$" : currency + " ") + new BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString();
    }

    static String specs(ResourcePoolModels.Resources resources) {
        String result = units(resources.ramMiB(), 1024) + " GB RAM  •  " + units(resources.cpuQuotaPercent(), 100)
                + " Shared CPU  •  " + units(resources.diskMiB(), 1024) + " GB Disk";
        return resources.backupMiB().equals("0") ? result : result + "  •  " + units(resources.backupMiB(), 1024) + " GB Backups";
    }

    private static String units(String value, int divisor) {
        return new BigDecimal(value).divide(BigDecimal.valueOf(divisor)).stripTrailingZeros().toPlainString();
    }

    private final class Amount {
        private final String label;
        private BigInteger minimum = BigInteger.ZERO;
        private BigInteger maximum = BigInteger.ZERO;
        private BigInteger step = BigInteger.ONE;
        private final int divisor;
        private final DoubleSliderWidget slider;
        private final MountableButtonWidget card;
        private String text = "0";

        private Amount(String label, String icon, int divisor) {
            this.label = label;
            this.divisor = divisor;
            slider = new DoubleSliderWidget.Builder().size((popupWidth - 28) * 3 / 5, 18).label("0").value(0)
                    .onChange(this::slide).onTextCommit(this::enter)
                    .hint("Drag To Resize • Shift Drag For Single Steps • Double Click To Enter A Value").build();
            card = new MountableButtonWidget.Builder(label).iconPath(icon).addWidget(slider).build();
            card.setSize(popupWidth - 16, 30);
            card.setCursorHoverReactive(false);
            if (popupWidth < 300) card.setIcon(null);
        }

        private void layout() {
            int width = Math.max(64, (card.getWidth() - 12) * 3 / 5);
            if (slider.getWidth() != width) slider.setWidth(width);
        }

        private void limits(String minimum, String maximum, String step) {
            this.minimum = new BigInteger(minimum);
            this.maximum = maximum == null ? null : new BigInteger(maximum);
            this.step = new BigInteger(step);
            if (this.maximum != null && this.maximum.compareTo(this.minimum) >= 0) {
                this.maximum = this.maximum.subtract(this.maximum.subtract(this.minimum).mod(this.step));
            }
            card.setDescription(units(this.minimum.toString(), divisor) + " To " + units(this.maximum.toString(), divisor));
            slider.setFineStep(steps().signum() > 0 ? 1.0 / steps().doubleValue() : 0);
        }

        private BigInteger steps() {
            return maximum == null || step.signum() <= 0 ? BigInteger.ZERO : maximum.subtract(minimum).max(BigInteger.ZERO).divide(step);
        }

        private void sync() {
            slider.label = text;
            try {
                BigInteger value = new BigInteger(raw());
                slider.label = units(value.toString(), divisor);
                BigInteger steps = steps();
                slider.setValue(steps.signum() <= 0 ? 0 : value.subtract(minimum).divide(step).doubleValue() / steps.doubleValue());
            } catch (IllegalArgumentException ignored) {
            }
        }

        private void slide() {
            if (busy || reviewing || !current() || !slider.active) return;
            BigInteger position = BigDecimal.valueOf(slider.getValue()).multiply(new BigDecimal(steps()))
                    .setScale(0, RoundingMode.HALF_UP).toBigIntegerExact();
            set(units(minimum.add(position.multiply(step)).toString(), divisor));
        }

        private void enter(String value) {
            if (busy || reviewing || !current() || !slider.active) return;
            set(value);
        }

        private void set(String value) {
            boolean manualChanged = this == amounts.get(1) && !cpuManual;
            if (this == amounts.get(1)) cpuManual = true;
            if (text.equals(value)) {
                if (manualChanged) changed();
                else sync();
                return;
            }
            text = value;
            if (this == amounts.get(0)) suggestCpu();
            changed();
        }

        private String range() {
            return units(minimum.toString(), divisor) + (maximum == null ? " Or More" : " To " + units(maximum.toString(), divisor))
                    + " • Steps Of " + units(step.toString(), divisor);
        }

        private BigInteger value() {
            try {
                if (text.length() > 18 || !text.trim().matches("[0-9]+(\\.[0-9]{1,10})?")) throw new IllegalArgumentException();
                return new BigDecimal(text.trim()).multiply(BigDecimal.valueOf(divisor)).toBigIntegerExact();
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Check " + label + ": " + range());
            }
        }

        private String raw() {
            BigInteger value = value();
            if (value.bitLength() > 63 || value.compareTo(minimum) < 0 || maximum != null && value.compareTo(maximum) > 0 || step.signum() <= 0
                    || value.subtract(minimum).mod(step).signum() != 0) {
                throw new IllegalArgumentException("Check " + label + ": " + range());
            }
            return value.toString();
        }

        private void active(boolean active) {
            slider.active = active;
        }
    }

    static MountableButtonWidget information(String title, String description, String icon) {
        MountableButtonWidget widget = new MountableButtonWidget.Builder(title).description(description).iconPath(icon).build();
        widget.setSize(240, 30);
        if ("Reactor.png".equals(icon)) {
            widget.setAccent(ThemeManager.getAccent("danger"));
            widget.setGradientEnabled(true);
        }
        widget.setCursorHoverReactive(false);
        return widget;
    }

    private static AnimatedButton label(String message) {
        AnimatedButton widget = new AnimatedButton.Builder().label(message).size(240, 18).centered(false)
                .flat(true).transparent(true).enableHoverColors(false).animateElevation(false).build();
        widget.setCursorHoverReactive(false);
        return widget;
    }
}
