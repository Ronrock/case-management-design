package org.casemgmt.release;

import org.casemgmt.error.InvalidCaseDefinitionException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Secure structural validation and index extraction for immutable BPMN/DMN resources. */
public final class BpmnReleaseValidator {

    public static final int MAX_FILES = 100;
    public static final int MAX_DECOMPRESSED_BYTES = 25 * 1024 * 1024;

    /** Engine-owned BPMN extensions. */
    public static final String OPERATON_NS = "http://operaton.org/schema/1.0/bpmn";
    /** Platform-owned BPMN extensions. */
    public static final String CASEMGMT_NS = "https://casemgmt.org/bpmn";

    /**
     * Extension attributes this platform reads, and the namespace that must carry each one.
     *
     * <p>A prefix is arbitrary in XML, so the URI is the identity. Matching on local name alone
     * meant {@code foo:formKey} was indexed as though the author had written
     * {@code operaton:formKey} — the model published cleanly and only diverged once a live case
     * reached the element.
     */
    private static final Map<String, String> VOCABULARY = Map.of(
            "formKey", OPERATON_NS,
            "candidateGroups", OPERATON_NS,
            "decisionRef", OPERATON_NS,
            "milestoneId", CASEMGMT_NS,
            "slaTargetId", CASEMGMT_NS);

    /** Retired spellings, mapped to the name that replaced them. */
    private static final Map<String, String> RETIRED = Map.of("slaRef", "slaTargetId");

    public record Index(Set<String> processIds, Set<String> formRefs,
                        Set<String> milestoneIds, Set<String> decisionIds,
                        Set<String> candidateGroups, Set<String> slaRefs) { }

    public static Index validate(String definitionKey, byte[] content, String mediaType) {
        List<Resource> resources = "application/zip".equals(mediaType)
                ? unzip(definitionKey, content)
                : List.of(new Resource(definitionKey + ".bpmn", content));
        Set<String> processIds = new LinkedHashSet<>();
        Set<String> formRefs = new LinkedHashSet<>();
        Set<String> milestones = new LinkedHashSet<>();
        Set<String> decisions = new LinkedHashSet<>();
        Set<String> calledElements = new LinkedHashSet<>();
        Set<String> decisionRefs = new LinkedHashSet<>();
        Set<String> candidateGroups = new LinkedHashSet<>();
        Set<String> slaRefs = new LinkedHashSet<>();
        int bpmnFiles = 0;
        for (Resource resource : resources) {
            Document document = parse(definitionKey, resource);
            if (resource.name.endsWith(".bpmn")) {
                bpmnFiles++;
                indexBpmn(definitionKey, document, processIds, formRefs, milestones,
                        calledElements, decisionRefs, candidateGroups, slaRefs);
            } else if (resource.name.endsWith(".dmn")) {
                indexDmn(definitionKey, document, decisions);
            }
        }
        if (bpmnFiles == 0) {
            throw invalid(definitionKey, "Orchestration release requires at least one .bpmn resource");
        }
        long roots = processIds.stream().filter(definitionKey::equals).count();
        if (roots != 1) {
            throw invalid(definitionKey, "Orchestration release requires exactly one root process "
                    + "whose id is the case-definition key '" + definitionKey + "'");
        }
        for (String called : calledElements) {
            if (!dynamic(called) && !processIds.contains(called)) {
                throw invalid(definitionKey, "Call activity references unbundled process '" + called + "'");
            }
        }
        for (String decision : decisionRefs) {
            if (!dynamic(decision) && !decisions.contains(decision)) {
                throw invalid(definitionKey, "Business rule task references unbundled decision '"
                        + decision + "'");
            }
        }
        return new Index(Set.copyOf(processIds), Set.copyOf(formRefs), Set.copyOf(milestones),
                Set.copyOf(decisions), Set.copyOf(candidateGroups), Set.copyOf(slaRefs));
    }

    private static List<Resource> unzip(String key, byte[] content) {
        List<Resource> result = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (result.size() >= MAX_FILES) throw invalid(key, "Orchestration ZIP has too many files");
                String name = safePath(key, entry.getName());
                if (!paths.add(name)) throw invalid(key, "Orchestration ZIP contains duplicate path '" + name + "'");
                if (!name.endsWith(".bpmn") && !name.endsWith(".dmn")) {
                    throw invalid(key, "Unsupported orchestration resource '" + name + "'");
                }
                byte[] bytes = zip.readAllBytes();
                total = Math.addExact(total, bytes.length);
                if (total > MAX_DECOMPRESSED_BYTES) {
                    throw invalid(key, "Orchestration ZIP exceeds decompressed-size limit");
                }
                result.add(new Resource(name, bytes));
            }
        } catch (InvalidCaseDefinitionException e) {
            throw e;
        } catch (Exception e) {
            throw invalid(key, "Invalid orchestration ZIP: " + e.getMessage());
        }
        return result;
    }

    private static String safePath(String key, String name) {
        if (name == null || name.isBlank() || name.contains("\\") || name.startsWith("/")) {
            throw invalid(key, "Orchestration ZIP contains unsafe path '" + name + "'");
        }
        String normalized = Path.of(name).normalize().toString();
        if (!normalized.equals(name) || normalized.startsWith("..")) {
            throw invalid(key, "Orchestration ZIP contains unsafe path '" + name + "'");
        }
        return name;
    }

    private static Document parse(String key, Resource resource) {
        String text = new String(resource.content, StandardCharsets.UTF_8);
        if (text.toLowerCase(Locale.ROOT).contains("<!doctype")
                || text.toLowerCase(Locale.ROOT).contains("<!entity")) {
            throw invalid(key, "XML resource '" + resource.name + "' declares a forbidden DOCTYPE/entity");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new ByteArrayInputStream(resource.content));
        } catch (Exception e) {
            throw invalid(key, "Invalid XML resource '" + resource.name + "': " + e.getMessage());
        }
    }

    private static void indexBpmn(String key, Document document, Set<String> processIds,
                                  Set<String> formRefs, Set<String> milestones,
                                  Set<String> calledElements, Set<String> decisionRefs,
                                  Set<String> candidateGroups, Set<String> slaRefs) {
        var nodes = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) continue;
            checkVocabularyNamespaces(key, element);
            String local = element.getLocalName();
            if ("process".equals(local)) addUnique(key, processIds, attribute(element, "id"), "process");
            if ("userTask".equals(local)) {
                addIfText(formRefs, extension(element, "formKey"));
                String groups = extension(element, "candidateGroups");
                if (groups != null && !dynamic(groups)) {
                    Arrays.stream(groups.split(",")).map(String::trim).filter(v -> !v.isBlank())
                            .forEach(candidateGroups::add);
                }
            }
            if ("callActivity".equals(local)) addIfText(calledElements, attribute(element, "calledElement"));
            if ("businessRuleTask".equals(local)) addIfText(decisionRefs, extension(element, "decisionRef"));
            String milestone = firstText(extension(element, "milestoneId"),
                    "milestone".equals(local) ? attribute(element, "id") : null);
            if (milestone != null && !milestones.add(milestone)) {
                throw invalid(key, "Duplicate milestone id '" + milestone + "'");
            }
            addIfText(slaRefs, extension(element, "slaTargetId"));
        }
    }

    private static void indexDmn(String key, Document document, Set<String> decisions) {
        var nodes = document.getElementsByTagNameNS("*", "decision");
        for (int i = 0; i < nodes.getLength(); i++) {
            addUnique(key, decisions, attribute((Element) nodes.item(i), "id"), "decision");
        }
    }

    /**
     * Reads a platform extension from the one namespace {@link #VOCABULARY} allows for it.
     * Anything in another namespace has already been rejected by
     * {@link #checkVocabularyNamespaces}, so a miss here means the author did not set it.
     */
    private static String extension(Element element, String localName) {
        String namespace = VOCABULARY.get(localName);
        return element.hasAttributeNS(namespace, localName)
                ? element.getAttributeNS(namespace, localName) : null;
    }

    /**
     * Fails a recognised extension carried in an unexpected namespace, and a retired spelling in
     * any namespace.
     *
     * <p>Silently skipping either is what let a wrong-prefix or stale model publish clean and
     * then behave differently at runtime, so the author gets the element id and the namespace
     * that was expected instead of no signal at all.
     */
    private static void checkVocabularyNamespaces(String key, Element element) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String namespace = attribute.getNamespaceURI();
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(namespace)) continue;
            String local = attribute.getLocalName();
            if (local == null) continue;
            String replacement = RETIRED.get(local);
            if (replacement != null) {
                throw invalid(key, "BPMN element '" + elementId(element) + "' uses retired attribute '"
                        + local + "'; use '" + replacement + "' in namespace "
                        + VOCABULARY.get(replacement));
            }
            String expected = VOCABULARY.get(local);
            if (expected != null && !expected.equals(namespace)) {
                throw invalid(key, "BPMN element '" + elementId(element) + "' declares '" + local
                        + "' in namespace " + (namespace == null ? "(none)" : namespace)
                        + "; expected " + expected);
            }
        }
    }

    private static String elementId(Element element) {
        String id = attribute(element, "id");
        return id == null || id.isBlank() ? element.getLocalName() : id;
    }

    private static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static void addUnique(String key, Set<String> values, String value, String kind) {
        if (value != null && !value.isBlank() && !values.add(value)) {
            throw invalid(key, "Duplicate " + kind + " id '" + value + "'");
        }
    }

    private static void addIfText(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value);
    }

    private static String firstText(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private static boolean dynamic(String value) {
        return value.contains("${") || value.contains("#{");
    }

    private static InvalidCaseDefinitionException invalid(String key, String message) {
        return new InvalidCaseDefinitionException(key, message);
    }

    private record Resource(String name, byte[] content) { }

    private BpmnReleaseValidator() { }
}
