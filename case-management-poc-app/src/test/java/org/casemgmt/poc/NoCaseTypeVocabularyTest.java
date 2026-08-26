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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * <p><b>How plain lowercase tokens are handled.</b> Distinctive identifiers are searched in all
 * source text. The case-definition key is also searched as a quoted literal. Other plain words
 * such as {@code intake} or {@code decision} are not case-type knowledge by themselves and are
 * ignored; treating them as leaks makes generic BPMN/DMN vocabulary fail this architecture test.
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

    private static final Pattern DOUBLE_QUOTED_LITERAL =
            Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"", Pattern.DOTALL);

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
                        "closeForm", "decision-letter", "sla-complaint",
                        "intake", "assessment", "decision", "closure", "decided")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    void noModuleOutsideThePocApplicationMentionsTheCaseType() throws IOException {
        Set<String> vocabulary = caseTypeVocabulary();
        String caseTypeKey = String.valueOf(deployedDefinition().get("key"));
        List<String> leaks = new ArrayList<>();

        for (String module : MODULES_THAT_MUST_STAY_GENERIC) {
            Path source = REPOSITORY_ROOT.resolve(module).resolve("src");
            try (Stream<Path> files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).filter(this::isTextFile).toList()) {
                    String document = Files.readString(file, StandardCharsets.UTF_8);
                    List<String> lines = document.lines().toList();
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String lowercase = line.toLowerCase(Locale.ROOT);
                        Set<String> literalTokens = quotedLiteralTokens(line);
                        for (String token : vocabulary) {
                            if (mentionsToken(lowercase, literalTokens, token, caseTypeKey)) {
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
     * key and process-definition key.
     */
    @SuppressWarnings("unchecked")
    private Set<String> caseTypeVocabulary() {
        Map<String, Object> definition = deployedDefinition();

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
        candidates.stream()
                .filter(token -> token != null && !token.isBlank())
                .forEach(vocabulary::add);
        return vocabulary;
    }

    private Map<String, Object> deployedDefinition() {
        try {
            String document = Files.readString(
                    Path.of("src/main/resources/definitions/complaint-v1.json"),
                    StandardCharsets.UTF_8);
            return JsonCodec.toMap(document);
        } catch (IOException e) {
            throw new AssertionError("Could not read the deployed case definition", e);
        }
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

    private boolean mentionsToken(String lowercaseLine, Set<String> literalTokens, String token,
                                  String caseTypeKey) {
        String lowercaseToken = token.toLowerCase(Locale.ROOT);
        return isDistinctive(token)
                ? lowercaseLine.contains(lowercaseToken)
                : token.equalsIgnoreCase(caseTypeKey) && literalTokens.contains(lowercaseToken);
    }

    private Set<String> quotedLiteralTokens(String document) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = DOUBLE_QUOTED_LITERAL.matcher(document);
        while (matcher.find()) {
            String literal = matcher.group();
            tokens.add(literal.substring(1, literal.length() - 1).toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return TEXT_SUFFIXES.stream().anyMatch(name::endsWith);
    }
}
