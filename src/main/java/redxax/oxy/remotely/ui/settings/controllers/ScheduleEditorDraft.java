package redxax.oxy.remotely.ui.settings.controllers;

import restudio.rebase.schedule.ServerScheduleModels;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

record ScheduleEditorDraft(String name, boolean enabled, boolean onlyOnline, String timing, String preset,
                           String oneTime, String recurringTime, int weekDay, String monthDay, String cron,
                           String zone, List<ServerScheduleModels.Task> tasks) {
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    ScheduleEditorDraft {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    static ScheduleEditorDraft create(ServerScheduleModels.Schedule schedule, List<ServerScheduleModels.Task> tasks) {
        boolean recurring = schedule != null && schedule.timing().type() == ServerScheduleModels.TimingType.CRON;
        String zone = schedule != null && !schedule.timing().zoneId().isBlank() ? schedule.timing().zoneId() : ZoneId.systemDefault().getId();
        String cron = recurring ? schedule.timing().cron() : "0 4 * * *";
        String instant = schedule == null || recurring ? "" : local(schedule.timing().runAt(), zone);
        return new ScheduleEditorDraft(schedule == null ? "" : schedule.name(), schedule == null || schedule.enabled(),
                schedule != null && schedule.onlyWhenOnline(), recurring ? "Recurring" : "One Time", ScheduleTimingGuide.preset(cron),
                instant, ScheduleTimingGuide.time(cron), ScheduleTimingGuide.weekDay(cron), ScheduleTimingGuide.monthDay(cron), cron,
                zone, tasks);
    }

    LocalDateTime dateTime(LocalDate today) {
        if (!oneTime.isBlank()) return LocalDateTime.parse(oneTime, LOCAL);
        LocalDate date = today;
        if ("Recurring".equals(timing)) {
            if (ScheduleTimingGuide.YEARLY.equals(preset)) {
                MonthDay day = MonthDay.of(ScheduleTimingGuide.month(cron), Integer.parseInt(monthDay));
                int year = today.getYear();
                while (!day.isValidYear(year)) year++;
                date = day.atYear(year);
            } else if (ScheduleTimingGuide.MONTHLY.equals(preset)) date = LocalDate.of(2000, 1, Integer.parseInt(monthDay));
            else if (ScheduleTimingGuide.WEEKLY.equals(preset)) date = date.minusDays(date.getDayOfWeek().getValue() % 7).plusDays(weekDay);
            return date.atTime(LocalTime.parse(recurringTime));
        }
        return today.plusDays(1).atTime(4, 0);
    }

    static String instant(String local, String zone) {
        String value = local == null ? "" : local.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(value, LOCAL).atZone(ZoneId.of(zone)).toInstant().toString();
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Use A Local Time Like 2026-08-27 18:00");
        }
    }

    static String local(String instant, String zone) {
        if (instant == null || instant.isBlank()) return "";
        var value = Instant.parse(instant).atZone(ZoneId.of(zone));
        return (value.getSecond() == 0 ? MINUTES : SECONDS).format(value);
    }

    static String display(String instant, String zone) {
        if (instant == null || instant.isBlank()) return "Not Scheduled";
        return local(instant, zone) + " " + zone;
    }
}
