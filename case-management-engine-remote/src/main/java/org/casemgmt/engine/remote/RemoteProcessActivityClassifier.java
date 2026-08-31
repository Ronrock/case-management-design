package org.casemgmt.engine.remote;

import org.casemgmt.engine.EngineException;
import org.casemgmt.projection.ActivityObservation;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches stock engine BPMN XML and caches only the stage/milestone tag classification. */
public final class RemoteProcessActivityClassifier {

    public record Classification(ActivityObservation.Kind kind, String milestoneId,
                                 String slaTargetId) { }
    public record TaskMetadata(List<String> candidateGroups, String formKey,
                               String slaTargetId) { }
    private record ModelIndex(Map<String, Classification> activities,
                              Map<String, TaskMetadata> tasks) { }
    private static final String BPMN_NAMESPACE =
            "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String CASE_MANAGEMENT_NAMESPACE = "https://casemgmt.org/bpmn";
    private static final String OPERATON_NAMESPACE = "http://operaton.org/schema/1.0/bpmn";

    private final RestClient client;
    private final Map<String, ModelIndex> cache = new ConcurrentHashMap<>();

    public RemoteProcessActivityClassifier(RestClient client) {
        this.client = client;
    }

    public Optional<Classification> classify(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) return Optional.empty();
        return Optional.ofNullable(cache.computeIfAbsent(processDefinitionId, this::load)
                .activities().get(activityId));
    }

    public TaskMetadata taskMetadata(String processDefinitionId, String activityId) {
        if (processDefinitionId == null || activityId == null) {
            return new TaskMetadata(List.of(), null, null);
        }
        return cache.computeIfAbsent(processDefinitionId, this::load).tasks()
                .getOrDefault(activityId, new TaskMetadata(List.of(), null, null));
    }

    @SuppressWarnings("unchecked")
    private ModelIndex load(String processDefinitionId) {
        String path = "/process-definition/"
                + URLEncoder.encode(processDefinitionId, StandardCharsets.UTF_8) + "/xml";
        try {
            Map<String, Object> response = client.get().uri(path).retrieve().body(Map.class);
            Object raw = response == null ? null : response.get("bpmn20Xml");
            if (raw == null) throw new EngineException("Process definition XML response is empty");
            return parse(raw.toString());
        } catch (RestClientException e) {
            throw new EngineException("Could not load process definition XML "
                    + processDefinitionId + ": " + e.getMessage(), e);
        }
    }

    private static ModelIndex parse(String xml) {
        if (xml.toLowerCase(java.util.Locale.ROOT).contains("<!doctype")
                || xml.toLowerCase(java.util.Locale.ROOT).contains("<!entity")) {
            throw new EngineException("Remote process definition XML contains a forbidden entity");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            var document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            if (root == null || !"definitions".equals(root.getLocalName())
                    || !BPMN_NAMESPACE.equals(root.getNamespaceURI())) {
                throw new EngineException("Remote process definition XML root must be "
                        + "'definitions' in namespace " + BPMN_NAMESPACE);
            }
            Map<String, Classification> result = new java.util.LinkedHashMap<>();
            Map<String, TaskMetadata> taskMetadata = new java.util.LinkedHashMap<>();
            var elements = new ArrayDeque<Element>();
            elements.add(root);
            while (!elements.isEmpty()) {
                Element element = elements.removeFirst();
                var children = element.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element child) elements.addLast(child);
                }
                if (!BPMN_NAMESPACE.equals(element.getNamespaceURI())) continue;
                String id = element.getAttribute("id");
                if (id.isBlank()) continue;
                String milestone = element.getAttributeNS(CASE_MANAGEMENT_NAMESPACE, "milestoneId");
                String slaTargetId = extensionAttribute(element, CASE_MANAGEMENT_NAMESPACE,
                        "slaTargetId");
                if (!milestone.isBlank()) {
                    result.put(id, new Classification(ActivityObservation.Kind.MILESTONE, milestone,
                            slaTargetId));
                } else if ("subProcess".equals(element.getLocalName())
                        && "true".equalsIgnoreCase(element.getAttributeNS(
                                CASE_MANAGEMENT_NAMESPACE, "stage"))) {
                    result.put(id, new Classification(ActivityObservation.Kind.STAGE, null,
                            slaTargetId));
                }
                if ("userTask".equals(element.getLocalName())) {
                    String rawGroups = extensionAttribute(element, OPERATON_NAMESPACE,
                            "candidateGroups");
                    List<String> groups = rawGroups == null ? List.of()
                            : Arrays.stream(rawGroups.split(",")).map(String::trim)
                                    .filter(value -> !value.isBlank()).toList();
                    taskMetadata.put(id, new TaskMetadata(groups,
                            extensionAttribute(element, OPERATON_NAMESPACE, "formKey"),
                            slaTargetId));
                }
            }
            return new ModelIndex(Map.copyOf(result), Map.copyOf(taskMetadata));
        } catch (EngineException e) {
            throw e;
        } catch (Exception e) {
            throw new EngineException("Could not parse process definition XML: " + e.getMessage(), e);
        }
    }

    private static String extensionAttribute(Element element, String namespace, String localName) {
        return element.hasAttributeNS(namespace, localName)
                ? element.getAttributeNS(namespace, localName) : null;
    }
}
