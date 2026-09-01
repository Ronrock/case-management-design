package org.casemgmt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationSourceLinkTest {

    private static final List<String> REVIEWED_DOCUMENTS = List.of(
            "docs/declarative-case-model-architecture.md",
            "docs/functional-requirements-support-assessment.md",
            "docs/system-overview.md",
            "docs/bpmn-first-orchestration-proposal.md");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]*]\\(([^)]+)\\)");
    private static final Pattern LINE_ANCHOR = Pattern.compile("#L(\\d+)(?:-L(\\d+))?$");

    @Test
    void reviewed_documentation_links_to_existing_source_files_and_lines() throws IOException {
        Path repositoryRoot = repositoryRoot();
        List<String> failures = new ArrayList<>();

        for (String document : REVIEWED_DOCUMENTS) {
            Path documentPath = repositoryRoot.resolve(document);
            Matcher links = MARKDOWN_LINK.matcher(Files.readString(documentPath));
            while (links.find()) {
                validateSourceLink(repositoryRoot, documentPath, links.group(1), failures);
            }
        }

        assertTrue(failures.isEmpty(), () -> "Broken local source links:\n" + String.join("\n", failures));
    }

    @Test
    void reports_missing_repository_files_and_invalid_local_anchors(@TempDir Path temporaryRoot)
            throws IOException {
        Path document = temporaryRoot.resolve("docs/links.md");
        Files.createDirectories(document.getParent());
        Files.writeString(temporaryRoot.resolve("README.md"), "only line\n");
        Files.writeString(document, """
                [missing](../missing.md)
                [zero](../README.md#L0)
                [reversed](../README.md#L2-L1)
                [past-end](../README.md#L2)
                """);
        List<String> failures = new ArrayList<>();
        Matcher links = MARKDOWN_LINK.matcher(Files.readString(document));
        while (links.find()) {
            validateSourceLink(temporaryRoot, document, links.group(1), failures);
        }

        assertTrue(failures.size() == 4,
                () -> "Expected local-file and anchor failures but got: " + failures);
    }

    private static void validateSourceLink(Path repositoryRoot, Path documentPath, String link,
            List<String> failures) throws IOException {
        if (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("#")) {
            return;
        }

        String target = link;
        Matcher anchor = LINE_ANCHOR.matcher(target);
        Integer firstLine = null;
        Integer lastLine = null;
        if (anchor.find()) {
            firstLine = Integer.valueOf(anchor.group(1));
            lastLine = anchor.group(2) == null ? firstLine : Integer.valueOf(anchor.group(2));
            target = target.substring(0, anchor.start());
        }

        if (target.isBlank()) {
            return;
        }

        Path source = documentPath.getParent()
                .resolve(URLDecoder.decode(target, StandardCharsets.UTF_8)).normalize();
        String location = repositoryRoot.relativize(documentPath) + " -> " + link;
        if (!source.startsWith(repositoryRoot)) {
            failures.add(location + " (outside repository)");
            return;
        }
        if (!Files.isRegularFile(source)) {
            failures.add(location + " (missing source file)");
            return;
        }
        if (firstLine != null && firstLine < 1) {
            failures.add(location + " (line anchors start at 1)");
            return;
        }
        if (lastLine != null && lastLine < firstLine) {
            failures.add(location + " (reversed line range)");
            return;
        }
        if (lastLine != null && Files.readAllLines(source).size() < lastLine) {
            failures.add(location + " (missing line " + lastLine + ")");
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("docs"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Unable to locate repository root");
        }
        return current;
    }
}
