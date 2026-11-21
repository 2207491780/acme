package com.acme.platform.workflow.bpmn;

import com.acme.platform.workflow.bpmn.model.BpmnElement;
import com.acme.platform.workflow.bpmn.model.BpmnFlow;
import com.acme.platform.workflow.bpmn.model.BpmnRawProcess;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BPMN 2.0 XML 文档解析器（JDK DOM，零新增依赖——设计规格 §9 后端零新增）：
 * 标准 namespace 展平节点/连线 + acme 扩展属性提取 + 一期白名单外元素报错。
 * 安全面：禁 DTD/外部实体（XXE 防护，2026-11-19 外部输入运行时校验口径）。
 */
public final class BpmnDocumentParser {

    /** 一期白名单映射：BPMN localName → 元素类型。 */
    private static final Map<String, BpmnElementType> WHITELIST = Map.ofEntries(
            Map.entry("startEvent", BpmnElementType.START_EVENT),
            Map.entry("endEvent", BpmnElementType.END_EVENT),
            Map.entry("exclusiveGateway", BpmnElementType.EXCLUSIVE_GATEWAY),
            Map.entry("parallelGateway", BpmnElementType.PARALLEL_GATEWAY),
            Map.entry("userTask", BpmnElementType.USER_TASK),
            Map.entry("serviceTask", BpmnElementType.SERVICE_TASK),
            Map.entry("subProcess", BpmnElementType.EMBEDDED_SUB_PROCESS));

    private BpmnDocumentParser() {
    }

    /**
     * 解析 XML 为裸模型（结构畸形抛 ProcessXmlMalformedException；白名单外元素收集校验错误）。
     *
     * @throws ProcessXmlMalformedException 非 XML / 非 BPMN process 结构
     */
    public static ParseResult parse(String bpmnXml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (ParserConfigurationException ex) {
            throw new IllegalStateException("DOM 解析器安全配置失败", ex);
        }
        Document document;
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override
                public void error(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            document = builder.parse(new ByteArrayInputStream(
                    bpmnXml.getBytes(StandardCharsets.UTF_8)));
        } catch (SAXException | IOException | ParserConfigurationException ex) {
            throw new ProcessXmlMalformedException("XML 解析失败：" + ex.getMessage());
        }
        Element definitions = document.getDocumentElement();
        if (definitions == null
                || !"definitions".equals(definitions.getLocalName())
                || !BpmnNamespace.BPMN_MODEL.equals(definitions.getNamespaceURI())) {
            throw new ProcessXmlMalformedException("根元素须为 BPMN 2.0 definitions（MODEL namespace）");
        }
        Element processElement = firstChildElement(definitions, "process", BpmnNamespace.BPMN_MODEL);
        if (processElement == null) {
            throw new ProcessXmlMalformedException("缺少 BPMN process 元素");
        }
        List<BpmnElement> elements = new ArrayList<>();
        List<BpmnFlow> flows = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        String processId = processElement.getAttribute("id");
        for (Element child : childElements(processElement)) {
            String localName = child.getLocalName();
            String uri = child.getNamespaceURI();
            if (!BpmnNamespace.BPMN_MODEL.equals(uri)) {
                continue;
            }
            if ("sequenceFlow".equals(localName)) {
                flows.add(parseFlow(child));
                continue;
            }
            BpmnElementType type = WHITELIST.get(localName);
            if (type == null) {
                if ("boundaryEvent".equals(localName)) {
                    BpmnElementType timerType = boundaryTimerType(child);
                    if (timerType != null) {
                        elements.add(parseNode(child, timerType, processId));
                        continue;
                    }
                }
                unsupported.add(localName + "#" + child.getAttribute("id"));
                continue;
            }
            if (type == BpmnElementType.EMBEDDED_SUB_PROCESS) {
                // 子流程内节点与连线展平进同一模型（scope 由运行时 execution 子树承载，§3.2；
                // containerId=子流程节点 id——start/end 恰一按容器校验 + 进入子流程定位其 StartEvent）
                collectSubProcess(child, elements, flows, unsupported);
            }
            elements.add(parseNode(child, type, processId));
        }
        return new ParseResult(new BpmnRawProcess(processId,
                processElement.getAttribute("name"), List.copyOf(elements), List.copyOf(flows)),
                unsupported);
    }

    /** 解析结果（裸模型 + 白名单外元素清单——转译为校验错误逐条返回）。 */
    public record ParseResult(BpmnRawProcess process, List<String> unsupportedElements) {
    }

    /** XML 结构畸形异常（→ WORKFLOW_XML_INVALID）。 */
    public static final class ProcessXmlMalformedException extends RuntimeException {

        public ProcessXmlMalformedException(String message) {
            super(message);
        }
    }

    /** 边界事件类型判定：仅一期 timerEventDefinition（中断型）；其余按不支持收集。 */
    private static BpmnElementType boundaryTimerType(Element element) {
        NodeList definitions = element.getElementsByTagNameNS(BpmnNamespace.BPMN_MODEL, "timerEventDefinition");
        return definitions.getLength() > 0 ? BpmnElementType.BOUNDARY_TIMER : null;
    }

    /** 子流程内容展平（一层节点 + 内部连线，containerId=子流程节点 id；嵌套子流程按白名单外收集）。 */
    private static void collectSubProcess(Element subProcess, List<BpmnElement> elements,
                                          List<BpmnFlow> flows, List<String> unsupported) {
        String containerId = subProcess.getAttribute("id");
        for (Element child : childElements(subProcess)) {
            String localName = child.getLocalName();
            if (!BpmnNamespace.BPMN_MODEL.equals(child.getNamespaceURI())) {
                continue;
            }
            if ("subProcess".equals(localName)) {
                unsupported.add(localName + "#" + child.getAttribute("id"));
                continue;
            }
            if ("sequenceFlow".equals(localName)) {
                flows.add(parseFlow(child));
                continue;
            }
            BpmnElementType type = WHITELIST.get(localName);
            if (type == null) {
                unsupported.add(localName + "#" + child.getAttribute("id"));
                continue;
            }
            elements.add(parseNode(child, type, containerId));
        }
    }

    private static BpmnElement parseNode(Element element, BpmnElementType type, String containerId) {
        Map<String, String> acmeAttrs = new LinkedHashMap<>();
        for (String localName : AcmeAttributes.KNOWN) {
            String value = element.getAttributeNS(BpmnNamespace.ACME_EXT, localName);
            if (value != null && !value.isBlank()) {
                acmeAttrs.put(localName, value.trim());
            }
        }
        String attachedTo = null;
        String timerDelay = null;
        String timerDate = null;
        String defaultFlow = null;
        if (type == BpmnElementType.BOUNDARY_TIMER) {
            attachedTo = element.getAttribute("attachedToRef");
            for (Element timerDef : childElements(element)) {
                if (!"timerEventDefinition".equals(timerDef.getLocalName())) {
                    continue;
                }
                Element duration = firstChildElement(timerDef, "timeDuration", BpmnNamespace.BPMN_MODEL);
                Element date = firstChildElement(timerDef, "timeDate", BpmnNamespace.BPMN_MODEL);
                if (duration != null) {
                    timerDelay = duration.getTextContent().trim();
                }
                if (date != null) {
                    timerDate = date.getTextContent().trim();
                }
            }
        }
        if (type == BpmnElementType.EXCLUSIVE_GATEWAY || type == BpmnElementType.PARALLEL_GATEWAY) {
            String defaultAttr = element.getAttribute("default");
            defaultFlow = defaultAttr == null || defaultAttr.isBlank() ? null : defaultAttr.trim();
        }
        return new BpmnElement(element.getAttribute("id"), element.getAttribute("name"), type,
                acmeAttrs, attachedTo, timerDelay, timerDate, defaultFlow, containerId);
    }

    private static BpmnFlow parseFlow(Element element) {
        String condition = null;
        Element conditionElement = firstChildElement(element, "conditionExpression", BpmnNamespace.BPMN_MODEL);
        if (conditionElement != null) {
            condition = conditionElement.getTextContent().trim();
        }
        return new BpmnFlow(element.getAttribute("id"), element.getAttribute("name"),
                element.getAttribute("sourceRef"), element.getAttribute("targetRef"),
                condition, false);
    }

    private static Element firstChildElement(Element parent, String localName, String namespace) {
        for (Element child : childElements(parent)) {
            if (localName.equals(child.getLocalName()) && namespace.equals(child.getNamespaceURI())) {
                return child;
            }
        }
        return null;
    }

    private static List<Element> childElements(Node parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }
}
