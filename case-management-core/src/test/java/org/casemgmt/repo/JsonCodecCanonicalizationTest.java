package org.casemgmt.repo;

import org.casemgmt.engine.EngineCommand;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecCanonicalizationTest {

    @Test
    void canonicalizesObjectKeysAndEquivalentNumbersButPreservesArrayOrderAndNull() {
        var first = new LinkedHashMap<String, Object>();
        first.put("unicode", "café-東京");
        first.put("number", 1);
        first.put("nothing", null);
        first.put("array", List.of(1, 2));
        var second = new LinkedHashMap<String, Object>();
        second.put("array", List.of(1.0, 2.00));
        second.put("nothing", null);
        second.put("number", 1.0);
        second.put("unicode", "café-東京");

        assertThat(JsonCodec.canonicalJson(first)).isEqualTo(JsonCodec.canonicalJson(second));
        assertThat(JsonCodec.canonicalSha256(first)).isEqualTo(JsonCodec.canonicalSha256(second));
        assertThat(JsonCodec.canonicalJson(Map.of("array", List.of(2, 1))))
                .isNotEqualTo(JsonCodec.canonicalJson(first));
    }

    @Test
    void requestRejectsCallerSuppliedDigestThatDoesNotMatchCanonicalPayload() {
        assertThatThrownBy(() -> new EngineCommandRepository.ProductionCommandRequest(
                "command", "case", "tenant", "operation", "key",
                "0".repeat(64), EngineCommand.Type.START_PROCESS, Map.of("n", 1.0),
                "definition", null, null, null, OffsetDateTime.parse("2026-08-28T12:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match canonical payload");
    }

    @Test
    void requestCanonicalizesItsPersistedTimestampBeforeJdbcRoundTrip() {
        var request = new EngineCommandRepository.ProductionCommandRequest(
                "command", "case", "tenant", "operation", "key",
                EngineCommand.Type.START_PROCESS, Map.of("n", 1),
                "definition", null, null, null,
                OffsetDateTime.parse("2026-08-28T14:00:00.123456789+02:00"));

        assertThat(request.submittedAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-28T12:00:00.123456Z"));
    }
}
