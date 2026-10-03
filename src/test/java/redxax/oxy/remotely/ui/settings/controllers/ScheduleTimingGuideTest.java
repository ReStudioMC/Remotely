package redxax.oxy.remotely.ui.settings.controllers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ScheduleTimingGuideTest {
    @Test
    void createsGuidedCronWithoutCronKnowledge() {
        assertEquals("30 4 * * *", ScheduleTimingGuide.cron(ScheduleTimingGuide.DAILY, "04:30", 1, "1", ""));
        assertEquals("15 9 * * 1-5", ScheduleTimingGuide.cron(ScheduleTimingGuide.WEEKDAYS, "09:15", 1, "1", ""));
        assertEquals("0 8 12 * *", ScheduleTimingGuide.cron(ScheduleTimingGuide.MONTHLY, "08:00", 1, "12", ""));
    }

    @Test
    void guidedAnnualAndSecondSchedulesRoundTripWithoutChangingTheirTiming() {
        assertEquals("15 9 29 2 *", ScheduleTimingGuide.cron(ScheduleTimingGuide.YEARLY, "09:15", 1, "29", 2, "", true));
        assertEquals(ScheduleTimingGuide.YEARLY, ScheduleTimingGuide.preset("15 9 29 2 *"));
        assertEquals("30 15 9 * * 1", ScheduleTimingGuide.cron(ScheduleTimingGuide.WEEKLY, "09:15:30", 1, "1", 1, "", true));
        assertEquals(ScheduleTimingGuide.WEEKLY, ScheduleTimingGuide.preset("30 15 9 * * 1"));
        assertEquals("09:15:30", ScheduleTimingGuide.time("30 15 9 * * 1"));
        assertEquals("* * * * * *", ScheduleTimingGuide.cron(ScheduleTimingGuide.SECONDLY, "00:00", 0, "1", 1, "", true));
        assertEquals("* * * * *", ScheduleTimingGuide.cron(ScheduleTimingGuide.MINUTELY, "00:00", 0, "1", 1, "", true));
    }

    @Test
    void preservesAndValidatesAdvancedCron() {
        assertEquals(ScheduleTimingGuide.ADVANCED, ScheduleTimingGuide.preset("*/10 * * * *"));
        assertEquals(ScheduleTimingGuide.ADVANCED, ScheduleTimingGuide.preset("15 9 31 4 *"));
        assertEquals("*/10 * * * *", ScheduleTimingGuide.cron(ScheduleTimingGuide.ADVANCED, "04:00", 1, "1", "*/10 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleTimingGuide.cron(ScheduleTimingGuide.ADVANCED, "04:00", 1, "1", "bad"));
    }
}
