package com.acme.business.leave.process;

/**
 * 试点流程定义供给 SPI（REQ-20261122-233425 确认⑤）：申请面不经入参指定 definitionId——
 * 试点 BPMN 由 business-leave 域自持（leave-pilot），部署能力归聚合面（bootstrap 依赖
 * workflow 服务实现承载部署用例；4.4.2 business 禁依赖 *-service，故本模块仅声明 SPI）。
 * standalone 服务化（不在本期）由自身部署组件实现同 SPI。
 */
public interface LeaveProcessLauncher {

    /** 试点流程定义 id（确保已部署——幂等；失败显式，申请面不静默降级）。 */
    Long pilotDefinitionId();
}
