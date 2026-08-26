package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public record CombinedCaseDefinitionArchive(
        byte[] orchestrationZip, String contractJson, String presentationJson) {

    public static final int MAX_FILES = 100;
    public static final int MAX_DECOMPRESSED_BYTES = 25 * 1024 * 1024;

    private static final Set<String> JSON_ARTIFACTS = Set.of("contract.json", "presentation.json");

    public static CombinedCaseDefinitionArchive read(String key, byte[] archive) {
        Map<String, byte[]> entries = readEntries(key, archive);
        byte[] contract = entries.get("contract.json");
        byte[] presentation = entries.get("presentation.json");
        if (contract == null || presentation == null) {
            throw invalid(key, "Combined deployment requires contract.json and presentation.json");
        }
        Map<String, byte[]> orchestration = new LinkedHashMap<>();
        entries.forEach((path, content) -> {
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".bpmn") || lower.endsWith(".dmn")) {
                rejectUnsafeXml(key, path, content);
                orchestration.put(path, content);
            } else if (!JSON_ARTIFACTS.contains(path)) {
                throw invalid(key, "Unsupported combined-deployment path '" + path + "'");
            }
        });
        if (orchestration.keySet().stream().noneMatch(path ->
                path.toLowerCase(Locale.ROOT).endsWith(".bpmn"))) {
            throw invalid(key, "Combined deployment requires at least one BPMN resource");
        }
        return new CombinedCaseDefinitionArchive(zip(key, orchestration),
                new String(contract, StandardCharsets.UTF_8),
                new String(presentation, StandardCharsets.UTF_8));
    }

    private static Map<String, byte[]> readEntries(String key, byte[] archive) {
        if (archive == null || archive.length == 0) {
            throw invalid(key, "Combined deployment ZIP is empty");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = safePath(key, entry.getName());
                if (entries.size() >= MAX_FILES) {
                    throw invalid(key, "Combined deployment exceeds " + MAX_FILES + " files");
                }
                if (entries.containsKey(path)) {
                    throw invalid(key, "Combined deployment contains duplicate path '" + path + "'");
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        throw invalid(key, "Combined deployment exceeds decompressed-size limit");
                    }
                    content.write(buffer, 0, read);
                }
                entries.put(path, content.toByteArray());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw invalid(key, "Combined deployment is not a readable ZIP archive");
        }
        return entries;
    }

    private static String safePath(String key, String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.startsWith("\\")
                || raw.contains("\\")) {
            throw invalid(key, "Combined deployment contains unsafe ZIP path '" + raw + "'");
        }
        Path normalized = Path.of(raw).normalize();
        String path = normalized.toString().replace('\\', '/');
        if (path.equals("..") || path.startsWith("../") || !path.equals(raw)) {
            throw invalid(key, "Combined deployment contains unsafe ZIP path '" + raw + "'");
        }
        return path;
    }

    private static byte[] zip(String key, Map<String, byte[]> resources) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                for (var resource : resources.entrySet()) {
                    zip.putNextEntry(new ZipEntry(resource.getKey()));
                    zip.write(resource.getValue());
                    zip.closeEntry();
                }
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw invalid(key, "Could not prepare orchestration release");
        }
    }

    private static void rejectUnsafeXml(String key, String path, byte[] content) {
        String xml = new String(content, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (xml.contains("<!doctype") || xml.contains("<!entity")) {
            throw invalid(key, "XML resource '" + path + "' must not declare a DOCTYPE or entity");
        }
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }
}
