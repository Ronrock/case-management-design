package org.casemgmt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

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

        if (!target.contains("/src/") || target.isBlank()) {
            return;
        }

        Path source = documentPath.getParent().resolve(target).normalize();
        String location = repositoryRoot.relativize(documentPath) + " -> " + link;
        if (!Files.isRegularFile(source)) {
            failures.add(location + " (missing source file)");
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
