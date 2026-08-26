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
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Fetches stock engine BPMN XML and caches only the stage/milestone tag classification. */
public final class RemoteProcessActivityClassifier {

    public record Classification(ActivityObservation.Kind kind, String milestoneId) { }
    public record TaskMetadata(List<String> candidateGroups, String formKey) { }
    private record ModelIndex(Map<String, Classification> activities,
                              Map<String, TaskMetadata> tasks) { }
    private static final String NAMESPACE = "https://casemgmt.org/bpmn";

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
        if (processDefinitionId == null || activityId == null) return new TaskMetadata(List.of(), null);
        return cache.computeIfAbsent(processDefinitionId, this::load).tasks()
                .getOrDefault(activityId, new TaskMetadata(List.of(), null));
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
            Map<String, Classification> result = new java.util.LinkedHashMap<>();
            Map<String, TaskMetadata> taskMetadata = new java.util.LinkedHashMap<>();
            var nodes = document.getElementsByTagNameNS("*", "*");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element element)) continue;
                String id = element.getAttribute("id");
                if (id.isBlank()) continue;
                String milestone = element.getAttributeNS(NAMESPACE, "milestoneId");
                if (!milestone.isBlank()) {
                    result.put(id, new Classification(ActivityObservation.Kind.MILESTONE, milestone));
                } else if ("subProcess".equals(element.getLocalName())
                        && "true".equalsIgnoreCase(element.getAttributeNS(NAMESPACE, "stage"))) {
                    result.put(id, new Classification(ActivityObservation.Kind.STAGE, null));
                }
                if ("userTask".equals(element.getLocalName())) {
                    String rawGroups = attributeByLocalName(element, "candidateGroups");
                    List<String> groups = rawGroups == null ? List.of()
                            : Arrays.stream(rawGroups.split(",")).map(String::trim)
                                    .filter(value -> !value.isBlank()).toList();
                    taskMetadata.put(id, new TaskMetadata(groups,
                            attributeByLocalName(element, "formKey")));
                }
            }
            return new ModelIndex(Map.copyOf(result), Map.copyOf(taskMetadata));
        } catch (Exception e) {
            throw new EngineException("Could not parse process definition XML: " + e.getMessage(), e);
        }
    }

    private static String attributeByLocalName(Element element, String localName) {
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            var attribute = attributes.item(i);
            if (localName.equals(attribute.getLocalName())
                    || localName.equals(attribute.getNodeName())) {
                return attribute.getNodeValue();
            }
        }
        return null;
    }
}
