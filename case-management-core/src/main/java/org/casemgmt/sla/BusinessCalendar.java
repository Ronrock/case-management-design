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
        return fromJson("Calendar definition", definition);
    }

    @SuppressWarnings("unchecked")
    public static BusinessCalendar fromJson(String source, Map<String, Object> definition) {
        ZoneId zone = ZoneId.of((String) definition.getOrDefault("timezone", "UTC"));

        Map<DayOfWeek, List<Interval>> hours = new EnumMap<>(DayOfWeek.class);
        Map<String, Object> raw = (Map<String, Object>) definition.getOrDefault("workingHours", Map.of());
        raw.forEach((day, intervals) -> {
            List<Interval> sorted = ((List<Map<String, String>>) intervals).stream()
                    .map(i -> parseInterval(source, day, i))
                    .sorted(Comparator.comparing(Interval::from))
                    .toList();
            checkNoOverlaps(source, day, sorted);
            hours.put(parseDay(source, day), sorted);
        });

        Set<LocalDate> holidays = new HashSet<>();
        ((List<String>) definition.getOrDefault("holidays", List.of()))
                .forEach(d -> holidays.add(LocalDate.parse(d)));

        return new BusinessCalendar(zone, hours, holidays);
    }

    private static DayOfWeek parseDay(String source, String day) {
        try {
            return DayOfWeek.valueOf(day);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    source + " workingHours has an unknown day key '" + day
                            + "' — expected one of " + Arrays.toString(DayOfWeek.values()), e);
        }
    }

    private static Interval parseInterval(String source, String day, Map<String, String> raw) {
        LocalTime from;
        LocalTime to;
        try {
            from = LocalTime.parse(raw.get("from"));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    source + " workingHours." + day + " has a malformed 'from' time '"
                            + raw.get("from") + "' — expected HH:mm", e);
        }
        try {
            to = LocalTime.parse(raw.get("to"));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(
                    source + " workingHours." + day + " has a malformed 'to' time '"
                            + raw.get("to") + "' — expected HH:mm", e);
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                    source + " workingHours." + day + " has interval [" + from + ", " + to
                            + "] where 'from' is not before 'to' — overnight intervals that split across "
                            + "midnight are not supported by this calendar, and zero-length intervals are "
                            + "meaningless; fix the calendar document");
        }
        return new Interval(from, to);
    }

    private static void checkNoOverlaps(String source, String day, List<Interval> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            Interval previous = sorted.get(i - 1);
            Interval current = sorted.get(i);
            if (current.from().isBefore(previous.to())) {
                throw new IllegalArgumentException(
                        source + " workingHours." + day + " has overlapping intervals ["
                                + previous.from() + ", " + previous.to() + "] and [" + current.from() + ", "
                                + current.to() + "] — addDuration would count the overlap twice and "
                                + "compute a shorter working window than the calendar actually specifies; "
                                + "fix the calendar document. Abutting intervals that only touch (e.g. "
                                + "[09:00, 12:00] and [12:00, 17:00]) are fine and are not rejected.");
            }
        }
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

    /**
     * The inverse of {@link #addDuration}: total working time that falls between two instants,
     * skipping evenings/weekends/holidays exactly as {@code addDuration} does. Added for SLA
     * pause/resume (Task 21 fix round 1, S1/I4): resuming a paused clock needs "how much business
     * time was left when we paused" so it can re-add exactly that much from the resume instant —
     * a plain wall-clock {@code Duration.between} would shift the deadline by calendar time
     * including nights/weekends the clock was never meant to run through.
     *
     * <p>{@code Duration.ZERO} if {@code to} is not strictly after {@code from} — never negative,
     * so a caller can feed the result straight into {@link #addDuration} without an extra guard.
     * Walks the same day-by-day interval structure {@code addDuration} uses (see that method's
     * Javadoc for the 1825-iteration safety bound), just summing overlaps with {@code [cursor, to]}
     * instead of consuming a remaining budget.
     */
    public Duration workingDurationBetween(OffsetDateTime from, OffsetDateTime to) {
        if (!to.isAfter(from)) {
            return Duration.ZERO;
        }

        ZonedDateTime cursor = from.atZoneSameInstant(zone);
        ZonedDateTime end = to.atZoneSameInstant(zone);
        Duration total = Duration.ZERO;

        for (int day = 0; day < 1825 && cursor.isBefore(end); day++) {
            LocalDate date = cursor.toLocalDate();
            for (Interval interval : intervalsOn(date)) {
                ZonedDateTime open = ZonedDateTime.of(date, interval.from(), zone);
                ZonedDateTime close = ZonedDateTime.of(date, interval.to(), zone);

                ZonedDateTime start = cursor.isAfter(open) ? cursor : open;
                ZonedDateTime stop = end.isBefore(close) ? end : close;
                if (start.isBefore(stop)) {
                    total = total.plus(Duration.between(start, stop));
                }
            }
            cursor = ZonedDateTime.of(date.plusDays(1), LocalTime.MIN, zone);
        }
        return total;
    }

    private List<Interval> intervalsOn(LocalDate date) {
        if (holidays.contains(date)) {
            return List.of();
        }
        return workingHours.getOrDefault(date.getDayOfWeek(), List.of());
    }
}
