package org.casemgmt.repo;

import org.casemgmt.OracleTestBase;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlaCalendarRevisionRepositoryTest extends OracleTestBase {

    @Test
    void calendarRevisionIdentityIncludesTenantAndRejectsAChangedDefinition() {
        SlaRepository calendars = new SlaRepository(jdbc());
        Map<String, Object> tenantA = new LinkedHashMap<>();
        tenantA.put("timezone", "UTC");
        tenantA.put("holidays", java.util.List.of());
        Map<String, Object> sameTenantAWithDifferentKeyOrder = new LinkedHashMap<>();
        sameTenantAWithDifferentKeyOrder.put("holidays", java.util.List.of());
        sameTenantAWithDifferentKeyOrder.put("timezone", "UTC");
        Map<String, Object> tenantB = Map.of("timezone", "Europe/Amsterdam");

        calendars.insertCalendarRevision("tenant-a", "support", 1, "Support", tenantA);
        calendars.insertCalendarRevision("tenant-a", "support", 1, "Support renamed",
                sameTenantAWithDifferentKeyOrder);
        calendars.insertCalendarRevision("tenant-b", "support", 1, "Support", tenantB);

        assertThat(calendars.require("tenant-a", "support", 1))
                .satisfies(revision -> {
                    assertThat(revision.tenantId()).isEqualTo("tenant-a");
                    assertThat(revision.calendarId()).isEqualTo("support");
                    assertThat(revision.revision()).isEqualTo(1);
                    assertThat(revision.definition()).isEqualTo(tenantA);
                });
        assertThat(calendars.require("tenant-b", "support", 1).definition())
                .isEqualTo(tenantB);
        assertThat(jdbc().sql("SELECT COUNT(*) FROM CM_BUSINESS_CALENDAR_REVISION")
                .query(Integer.class).single()).isEqualTo(2);

        assertThatThrownBy(() -> calendars.insertCalendarRevision(
                "tenant-a", "support", 1, "Support", Map.of("timezone", "Asia/Tokyo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-a")
                .hasMessageContaining("support")
                .hasMessageContaining("revision 1");
    }
}
