package org.casemgmt.poc;

import org.casemgmt.repo.JsonCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "No case-type knowledge outside {@code case-management-poc-app}" is a headline design claim
 * this PoC exists to demonstrate. This is the only mechanism in the repository that can actually
 * falsify it.
 *
 * <p><b>Why bytecode analysis is not enough (Task 27, carried Task 25 Minor).</b>
 * {@link CrossModuleArchitectureTest} matches TYPE names and TYPE dependencies. The realistic way
 * case-type knowledge leaks into a generic service is not a class called
 * {@code ComplaintService} — it is one {@code if (case.caseDefKey().equals("complaint"))} branch,
 * or a form key, or a plan-item key, sitting in a string literal that no structural rule can see.
 * This test reads SOURCE TEXT, so it sees literals, and it also sees test fixtures, which the
 * ArchUnit import deliberately excludes.
 *
 * <p><b>The vocabulary is derived from the deployed case type, not typed out here.</b> It comes
 * from {@code definitions/complaint-v1.json} — the same document {@link PocBootstrap} deploys —
 * so a new plan item, form or process key added to that file is covered automatically the moment
 * it exists. Nothing has to be remembered.
 *
 * <p><b>The one documented limitation.</b> Only DISTINCTIVE tokens are enforceable: those with a
 * hyphen or an interior capital ({@code registerComplaint}, {@code closeForm},
 * {@code decision-letter}, {@code sla-complaint}), plus the case-definition key itself. The case
 * type also declares single lowercase words as plan-item keys — {@code intake},
 * {@code assessment}, {@code decision}, {@code closure}, {@code decided} — and those are ordinary
 * English that appears legitimately in prose and in generic domain code ("the retry-vs-dead-letter
 * decision"). Searching for them would produce false failures with no way to tell a leak from a
 * sentence, so they are excluded by a mechanical rule stated in {@link #isDistinctive(String)}
 * rather than by a hand-maintained ignore list. This is a real gap in coverage, stated here rather
 * than papered over: a leak of exactly the string {@code "intake"} would not be caught.
 */
class NoCaseTypeVocabularyTest {

    /** Every module whose sources must contain no case-type vocabulary at all. */
    private static final List<String> MODULES_THAT_MUST_STAY_GENERIC = List.of(
            "case-management-core",
            "case-management-rest",
            "case-management-engine-embedded",
            "case-management-engine-remote",
            "case-management-spring-boot-starter");

    /** Text file kinds worth scanning; everything else under {@code src} is skipped. */
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            ".java", ".xml", ".yaml", ".yml", ".json", ".sql", ".properties", ".bpmn", ".txt", ".md");

    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void theScanIsPointedAtARealRepositoryTree() {
        assertThat(REPOSITORY_ROOT.resolve("pom.xml"))
                .as("the scan resolves the repository root relative to this module's own directory; "
                        + "if that is wrong every assertion below passes over nothing")
                .exists();
        for (String module : MODULES_THAT_MUST_STAY_GENERIC) {
            assertThat(REPOSITORY_ROOT.resolve(module).resolve("src")).exists();
        }
    }

    @Test
    void theCaseTypeVocabularyIsDerivedFromTheDeployedDefinition() {
        Set<String> vocabulary = caseTypeVocabulary();

        // A parse that silently produced nothing, or a definition whose shape changed, would make
        // the leak scan below pass over an empty token set. Pin what the derivation must produce.
        assertThat(vocabulary)
                .contains("complaint", "registerComplaint", "closeComplaint", "registerForm",
                        "closeForm", "decision-letter", "sla-complaint")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void noModuleOutsideThePocApplicationMentionsTheCaseType() throws IOException {
        Set<String> vocabulary = caseTypeVocabulary();
        List<String> leaks = new ArrayList<>();

        for (String module : MODULES_THAT_MUST_STAY_GENERIC) {
            Path source = REPOSITORY_ROOT.resolve(module).resolve("src");
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).filter(this::isTextFile).toList()) {
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String lowercase = lines.get(i).toLowerCase(Locale.ROOT);
                        for (String token : vocabulary) {
                            if (lowercase.contains(token.toLowerCase(Locale.ROOT))) {
                                leaks.add(REPOSITORY_ROOT.relativize(file) + ":" + (i + 1)
                                        + " mentions '" + token + "'");
                            }
                        }
                    }
                }
            }
        }

        assertThat(leaks)
                .as("case-type vocabulary from definitions/complaint-v1.json must appear only in "
                        + "case-management-poc-app — main sources AND test fixtures")
                .isEmpty();
    }

    // ---- derivation ----

    /**
     * Reads the deployed case definition and returns the tokens that identify THIS case type:
     * its key and display name, its SLA policy id, its form keys, and each plan item's key, form
     * key and process-definition key — filtered to those {@link #isDistinctive(String) distinctive}
     * enough to search for.
     */
    @SuppressWarnings("unchecked")
    private Set<String> caseTypeVocabulary() {
        String document;
        try {
            document = Files.readString(Path.of("src/main/resources/definitions/complaint-v1.json"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("Could not read the deployed case definition", e);
        }
        Map<String, Object> definition = JsonCodec.toMap(document);

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(String.valueOf(definition.get("key")));
        candidates.add(String.valueOf(definition.get("name")));
        candidates.add(String.valueOf(definition.get("slaPolicyId")));
        ((Map<String, Object>) definition.getOrDefault("forms", Map.of())).keySet()
                .forEach(candidates::add);
        for (Map<String, Object> item :
                (List<Map<String, Object>>) definition.getOrDefault("planItems", List.of())) {
            for (String field : List.of("defKey", "formKey", "processDefinitionKey")) {
                if (item.get(field) != null) {
                    candidates.add(String.valueOf(item.get(field)));
                }
            }
        }

        Set<String> vocabulary = new LinkedHashSet<>();
        String key = String.valueOf(definition.get("key"));
        vocabulary.add(key);                                    // the case type's own key, always
        candidates.stream().filter(this::isDistinctive).forEach(vocabulary::add);
        return vocabulary;
    }

    /**
     * A token is searchable when it cannot plausibly occur as ordinary English: it carries an
     * interior capital (camelCase identifier) or a hyphen (kebab-case key). Single lowercase
     * words are not — see this class's Javadoc for why that limitation is accepted rather than
     * worked around.
     */
    private boolean isDistinctive(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return token.indexOf('-') >= 0
                || token.chars().skip(1).anyMatch(Character::isUpperCase);
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return TEXT_SUFFIXES.stream().anyMatch(name::endsWith);
    }
}
