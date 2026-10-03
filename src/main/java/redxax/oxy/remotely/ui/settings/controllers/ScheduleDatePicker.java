package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetComposite;
import restudio.rescreen.ui.widgets.AnimatedWidget;
import restudio.rescreen.ui.widgets.CalendarWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.TitledRowWidget;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

final class ScheduleDatePicker extends MountableButtonWidget {
    static final int SELECTOR_WIDTH = 140;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter YEARLY = DateTimeFormatter.ofPattern("d MMM");
    private static final List<String> HOURS = numbers(24);
    private static final List<String> MINUTES = numbers(60);
    private final CalendarWidget calendar = new CalendarWidget();
    private final DropDownWidget<String> scale;
    private final ScrollSelectorWidget hour;
    private final ScrollSelectorWidget minute;
    private final ScrollSelectorWidget second;
    private final boolean seconds;
    private final int timeLabels;
    private LocalDateTime value;
    private String pattern = "One Time";
    private boolean expanded;
    private boolean controls = true;
    private Runnable changed;

    ScheduleDatePicker(LocalDateTime initial, boolean seconds) {
        super("Choose Date And Time", "", "", new ArrayList<>(), null);
        this.seconds = seconds;
        value = initial.withNano(0);
        calendar.setValue(value);
        calendar.onChange(selected -> { value = selected; syncTime(); notifyChange(); });
        hour = wheel(HOURS, initial.getHour(), "Hour");
        minute = wheel(MINUTES, initial.getMinute(), "Minute");
        second = wheel(MINUTES, initial.getSecond(), seconds ? "Second" : "This Backend Uses Minute Precision");
        hour.setOnChange(this::chooseTime);
        minute.setOnChange(this::chooseTime);
        second.setOnChange(this::chooseTime);
        scale = new DropDownWidget.Builder<>(List.of("Day", "Week", "Month", "Year")).selectedItem("Day").size(SELECTOR_WIDTH, 20)
                .onSelectionChanged(selected -> calendar.setUnit(switch (selected) {
                    case "Week" -> CalendarWidget.Unit.WEEK;
                    case "Month" -> CalendarWidget.Unit.MONTH;
                    case "Year" -> CalendarWidget.Unit.YEAR;
                    default -> calendarUnit();
                })).build();
        addMountedWidget(scale);
        TitledRowWidget time = new TitledRowWidget.Builder()
                .addField("H", "Hour", new TitledRowWidget.FieldOptions().width(18).centerTitle(), hour)
                .addField("M", "Minute", new TitledRowWidget.FieldOptions().width(18).centerTitle(), minute)
                .addField("S", "Second", new TitledRowWidget.FieldOptions().width(18).centerTitle(), second)
                .wrapFields(false).fieldSpacing(3).autoHeight().build();
        timeLabels = time.getHeight() - hour.getHeight();
        TitledRowWidget body = new TitledRowWidget.Builder().addField("", calendar)
                .addField("", new TitledRowWidget.FieldOptions().width(60), time)
                .wrapFields(false).centerFields(true).fieldSpacing(8).autoHeight().build();
        setEmbeddedBody(List.of(body), false);
        setOnClick(() -> {
            if (!controls) return;
            expanded = !expanded;
            setEmbeddedBody(null, expanded);
            update();
        });
        configure();
        quiet(this);
    }

    private static List<String> numbers(int count) {
        return IntStream.range(0, count).mapToObj(number -> number < 10 ? "0" + number : Integer.toString(number)).toList();
    }

    private static ScrollSelectorWidget wheel(List<String> options, int selected, String hint) {
        return new ScrollSelectorWidget.Builder().options(options).selectedIndex(selected).vertical(true).size(18, 74).hint(hint).build();
    }

    static <T extends AnimatedWidget> T quiet(T widget) {
        widget.setAnimateElevation(false);
        widget.setEnableHoverColors(false);
        widget.setAnimateColor(false);
        widget.setAnimateLayout(false);
        widget.entranceAnimationEnabled = false;
        if (widget instanceof WidgetComposite composite) {
            for (Widget child : composite.getChildWidgets()) if (child instanceof AnimatedWidget animated) quiet(animated);
        }
        return widget;
    }

    @Override
    public void renderHintOverlay(IDrawContext context) {
    }

    void onChange(Runnable listener) { changed = listener; }
    LocalDateTime value() { return value; }
    String clock() { return value.format(CLOCK); }
    int weekDay() { return value.getDayOfWeek().getValue() % 7; }
    String monthDay() { return Integer.toString(value.getDayOfMonth()); }
    int month() { return value.getMonthValue(); }

    void pattern(String selected) {
        if (pattern.equals(selected)) return;
        pattern = selected;
        if ("One Time".equals(pattern) && value.toLocalDate().isBefore(LocalDate.now())) {
            value = LocalDate.now().plusDays(1).atTime(value.toLocalTime());
            calendar.setValue(value);
        }
        configure();
    }

    void controls(boolean visible) {
        controls = visible;
        setEmbeddedBody(null, expanded && controls);
        update();
    }

    private void configure() {
        calendar.setVisible(true);
        calendar.setHeight(ScheduleTimingGuide.WEEKLY.equals(pattern) ? 46 : ScheduleTimingGuide.MONTHLY.equals(pattern) ? 102 : 144);
        int wheelHeight = Math.max(20, calendar.getHeight() - timeLabels);
        hour.setHeight(wheelHeight);
        minute.setHeight(wheelHeight);
        second.setHeight(wheelHeight);
        scale.setVisible("One Time".equals(pattern));
        scale.setSelectedItem("Day");
        calendar.setUnit(calendarUnit());
        boolean everySecond = ScheduleTimingGuide.SECONDLY.equals(pattern);
        boolean everyMinute = everySecond || ScheduleTimingGuide.MINUTELY.equals(pattern);
        boolean everyHour = everyMinute || ScheduleTimingGuide.HOURLY.equals(pattern);
        hour.setActive(!everyHour);
        minute.setActive(!everyMinute);
        second.setActive(seconds && !everySecond);
        hour.setOptions(everyHour ? List.of("Any") : HOURS);
        minute.setOptions(everyMinute ? List.of("Any") : MINUTES);
        second.setOptions(everySecond ? List.of("Any") : seconds ? MINUTES : List.of(MINUTES.get(value.getSecond())));
        syncTime();
        update();
        setEmbeddedBody(null, expanded && controls);
    }

    private CalendarWidget.Unit calendarUnit() {
        return switch (pattern) {
            case ScheduleTimingGuide.WEEKLY -> CalendarWidget.Unit.WEEKDAY;
            case ScheduleTimingGuide.MONTHLY -> CalendarWidget.Unit.MONTH_DAY;
            default -> CalendarWidget.Unit.DATE;
        };
    }

    private void syncTime() {
        if (hour.isActive()) hour.setSelectedIndex(value.getHour());
        if (minute.isActive()) minute.setSelectedIndex(value.getMinute());
        if (second.isActive()) second.setSelectedIndex(value.getSecond());
    }

    private void chooseTime() {
        value = value.withHour(hour.isActive() ? hour.getSelectedIndex() : value.getHour())
                .withMinute(minute.isActive() ? minute.getSelectedIndex() : value.getMinute())
                .withSecond(second.isActive() ? second.getSelectedIndex() : value.getSecond());
        calendar.setValue(value);
        notifyChange();
    }

    private void notifyChange() {
        update();
        if (changed != null) changed.run();
    }

    private void update() {
        String date = switch (pattern) {
            case ScheduleTimingGuide.SECONDLY -> ScheduleTimingGuide.SECONDLY;
            case ScheduleTimingGuide.MINUTELY -> ScheduleTimingGuide.MINUTELY + " At Second " + (seconds ? clock().substring(6) : "00");
            case ScheduleTimingGuide.HOURLY -> ScheduleTimingGuide.HOURLY + " At :" + clock().substring(3, seconds && value.getSecond() != 0 ? 8 : 5);
            case ScheduleTimingGuide.DAILY -> ScheduleTimingGuide.DAILY + " At " + clock();
            case ScheduleTimingGuide.WEEKDAYS -> ScheduleTimingGuide.WEEKDAYS + " At " + clock();
            case ScheduleTimingGuide.WEEKLY -> "Every " + ScheduleTimingGuide.WEEKDAYS_LIST.get(weekDay()) + " At " + clock();
            case ScheduleTimingGuide.MONTHLY -> ScheduleTimingGuide.MONTHLY + " On Day " + monthDay() + " At " + clock();
            case ScheduleTimingGuide.YEARLY -> ScheduleTimingGuide.YEARLY + " On " + value.format(YEARLY) + " At " + clock();
            case "One Time" -> value.format(DATE);
            default -> clock();
        };
        setName(controls ? date : pattern);
        setMessage(controls ? date : pattern);
        boolean picksDate = "One Time".equals(pattern) || ScheduleTimingGuide.WEEKLY.equals(pattern)
                || ScheduleTimingGuide.MONTHLY.equals(pattern) || ScheduleTimingGuide.YEARLY.equals(pattern);
        setDescription(!controls ? "Choose A Repeat Frequency" : expanded
                ? picksDate ? "Select A Date And Scroll The Time Wheels" : "Scroll The Time Wheels. Repeat Sets The Days" : "Open Date And Time");
        setHint("");
    }
}
