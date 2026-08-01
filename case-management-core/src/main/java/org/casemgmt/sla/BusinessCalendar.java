package org.casemgmt.sla;

import java.time.*;
import java.util.*;

/**
 * Walks an ISO duration across working intervals, skipping evenings, weekends and
 * holidays. All arithmetic is done in the calendar's own zone so DST transitions
 * move wall-clock openings rather than shifting them by an hour.
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
        raw.forEach((day, intervals) -> hours.put(DayOfWeek.valueOf(day),
                ((List<Map<String, String>>) intervals).stream()
                        .map(i -> new Interval(LocalTime.parse(i.get("from")), LocalTime.parse(i.get("to"))))
                        .sorted(Comparator.comparing(Interval::from))
                        .toList()));

        Set<LocalDate> holidays = new HashSet<>();
        ((List<String>) definition.getOrDefault("holidays", List.of()))
                .forEach(d -> holidays.add(LocalDate.parse(d)));

        return new BusinessCalendar(zone, hours, holidays);
    }

    public boolean isWorking(OffsetDateTime at) {
        ZonedDateTime local = at.atZoneSameInstant(zone);
        return intervalsOn(local.toLocalDate()).stream()
                .anyMatch(i -> !local.toLocalTime().isBefore(i.from()) && local.toLocalTime().isBefore(i.to()));
    }

    public OffsetDateTime addDuration(OffsetDateTime from, Duration duration) {
        ZonedDateTime cursor = from.atZoneSameInstant(zone);
        Duration remaining = duration;

        // Guard: 5 working years is far past any sane SLA and stops a malformed
        // calendar (no working hours at all) from looping forever.
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
