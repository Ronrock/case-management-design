package org.casemgmt.sla;

import java.time.*;
import java.util.*;

/**
 * Walks an ISO duration across working intervals, skipping evenings, weekends and
 * holidays. All arithmetic is done in the calendar's own zone so DST transitions
 * move wall-clock openings rather than shifting them by an hour.
 *
 * <p>Overnight intervals (where {@code to} is not strictly after {@code from}, e.g. a
 * night shift expressed as {@code 22:00-02:00}) are out of scope for this PoC and are
 * rejected at parse time rather than silently misinterpreted — supporting a shift that
 * splits across midnight is a deliberate non-goal here, not an oversight.
 *
 * <p>{@link #addDuration(OffsetDateTime, Duration)} treats {@link Duration#ZERO} as
 * identity: it returns {@code from} unchanged even when {@code from} falls outside
 * working hours, rather than "helpfully" advancing to the next opening. A caller that
 * asks to add nothing should get exactly what it started with.
 */
public class BusinessCalendar {

    public record Interval(LocalTime from, LocalTime to) {}

    private final ZoneId zone;
    private final Map<DayOfWeek, List<Interval>> workingHours;
    private final Set<LocalDate> holidays;

    private BusinessCalendar(ZoneId zone, Map<DayOfWeek, List<Interval>> workingHours,
                             Set<LocalDate> holidays) {
        this.zone = zone;
        this.workingHours = workingHours;
        this.holidays = holidays;
    }

    @SuppressWarnings("unchecked")
    public static BusinessCalendar fromJson(Map<String, Object> definition) {
        ZoneId zone = ZoneId.of((String) definition.getOrDefault("timezone", "UTC"));

        Map<DayOfWeek, List<Interval>> hours = new EnumMap<>(DayOfWeek.class);
        Map<String, Object> raw = (Map<String, Object>) definition.getOrDefault("workingHours", Map.of());
        raw.forEach((day, intervals) -> hours.put(parseDay(day),
                ((List<Map<String, String>>) intervals).stream()
                        .map(i -> parseInterval(day, i))
                        .sorted(Comparator.comparing(Interval::from))
                        .toList()));

        Set<LocalDate> holidays = new HashSet<>();
        ((List<String>) definition.getOrDefault("holidays", List.of()))
                .forEach(d -> holidays.add(LocalDate.parse(d)));

        return new BusinessCalendar(zone, hours, holidays);
    }

    private static DayOfWeek parseDay(String day) {
        try {
            return DayOfWeek.valueOf(day);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Calendar definition workingHours has an unknown day key '" + day
                            + "' — expected one of " + Arrays.toString(DayOfWeek.values()), e);
        }
    }

    private static Interval parseInterval(String day, Map<String, String> raw) {
        LocalTime from;
        LocalTime to;
        try {
            from = LocalTime.parse(raw.get("from"));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "Calendar definition workingHours." + day + " has a malformed 'from' time '"
                            + raw.get("from") + "' — expected HH:mm", e);
        }
        try {
            to = LocalTime.parse(raw.get("to"));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    "Calendar definition workingHours." + day + " has a malformed 'to' time '"
                            + raw.get("to") + "' — expected HH:mm", e);
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "Calendar definition workingHours." + day + " has interval [" + from + ", " + to
                            + "] where 'from' is not before 'to' — overnight intervals that split across "
                            + "midnight are not supported by this calendar, and zero-length intervals are "
                            + "meaningless; fix the calendar document");
        }
        return new Interval(from, to);
    }

    public boolean isWorking(OffsetDateTime at) {
        ZonedDateTime local = at.atZoneSameInstant(zone);
        return intervalsOn(local.toLocalDate()).stream()
                .anyMatch(i -> !local.toLocalTime().isBefore(i.from()) && local.toLocalTime().isBefore(i.to()));
    }

    public OffsetDateTime addDuration(OffsetDateTime from, Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException(
                    "addDuration does not support negative durations (got " + duration
                            + ") — an upstream subtraction bug must fail loudly here, not silently "
                            + "walk backwards past an opening");
        }
        if (duration.isZero()) {
            return from;
        }

        ZonedDateTime cursor = from.atZoneSameInstant(zone);
        Duration remaining = duration;

        // Guard: 1825 iterations (5 x 365 days, a few days short of 5 real calendar
        // years once leap years are counted) is far past any sane SLA and stops a
        // malformed calendar (no working hours at all) from looping forever.
        for (int day = 0; day < 1825; day++) {
            LocalDate date = cursor.toLocalDate();
            for (Interval interval : intervalsOn(date)) {
                ZonedDateTime open = ZonedDateTime.of(date, interval.from(), zone);
                ZonedDateTime close = ZonedDateTime.of(date, interval.to(), zone);

                ZonedDateTime start = cursor.isAfter(open) ? cursor : open;
                if (!start.isBefore(close)) {
                    continue;
                }
                Duration available = Duration.between(start, close);
                if (available.compareTo(remaining) >= 0) {
                    return start.plus(remaining).toOffsetDateTime();
                }
                remaining = remaining.minus(available);
            }
            cursor = ZonedDateTime.of(date.plusDays(1), LocalTime.MIN, zone);
        }
        throw new IllegalStateException(
                "Could not consume " + duration + " within 5 years — check the calendar definition");
    }

    private List<Interval> intervalsOn(LocalDate date) {
        if (holidays.contains(date)) {
            return List.of();
        }
        return workingHours.getOrDefault(date.getDayOfWeek(), List.of());
    }
}
