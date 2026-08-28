package org.casemgmt.orchestration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentResourceManifestTest {

    @Test
    void zipContainerOrderAndMetadataDoNotChangeTheDeployedResourceIdentity() {
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("model/invoice.bpmn", bytes("approved-bpmn"));
        first.put("decision.dmn", bytes("approved-dmn"));
        Map<String, byte[]> second = new LinkedHashMap<>();
        second.put("decision.dmn", bytes("approved-dmn"));
        second.put("model/invoice.bpmn", bytes("approved-bpmn"));

        DeploymentResourceManifest left = DeploymentResourceManifest.fromArtifact(
                "invoice", zip(first, 1_000L), "application/zip");
        DeploymentResourceManifest right = DeploymentResourceManifest.fromArtifact(
                "invoice", zip(second, 2_000L), "application/zip");

        assertThat(left.matches(right)).isTrue();
    }

    @Test
    void zipSlipPathCannotEnterAnApprovedManifest() {
        assertThatThrownBy(() -> DeploymentResourceManifest.fromArtifact(
                "invoice", zip(Map.of("../invoice.bpmn", bytes("content")), 1_000L),
                "application/zip"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe path");
    }

    @Test
    void engineReadBackCannotExceedTheTotalByteLimit() {
        DeploymentResourceManifest.Builder manifest = DeploymentResourceManifest.builder();
        InputStream oversized = new InputStream() {
            private int remaining = DeploymentResourceManifest.MAX_TOTAL_BYTES + 1;

            @Override
            public int read(byte[] target, int offset, int length) {
                if (remaining == 0) return -1;
                int read = Math.min(remaining, length);
                remaining -= read;
                return read;
            }

            @Override
            public int read() {
                return remaining-- > 0 ? 0 : -1;
            }
        };

        assertThatThrownBy(() -> manifest.add("invoice.bpmn", oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds")
                .hasMessageContaining("bytes");
    }

    @Test
    void engineReadBackCannotExceedTheResourceCountLimit() throws Exception {
        DeploymentResourceManifest.Builder manifest = DeploymentResourceManifest.builder();
        for (int index = 0; index < DeploymentResourceManifest.MAX_RESOURCES; index++) {
            manifest.add("resource-%03d.bpmn".formatted(index),
                    new ByteArrayInputStream(new byte[0]));
        }

        assertThatThrownBy(() -> manifest.add("one-too-many.bpmn",
                new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resources");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> resources, long timestamp) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (var resource : resources.entrySet()) {
                    ZipEntry entry = new ZipEntry(resource.getKey());
                    entry.setTime(timestamp);
                    zip.putNextEntry(entry);
                    zip.write(resource.getValue());
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
