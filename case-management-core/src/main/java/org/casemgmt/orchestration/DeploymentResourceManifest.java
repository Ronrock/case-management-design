package org.casemgmt.orchestration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Deterministic identity of the resource bytes stored by an orchestration deployment.
 *
 * <p>The ZIP file itself is not the identity: entry order, compression level and timestamps can
 * change without changing the deployed model. The manifest therefore hashes each decompressed
 * resource by its safe deployment name. Both artifact parsing and engine read-back use the same
 * resource-count and total-byte limits.
 */
public final class DeploymentResourceManifest {

    public static final int MAX_RESOURCES = 100;
    public static final int MAX_TOTAL_BYTES = 25 * 1024 * 1024;
    private static final int MAX_REPORTED_NAMES = 20;

    private final Map<String, String> sha256ByName;

    private DeploymentResourceManifest(Map<String, String> sha256ByName) {
        this.sha256ByName = Collections.unmodifiableMap(new TreeMap<>(sha256ByName));
    }

    public static DeploymentResourceManifest fromArtifact(
            String definitionKey, byte[] content, String mediaType) {
        if (definitionKey == null || definitionKey.isBlank()) {
            throw new IllegalArgumentException("Definition key must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("Deployment artifact must not be null");
        }
        Builder manifest = builder();
        try {
            if ("application/zip".equals(mediaType)) {
                try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (!entry.isDirectory()) {
                            manifest.add(entry.getName(), zip);
                        }
                        zip.closeEntry();
                    }
                }
            } else {
                manifest.add(definitionKey + ".bpmn", new ByteArrayInputStream(content));
            }
            return manifest.build();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid deployment resource manifest: "
                    + e.getMessage(), e);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean matches(DeploymentResourceManifest actual) {
        return actual != null && sha256ByName.equals(actual.sha256ByName);
    }

    /** Returns a deterministic, bounded explanation suitable for a deployment diagnostic. */
    public String differenceFrom(DeploymentResourceManifest actual) {
        if (actual == null) return "actual manifest was absent";
        TreeSet<String> missing = new TreeSet<>(sha256ByName.keySet());
        missing.removeAll(actual.sha256ByName.keySet());
        TreeSet<String> added = new TreeSet<>(actual.sha256ByName.keySet());
        added.removeAll(sha256ByName.keySet());
        TreeSet<String> changed = new TreeSet<>(sha256ByName.keySet());
        changed.retainAll(actual.sha256ByName.keySet());
        changed.removeIf(name -> sha256ByName.get(name).equals(actual.sha256ByName.get(name)));

        List<String> parts = new ArrayList<>(3);
        if (!missing.isEmpty()) parts.add("missing " + summarize(missing));
        if (!added.isEmpty()) parts.add("added " + summarize(added));
        if (!changed.isEmpty()) parts.add("changed " + summarize(changed));
        return parts.isEmpty() ? "no difference" : String.join("; ", parts);
    }

    private static String summarize(TreeSet<String> names) {
        List<String> retained = names.stream().limit(MAX_REPORTED_NAMES).toList();
        return names.size() <= MAX_REPORTED_NAMES
                ? retained.toString()
                : retained + " and " + (names.size() - MAX_REPORTED_NAMES) + " more";
    }

    public static final class Builder {
        private final Map<String, String> sha256ByName = new TreeMap<>();
        private long totalBytes;

        /** Reads one resource without closing the caller-owned stream. */
        public Builder add(String rawName, InputStream content) throws IOException {
            String name = safeName(rawName);
            if (sha256ByName.containsKey(name)) {
                throw new IllegalArgumentException(
                        "Deployment resource manifest contains duplicate path '" + name + "'");
            }
            if (sha256ByName.size() >= MAX_RESOURCES) {
                throw new IllegalArgumentException(
                        "Deployment resource manifest exceeds " + MAX_RESOURCES + " resources");
            }
            if (content == null) {
                throw new IllegalArgumentException(
                        "Deployment resource '" + name + "' has no content");
            }

            MessageDigest digest = sha256();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw new IllegalArgumentException(
                            "Deployment resource manifest exceeds " + MAX_TOTAL_BYTES + " bytes");
                }
                digest.update(buffer, 0, read);
            }
            sha256ByName.put(name, HexFormat.of().formatHex(digest.digest()));
            return this;
        }

        public DeploymentResourceManifest build() {
            return new DeploymentResourceManifest(sha256ByName);
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank() || name.contains("\\") || name.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Deployment resource manifest contains unsafe path '" + name + "'");
        }
        try {
            String normalized = Path.of(name).normalize().toString();
            if (!normalized.equals(name) || normalized.startsWith("..")) {
                throw new IllegalArgumentException(
                        "Deployment resource manifest contains unsafe path '" + name + "'");
            }
            return name;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "Deployment resource manifest contains unsafe path '" + name + "'", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }
}
