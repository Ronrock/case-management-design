package org.casemgmt.migration;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class EngineCommandPayloadDigestBackfillTest {

    @Test
    void hashesTheCompleteUnicodeCharacterStreamAsUtf8() throws Exception {
        String retainedPayload = "{\"raw\":\"" + "é😀".repeat(40_000) + "\"}";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(retainedPayload.getBytes(StandardCharsets.UTF_8)));

        assertThat(EngineCommandPayloadDigestBackfill.hashUtf8(
                new StringReader(retainedPayload))).isEqualTo(expected);
    }
}
