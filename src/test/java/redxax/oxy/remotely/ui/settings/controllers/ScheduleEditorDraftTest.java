package redxax.oxy.remotely.ui.settings.controllers;

import org.junit.jupiter.api.Test;
import restudio.rebase.schedule.ServerScheduleModels;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleEditorDraftTest {
    @Test
    void convertsReadableLocalTimeOnlyAtTheWireBoundary() {
        assertEquals("2026-08-27T15:00:00Z", ScheduleEditorDraft.instant("2026-08-27 18:00", "Asia/Riyadh"));
        assertEquals("2026-08-27 18:00", ScheduleEditorDraft.local("2026-08-27T15:00:00Z", "Asia/Riyadh"));
        assertEquals("2026-08-27T15:00:00Z", ScheduleEditorDraft.instant("2026-08-27T18:00", "Asia/Riyadh"));
    }

    @Test
    void completeDraftRetainsEveryUnsavedFieldWithTaskChanges() {
        var task = new ServerScheduleModels.Task("task", 1, ServerScheduleModels.Action.COMMAND, "say hello", 7, true);
        var draft = new ScheduleEditorDraft("Nightly", false, true, "Recurring", ScheduleTimingGuide.WEEKLY,
                "2026-08-27 18:00", "23:45", 4, "19", "45 23 * * 4", "Asia/Riyadh", List.of(task));
        assertEquals("Nightly", draft.name());
        assertEquals("2026-08-27 18:00", draft.oneTime());
        assertEquals("23:45", draft.recurringTime());
        assertEquals("45 23 * * 4", draft.cron());
        assertEquals("say hello", draft.tasks().getFirst().payload());
    }
    @Test
    void annualLeapDayKeepsItsDateWhenOpenedInANonLeapYear() {
        var timing = new ServerScheduleModels.Timing(ServerScheduleModels.TimingType.CRON, "", "30 15 9 29 2 *", "Europe/Berlin");
        var schedule = new ServerScheduleModels.Schedule("annual", "Leap Day", true, timing, false, List.of(), "", "", "", "", false);
        var draft = ScheduleEditorDraft.create(schedule, List.of());
        LocalDateTime selected = draft.dateTime(LocalDate.of(2026, 9, 30));
        assertEquals(LocalDateTime.of(2028, 2, 29, 9, 15, 30), selected);
        assertEquals(timing.cron(), ScheduleTimingGuide.cron(draft.preset(), selected.toLocalTime().toString(), draft.weekDay(),
                Integer.toString(selected.getDayOfMonth()), selected.getMonthValue(), draft.cron(), true));
    }

    @Test
    void monthlyDayThirtyOneKeepsItsDayWhenOpenedInFebruary() {
        var timing = new ServerScheduleModels.Timing(ServerScheduleModels.TimingType.CRON, "", "15 9 31 * *", "UTC");
        var schedule = new ServerScheduleModels.Schedule("monthly", "Month End", true, timing, false, List.of(), "", "", "", "", false);
        var selected = ScheduleEditorDraft.create(schedule, List.of()).dateTime(LocalDate.of(2026, 2, 1));
        assertEquals(31, selected.getDayOfMonth());
        assertEquals("09:15", selected.toLocalTime().toString());
    }

    @Test
    void editingOneTimeScheduleKeepsItsSavedZoneAndInstant() {
        var timing = new ServerScheduleModels.Timing(ServerScheduleModels.TimingType.ONE_TIME, "2026-10-01T15:00:30Z", "", "Asia/Riyadh");
        var schedule = new ServerScheduleModels.Schedule("one", "Once", true, timing, false, List.of(), "", "", "", "", false);
        var draft = ScheduleEditorDraft.create(schedule, List.of());
        assertEquals("Asia/Riyadh", draft.zone());
        assertEquals("2026-10-01 18:00:30", draft.oneTime());
        assertEquals(timing.runAt(), ScheduleEditorDraft.instant(draft.oneTime(), draft.zone()));
    }

}
