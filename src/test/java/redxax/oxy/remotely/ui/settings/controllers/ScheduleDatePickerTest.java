package redxax.oxy.remotely.ui.settings.controllers;

import org.junit.jupiter.api.Test;
import restudio.rescreen.config.Config;
import restudio.rescreen.platform.input.ReModifierState;
import restudio.rescreen.platform.input.ReMouseButton;
import restudio.rescreen.platform.input.ReMouseEvent;
import restudio.rescreen.render.TextRenderer;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Widget;
import restudio.rescreen.ui.core.WidgetComposite;
import restudio.rescreen.ui.widgets.ScrollSelectorWidget;
import restudio.rescreen.ui.widgets.CalendarWidget;
import restudio.rescreen.ui.widgets.DropDownWidget;
import restudio.rescreen.ui.widgets.PopupWidget;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleDatePickerTest {
    @Test
    void wheelEditsKeepTheDateAndSurviveChangesToTheRepeatFrequency() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
        ScheduleDatePicker picker = new ScheduleDatePicker(LocalDateTime.of(2026, 12, 31, 23, 59, 58), true);
        ScrollSelectorWidget hour = wheel(picker, "Hour");
        ScrollSelectorWidget minute = wheel(picker, "Minute");
        ScrollSelectorWidget second = wheel(picker, "Second");
        second.onClick(second.getX(), second.getY() + second.getHeight() / 2d + 24, 0);
        assertEquals("23:59:59", picker.clock());
        assertEquals("2026-12-31", picker.value().toLocalDate().toString());
        picker.pattern(ScheduleTimingGuide.HOURLY);
        assertFalse(hour.isActive());
        minute.onClick(minute.getX(), minute.getY() + minute.getHeight() / 2d + 24, 0);
        assertEquals("23:00:59", picker.clock());
        picker.pattern(ScheduleTimingGuide.MINUTELY);
        assertFalse(minute.isActive());
        picker.pattern(ScheduleTimingGuide.DAILY);
        assertEquals("23", hour.getSelectedOption());
        assertEquals("00", minute.getSelectedOption());
        assertEquals("59", second.getSelectedOption());
        assertEquals("2026-12-31", picker.value().toLocalDate().toString());
    }

    @Test
    void expandedAnnualPickerKeepsWheelGesturesBesideItsCalendar() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
        boolean animations = Config.animationsEnabled;
        try {
            Config.animationsEnabled = false;
            ScheduleDatePicker picker = new ScheduleDatePicker(LocalDateTime.of(2028, 2, 29, 23, 15, 30), true);
            picker.setPosition(10, 10);
            picker.setWidth(400);
            picker.pattern(ScheduleTimingGuide.YEARLY);
            picker.setHovered(true);
            assertTrue(picker.mouseClicked(mouse(picker, ReMouseEvent.Action.PRESSED, 20, 20)));
            picker.tick();
            ScrollSelectorWidget hour = wheel(picker, "Hour");
            ScrollSelectorWidget minute = wheel(picker, "Minute");
            ScrollSelectorWidget second = wheel(picker, "Second");
            CalendarWidget calendar = calendar(picker);
            assertEquals(CalendarWidget.Unit.DATE, calendar.unit());
            assertTrue(hour.getX() >= calendar.getX() + calendar.getWidth());
            assertEquals(18, hour.getWidth());
            assertEquals(18, minute.getWidth());
            assertEquals(18, second.getWidth());
            double x = hour.getX() + hour.getWidth() / 2d;
            double y = hour.getY() + hour.getHeight() / 2d;
            double spacing = Math.max(20, TextRenderer.tr.fontHeight + 12);
            assertTrue(picker.mouseClicked(mouse(picker, ReMouseEvent.Action.PRESSED, x, y)));
            assertTrue(picker.mouseDragged(mouse(picker, ReMouseEvent.Action.DRAGGED, x, y - spacing)));
            assertTrue(picker.mouseReleased(mouse(picker, ReMouseEvent.Action.RELEASED, x, y - spacing)));
            assertEquals("00:15:30", picker.clock());
            assertEquals("2028-02-29", picker.value().toLocalDate().toString());
            picker.pattern(ScheduleTimingGuide.DAILY);
            picker.tick();
            assertTrue(calendar.isVisible());
            assertTrue(calendar.getHeight() >= 127);
            assertTrue(calendar.interactiveAt(calendar.getX() + 10, calendar.getY() + 45));
            assertTrue(hour.getX() >= calendar.getX() + calendar.getWidth());
            assertEquals(calendar.getY() + calendar.getHeight(), hour.getY() + hour.getHeight());
            assertEquals(calendar.getY() + calendar.getHeight(), minute.getY() + minute.getHeight());
            assertEquals(calendar.getY() + calendar.getHeight(), second.getY() + second.getHeight());
            assertEquals("00:15:30", picker.clock());
        } finally {
            Config.animationsEnabled = animations;
        }
    }

    @Test
    void headerDropdownChangesTheCalendarWithoutClickingCoveredTimeWheels() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
        boolean animations = Config.animationsEnabled;
        try {
            Config.animationsEnabled = false;
            for (boolean managed : new boolean[]{false, true}) {
                ScheduleDatePicker picker = new ScheduleDatePicker(LocalDateTime.of(2028, 2, 29, 23, 15, 30), true);
                PopupWidget popup = new PopupWidget.Builder("Schedule").pos(0, 0).size(450, 360)
                        .setHeaderVisible(false).addRow("", picker).build();
                picker.onClick.run();
                popup.tick();
                DropDownWidget<?> type = (DropDownWidget<?>) picker.mountedWidgets.getFirst();
                double x = type.getX() + 10;
                ReMouseEvent open = mouse(popup, ReMouseEvent.Action.PRESSED, x, type.getY() + 10);
                assertTrue(managed ? popup.mouseClickedManaged(open) : popup.mouseClicked(open));
                type.tick();
                assertTrue(type.isExpanded());
                ReMouseEvent select = mouse(popup, ReMouseEvent.Action.PRESSED, x, type.getY() + type.getHeight() + 49);
                assertTrue(managed ? popup.mouseClickedManaged(select) : popup.mouseClicked(select));
                assertEquals("Year", type.getSelectedItem());
                assertEquals(CalendarWidget.Unit.YEAR, calendar(picker).unit());
                assertEquals("2028-02-29T23:15:30", picker.value().toString());
                assertFalse(type.isExpanded());
            }
        } finally {
            Config.animationsEnabled = animations;
        }
    }

    @Test
    void repeatSelectionRemainsAvailableWhenTheCalendarIsHidden() {
        ThemeManager.initBrowserDefaults();
        TextRenderer.ensureDefaultRenderer();
        boolean animations = Config.animationsEnabled;
        try {
            Config.animationsEnabled = false;
            ScheduleDatePicker picker = new ScheduleDatePicker(LocalDateTime.of(2028, 2, 29, 23, 15, 30), true);
            DropDownWidget<String> repeat = new DropDownWidget.Builder<>(List.of(ScheduleTimingGuide.DAILY, ScheduleTimingGuide.SECONDLY))
                    .selectedItem(ScheduleTimingGuide.DAILY).size(140, 20).onSelectionChanged(pattern -> {
                        picker.pattern(pattern);
                        picker.controls(!ScheduleTimingGuide.SECONDLY.equals(pattern));
                    }).build();
            picker.addMountedWidget(repeat);
            picker.pattern(ScheduleTimingGuide.DAILY);
            PopupWidget popup = new PopupWidget.Builder("Schedule").pos(0, 0).size(450, 360)
                    .setHeaderVisible(false).addRow("", picker).build();
            picker.onClick.run();
            popup.tick();
            repeat.setPosition(picker.getX() + picker.getWidth() - 145, picker.getY() + 5);
            double x = repeat.getX() + 10;
            assertTrue(popup.mouseClicked(mouse(popup, ReMouseEvent.Action.PRESSED, x, repeat.getY() + 10)));
            repeat.tick();
            assertTrue(popup.mouseClicked(mouse(popup, ReMouseEvent.Action.PRESSED, x, repeat.getY() + repeat.getHeight() + 21)));
            picker.tick();
            int collapsed = picker.getHeight();
            assertEquals(ScheduleTimingGuide.SECONDLY, repeat.getSelectedItem());
            assertTrue(popup.mouseClicked(mouse(popup, ReMouseEvent.Action.PRESSED, x, repeat.getY() + 10)));
            repeat.tick();
            assertTrue(popup.mouseClicked(mouse(popup, ReMouseEvent.Action.PRESSED, x, repeat.getY() + repeat.getHeight() + 7)));
            picker.tick();
            assertEquals(ScheduleTimingGuide.DAILY, repeat.getSelectedItem());
            assertTrue(picker.getHeight() > collapsed);
            assertEquals("2028-02-29T23:15:30", picker.value().toString());
        } finally {
            Config.animationsEnabled = animations;
        }
    }

    private CalendarWidget calendar(Widget widget) {
        if (widget instanceof CalendarWidget calendar) return calendar;
        if (widget instanceof WidgetComposite composite) {
            for (Widget child : composite.getChildWidgets()) {
                CalendarWidget found = calendar(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private ReMouseEvent mouse(Widget widget, ReMouseEvent.Action action, double x, double y) {
        return new ReMouseEvent(this, widget, 0, ReModifierState.none(), action, x, y, 0, 0, ReMouseButton.LEFT, 0, 1);
    }

    private ScrollSelectorWidget wheel(Widget widget, String hint) {
        if (widget instanceof ScrollSelectorWidget selector && selector.hint.equals(hint)) return selector;
        if (widget instanceof WidgetComposite composite) {
            for (Widget child : composite.getChildWidgets()) {
                ScrollSelectorWidget found = wheel(child, hint);
                if (found != null) return found;
            }
        }
        return null;
    }
}
