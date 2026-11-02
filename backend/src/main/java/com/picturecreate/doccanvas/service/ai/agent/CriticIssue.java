package com.picturecreate.doccanvas.service.ai.agent;

/**
 * 语义审查发现的单条问题。
 * nodeId 是待审查快照中的节点标识（可能是临时 id），无法定位节点时为 null。
 */
public record CriticIssue(String nodeId, Type type, Severity severity, String message, String suggestion) {

    /** 审查维度；结构类问题由确定性校验负责，不在此列 */
    public enum Type {
        /** 节点语义重复，标题不同也算 */
        DUPLICATE,
        /** 内容缺少资料依据；没有资料引用时不会出现 */
        UNSUPPORTED,
        /** 目标中的关键方面缺失或严重失衡 */
        COVERAGE,
        /** 同级节点粒度差异过大 */
        GRANULARITY,
        /** 跨节点内容互相矛盾 */
        CONTRADICTION,
        /** 标题空泛、无法表达内容 */
        TITLE
    }

    /** 严重程度：只有影响正确性或会直接误导用户的问题才是 HIGH */
    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }
}
