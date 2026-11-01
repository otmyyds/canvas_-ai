package com.picturecreate.doccanvas.domain;

/**
 * Agent 角色。
 * 一次任务由 Supervisor 主导，按需派生分支 Worker、资料研究员和语义审查员；
 * 每个角色各占一条 agent_runs 记录，parent_run_id 组成委派树。
 */
public enum AgentRole {
    /** 主控：理解意图、规划大纲、委派分支、合并提案 */
    SUPERVISOR,
    /** 分支执行者：只读资料和画布，返回结构化分支结果 */
    BRANCH_WORKER,
    /** 资料研究员：从单份资料提取证据，不产生画布写操作 */
    SOURCE_RESEARCHER,
    /** 语义审查员：检查大图的覆盖度、重复和语义断裂 */
    CRITIC
}
