package org.casemgmt.sla;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessCalendarTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    private BusinessCalendar calendar() {
        Map<String, Object> day = Map.of("from", "09:00", "to", "17:00");
        return BusinessCalendar.fromJson(Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of(
                        "MONDAY", List.of(day), "TUESDAY", List.of(day), "WEDNESDAY", List.of(day),
                        "THURSDAY", List.of(day), "FRIDAY", List.of(day)),
                "holidays", List.of("2026-12-25")));
    }

    private OffsetDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, AMS).toOffsetDateTime();
    }

    @Test
    void addsHoursInsideOneWorkingDay() {
        // Thursday 2026-08-06, 10:00 + 4h = 14:00 same day
        assertThat(calendar().addDuration(at(2026, 8, 6, 10, 0), Duration.ofHours(4)))
                .isEqualTo(at(2026, 8, 6, 14, 0));
    }

    @Test
    void rollsOverIntoTheNextWorkingDay() {
        // Thursday 15:00 + 4h: 2h left today, 2h into Friday from 09:00 = Friday 11:00
        assertThat(calendar().addDuration(at(2026, 8, 6, 15, 0), Duration.ofHours(4)))
                .isEqualTo(at(2026, 8, 7, 11, 0));
    }

    @Test
    void skipsTheWeekend() {
        // Friday 16:00 + 2h: 1h left Friday, 1h into Monday from 09:00 = Monday 10:00
        assertThat(calendar().addDuration(at(2026, 8, 7, 16, 0), Duration.ofHours(2)))
                .isEqualTo(at(2026, 8, 10, 10, 0));
    }

    @Test
    void skipsHolidays() {
        // 2026-12-25 is a Friday and a holiday: Thursday 16:00 + 2h lands Monday 2026-12-28 10:00
        assertThat(calendar().addDuration(at(2026, 12, 24, 16, 0), Duration.ofHours(2)))
                .isEqualTo(at(2026, 12, 28, 10, 0));
    }

    @Test
    void startingOutsideWorkingHoursJumpsToTheNextOpening() {
        // Thursday 06:00 + 1h = Thursday 10:00 (clock starts at 09:00)
        assertThat(calendar().addDuration(at(2026, 8, 6, 6, 0), Duration.ofHours(1)))
                .isEqualTo(at(2026, 8, 6, 10, 0));
    }

    @Test
    void survivesTheDstTransition() {
        // Dutch DST ends 2026-10-25 (Sunday). Friday 2026-10-23 16:00 + 2h -> Monday 2026-10-26 10:00
        assertThat(calendar().addDuration(at(2026, 10, 23, 16, 0), Duration.ofHours(2)))
                .isEqualTo(at(2026, 10, 26, 10, 0));
    }

    @Test
    void reportsWorkingAndNonWorkingInstants() {
        assertThat(calendar().isWorking(at(2026, 8, 6, 10, 0))).isTrue();
        assertThat(calendar().isWorking(at(2026, 8, 6, 20, 0))).isFalse();
        assertThat(calendar().isWorking(at(2026, 8, 8, 10, 0))).isFalse();   // Saturday
        assertThat(calendar().isWorking(at(2026, 12, 25, 10, 0))).isFalse(); // holiday
    }

    @Test
    void rejectsAnOvernightInterval() {
        // "from" after "to" would silently mean a night shift if we let it through;
        // overnight intervals are out of scope for this PoC, so reject at parse time.
        Map<String, Object> overnight = Map.of("from", "22:00", "to", "02:00");
        Map<String, Object> definition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("MONDAY", List.of(overnight)));

        assertThatThrownBy(() -> BusinessCalendar.fromJson(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONDAY")
                .hasMessageContaining("22:00")
                .hasMessageContaining("02:00");
    }

    @Test
    void rejectsAZeroLengthInterval() {
        Map<String, Object> degenerate = Map.of("from", "09:00", "to", "09:00");
        Map<String, Object> definition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("TUESDAY", List.of(degenerate)));

        assertThatThrownBy(() -> BusinessCalendar.fromJson(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TUESDAY");
    }

    @Test
    void rejectsAnUnknownDayName() {
        Map<String, Object> day = Map.of("from", "09:00", "to", "17:00");
        Map<String, Object> definition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("MONDAYY", List.of(day)));

        assertThatThrownBy(() -> BusinessCalendar.fromJson(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONDAYY")
                .hasMessageContaining("workingHours");
    }

    @Test
    void rejectsAMalformedTime() {
        Map<String, Object> day = Map.of("from", "9:00", "to", "17:00");
        Map<String, Object> definition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("MONDAY", List.of(day)));

        assertThatThrownBy(() -> BusinessCalendar.fromJson(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MONDAY")
                .hasMessageContaining("9:00");
    }

    @Test
    void parseErrorsNameTheSourceDefinition() {
        Map<String, Object> day = Map.of("from", "9:00", "to", "17:00");
        Map<String, Object> definition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("MONDAY", List.of(day)));

        assertThatThrownBy(() -> BusinessCalendar.fromJson("Business calendar 'cal-bad'", definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Business calendar 'cal-bad'")
                .hasMessageContaining("MONDAY")
                .hasMessageContaining("9:00");
    }

    @Test
    void rejectsANegativeDuration() {
        assertThatThrownBy(() -> calendar().addDuration(at(2026, 8, 6, 6, 0), Duration.ofHours(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void zeroDurationReturnsFromUnchangedEvenOutsideWorkingHours() {
        // Identity: adding nothing must not "helpfully" jump to the next opening.
        OffsetDateTime outsideHours = at(2026, 8, 6, 20, 0);
        assertThat(calendar().addDuration(outsideHours, Duration.ZERO)).isEqualTo(outsideHours);

        OffsetDateTime insideHours = at(2026, 8, 6, 10, 0);
        assertThat(calendar().addDuration(insideHours, Duration.ZERO)).isEqualTo(insideHours);
    }

    @Test
    void rejectsOverlappingIntervalsOnTheSameDay() {
        // 09:00-13:00 and 11:00-17:00 overlap by 2h. Left unrejected, addDuration's
        // per-interval loop counts that overlap twice, deflating the true 8h window
        // (09:00-17:00) to a computed 10h and returning due dates that are too early.
        Map<String, Object> morning = Map.of("from", "09:00", "to", "13:00");
        Map<String, Object> afternoon = Map.of("from", "11:00", "to", "17:00");
        Map<String, Object> definition = Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("WEDNESDAY", List.of(morning, afternoon)));

        assertThatThrownBy(() -> BusinessCalendar.fromJson(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEDNESDAY")
                .hasMessageContaining("09:00")
                .hasMessageContaining("13:00")
                .hasMessageContaining("11:00")
                .hasMessageContaining("17:00");
    }

    @Test
    void allowsAbuttingIntervalsOnTheSameDay() {
        // 09:00-12:00 and 12:00-17:00 touch at noon but do not overlap: a legitimate
        // way to express a day with a nominal lunch boundary that isn't actually a
        // break. The half-open interval convention (from inclusive, to exclusive)
        // means these compose into one continuous 8h working window.
        Map<String, Object> morning = Map.of("from", "09:00", "to", "12:00");
        Map<String, Object> afternoon = Map.of("from", "12:00", "to", "17:00");
        BusinessCalendar abutting = BusinessCalendar.fromJson(Map.of(
                "timezone", "Europe/Amsterdam",
                "workingHours", Map.of("WEDNESDAY", List.of(morning, afternoon))));

        // Wednesday 2026-08-05, 09:00 + 5h crosses the noon boundary and must land at
        // 14:00, not be shortchanged by treating the boundary as a gap.
        assertThat(abutting.addDuration(at(2026, 8, 5, 9, 0), Duration.ofHours(5)))
                .isEqualTo(at(2026, 8, 5, 14, 0));

        // The full day is one continuous 8h window: 09:00 + 8h = 17:00.
        assertThat(abutting.addDuration(at(2026, 8, 5, 9, 0), Duration.ofHours(8)))
                .isEqualTo(at(2026, 8, 5, 17, 0));
    }

    @Test
    void workingDurationBetweenSumsAPartialSameDayWindow() {
        // Thursday 10:00 to Thursday 14:00: entirely inside one working day.
        assertThat(calendar().workingDurationBetween(at(2026, 8, 6, 10, 0), at(2026, 8, 6, 14, 0)))
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    void workingDurationBetweenSkipsTheWeekend() {
        // Same instants as skipsTheWeekend above, but asking for the inverse question:
        // Friday 16:00 to Monday 10:00 is only 1h(Fri 16-17) + 1h(Mon 09-10) = 2h of
        // business time, not the ~66 wall-clock hours between them.
        assertThat(calendar().workingDurationBetween(at(2026, 8, 7, 16, 0), at(2026, 8, 10, 10, 0)))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void workingDurationBetweenSkipsAHoliday() {
        // Same instants as skipsHolidays above: Thursday 16:00 to the following Monday
        // 10:00, with Friday 2026-12-25 a holiday in between. 1h(Thu 16-17) + 1h(Mon 09-10) = 2h.
        assertThat(calendar().workingDurationBetween(at(2026, 12, 24, 16, 0), at(2026, 12, 28, 10, 0)))
                .isEqualTo(Duration.ofHours(2));
    }

    @Test
    void workingDurationBetweenIsZeroWhenToIsNotAfterFrom() {
        OffsetDateTime instant = at(2026, 8, 6, 12, 0);
        assertThat(calendar().workingDurationBetween(instant, instant)).isEqualTo(Duration.ZERO);
        assertThat(calendar().workingDurationBetween(instant, at(2026, 8, 6, 9, 0))).isEqualTo(Duration.ZERO);
    }
}
