package redxax.oxy.remotely.ui.settings.controllers;

import java.time.Month;
import java.util.List;
import java.util.Arrays;

final class ScheduleTimingGuide {
    static final String SECONDLY = "Every Second";
    static final String MINUTELY = "Every Minute";
    static final String YEARLY = "Every Year";
    static final String HOURLY = "Every Hour";
    static final String DAILY = "Every Day";
    static final String WEEKDAYS = "Weekdays";
    static final String WEEKLY = "Every Week";
    static final String MONTHLY = "Every Month";
    static final String ADVANCED = "Advanced Cron";
    static final List<String> PRESETS = List.of(SECONDLY, MINUTELY, HOURLY, DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY, ADVANCED);
    static final List<String> WEEKDAYS_LIST = List.of("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday");

    private ScheduleTimingGuide() {
    }

    static String preset(String cron) {
        String value = normalize(cron);
        String[] fields = value.split(" ");
        if ("* * * * * *".equals(value)) return SECONDLY;
        if (fields.length == 6 && number(fields[0], 0, 59)) fields = Arrays.copyOfRange(fields, 1, 6);
        if (fields.length != 5) return ADVANCED;
        if ("* * * * *".equals(String.join(" ", fields))) return MINUTELY;
        if (number(fields[0], 0, 59) && "*".equals(fields[1]) && "*".equals(fields[2]) && "*".equals(fields[3]) && "*".equals(fields[4])) return HOURLY;
        if (number(fields[0], 0, 59) && number(fields[1], 0, 23) && "*".equals(fields[2]) && "*".equals(fields[3])) {
            if ("*".equals(fields[4])) return DAILY;
            if ("1-5".equals(fields[4])) return WEEKDAYS;
            if (number(fields[4], 0, 6)) return WEEKLY;
        }
        if (number(fields[0], 0, 59) && number(fields[1], 0, 23) && number(fields[2], 1, 31) && "*".equals(fields[3]) && "*".equals(fields[4])) return MONTHLY;
        if (number(fields[0], 0, 59) && number(fields[1], 0, 23) && number(fields[2], 1, 31)
                && number(fields[3], 1, 12) && "*".equals(fields[4])
                && Integer.parseInt(fields[2]) <= Month.of(Integer.parseInt(fields[3])).maxLength()) return YEARLY;
        return ADVANCED;
    }

    private static String[] fields(String cron) {
        String[] fields = normalize(cron).split(" ");
        return fields.length == 6 ? Arrays.copyOfRange(fields, 1, 6) : fields;
    }

    static String time(String cron) {
        String[] fields = fields(cron);
        String[] raw = normalize(cron).split(" ");
        String second = raw.length == 6 && number(raw[0], 0, 59) ? two(Integer.parseInt(raw[0])) : "00";
        if (fields.length == 5 && "*".equals(fields[0]) && "*".equals(fields[1])) return "00:00" + (raw.length == 6 ? ":" + second : "");
        return fields.length == 5 && number(fields[0], 0, 59) && (number(fields[1], 0, 23) || "*".equals(fields[1]))
                ? two("*".equals(fields[1]) ? 0 : Integer.parseInt(fields[1])) + ":" + two(Integer.parseInt(fields[0]))
                + (raw.length == 6 ? ":" + second : "") : "04:00";
    }

    static int weekDay(String cron) {
        String[] fields = fields(cron);
        return fields.length == 5 && number(fields[4], 0, 6) ? Integer.parseInt(fields[4]) : 1;
    }

    static String monthDay(String cron) {
        String[] fields = fields(cron);
        return fields.length == 5 && number(fields[2], 1, 31) ? fields[2] : "1";
    }

    static int month(String cron) {
        String[] fields = fields(cron);
        return fields.length == 5 && number(fields[3], 1, 12) ? Integer.parseInt(fields[3]) : 1;
    }

    static String cron(String preset, String time, int weekDay, String monthDay, String advanced) {
        return cron(preset, time, weekDay, monthDay, 1, advanced, false);
    }

    static String cron(String preset, String time, int weekDay, String monthDay, int month, String advanced, boolean seconds) {
        if (ADVANCED.equals(preset)) return validateAdvanced(advanced);
        if (SECONDLY.equals(preset)) return "* * * * * *";
        if (MINUTELY.equals(preset)) return seconds && clock(time)[2] > 0 ? clock(time)[2] + " * * * * *" : "* * * * *";
        int[] clock = clock(time);
        String minute = String.valueOf(clock[1]);
        String hour = String.valueOf(clock[0]);
        String result = switch (preset) {
            case HOURLY -> minute + " * * * *";
            case DAILY -> minute + " " + hour + " * * *";
            case WEEKDAYS -> minute + " " + hour + " * * 1-5";
            case WEEKLY -> minute + " " + hour + " * * " + Math.clamp(weekDay, 0, 6);
            case MONTHLY -> minute + " " + hour + " " + integer(monthDay, 1, 31, "Day Of Month") + " * *";
            case YEARLY -> minute + " " + hour + " " + integer(monthDay, 1, 31, "Day Of Month") + " " + month + " *";
            default -> throw new IllegalArgumentException("Choose A Recurring Schedule");
        };
        return seconds && clock[2] > 0 ? clock[2] + " " + result : result;
    }

    static String summary(String preset, String time, int weekDay, String monthDay, String advanced, String zone) {
        String location = zone == null || zone.isBlank() ? "UTC" : zone.trim();
        return switch (preset) {
            case SECONDLY -> "Runs Every Second In " + location;
            case MINUTELY -> "Runs Every Minute At Second " + clock(time)[2] + " In " + location;
            case HOURLY -> "Runs Every Hour At Minute " + clock(time)[1] + " In " + location;
            case YEARLY -> "Runs Once A Year At " + normalizedTime(time) + " In " + location;
            case DAILY -> "Runs Every Day At " + normalizedTime(time) + " In " + location;
            case WEEKDAYS -> "Runs Monday Through Friday At " + normalizedTime(time) + " In " + location;
            case WEEKLY -> "Runs Every " + WEEKDAYS_LIST.get(Math.clamp(weekDay, 0, 6)) + " At " + normalizedTime(time) + " In " + location;
            case MONTHLY -> "Runs On Day " + integer(monthDay, 1, 31, "Day Of Month") + " At " + normalizedTime(time) + " In " + location;
            case ADVANCED -> "Advanced Cron: " + validateAdvanced(advanced) + " In " + location;
            default -> "Choose A Recurring Schedule";
        };
    }

    private static String validateAdvanced(String cron) {
        String value = normalize(cron);
        String[] fields = value.split(" ");
        if (fields.length != 5 && fields.length != 6) throw new IllegalArgumentException("Advanced Cron Must Have Five Or Six Fields");
        for (String field : fields) if (!field.matches("[0-9*/,\\-]+")) throw new IllegalArgumentException("Advanced Cron Contains An Invalid Field");
        int offset = fields.length - 5;
        if (!"*".equals(fields[offset + 2]) && !"*".equals(fields[offset + 4])) throw new IllegalArgumentException("Advanced Cron Cannot Restrict Both Day Fields");
        return value;
    }

    private static int[] clock(String time) {
        String[] fields = time == null ? new String[0] : time.trim().split(":");
        if (fields.length != 2 && fields.length != 3) throw new IllegalArgumentException("Time Must Use HH:MM Or HH:MM:SS");
        return new int[]{integer(fields[0], 0, 23, "Hour"), integer(fields[1], 0, 59, "Minute"), fields.length == 3 ? integer(fields[2], 0, 59, "Second") : 0};
    }

    private static String normalizedTime(String time) {
        int[] value = clock(time);
        return two(value[0]) + ":" + two(value[1]) + (value[2] == 0 ? "" : ":" + two(value[2]));
    }

    private static int integer(String value, int minimum, int maximum, String name) {
        try {
            int result = Integer.parseInt(value == null ? "" : value.trim());
            if (result < minimum || result > maximum) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " Must Be Between " + minimum + " And " + maximum);
        }
    }

    private static boolean number(String value, int minimum, int maximum) {
        try {
            int result = Integer.parseInt(value);
            return result >= minimum && result <= maximum;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }
}
