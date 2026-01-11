package org.noear.solon.ai.agent.team;

public enum TeamStatus {
    RUNNING,    // 协作中
    SUSPENDED,  // 挂起（等待人工干预/审批）
    COMPLETED,  // 正常结束
    TERMINATED  // 异常/熔断终止
}