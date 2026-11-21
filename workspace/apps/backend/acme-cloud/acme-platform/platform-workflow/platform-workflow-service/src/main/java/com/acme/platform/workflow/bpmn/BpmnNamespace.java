package com.acme.platform.workflow.bpmn;

/**
 * BPMN 2.0 命名空间与 ACME 扩展属性常量（设计规格 §2：标准 namespace + 独立扩展 namespace
 * http://acme.dev/bpmn/ext/v1 前缀 acme:，不污染标准语义）。
 */
public final class BpmnNamespace {

    /** BPMN 2.0 MODEL namespace。 */
    public static final String BPMN_MODEL = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    /** BPMNDI namespace（解析期忽略布局，布局归 layout_json）。 */
    public static final String BPMN_DI = "http://www.omg.org/spec/BPMN/20100524/DI";

    /** ACME 扩展 namespace。 */
    public static final String ACME_EXT = "http://acme.dev/bpmn/ext/v1";

    /** XSI namespace（conditionExpression xsi:type 判定）。 */
    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    private BpmnNamespace() {
    }
}
