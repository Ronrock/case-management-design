package org.casemgmt.migration;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.Reader;
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

    @Test
    void closesThePayloadReaderAfterHashing() throws Exception {
        var reader = new CloseTrackingReader("{\"raw\":true}");

        EngineCommandPayloadDigestBackfill.hashUtf8(reader);

        assertThat(reader.closed).isTrue();
    }

    private static final class CloseTrackingReader extends Reader {
        private final StringReader delegate;
        private boolean closed;

        private CloseTrackingReader(String value) {
            delegate = new StringReader(value);
        }

        @Override public int read(char[] cbuf, int off, int len) throws java.io.IOException {
            return delegate.read(cbuf, off, len);
        }

        @Override public void close() {
            closed = true;
            delegate.close();
        }
    }
}
