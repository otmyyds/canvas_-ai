package com.picturecreate.doccanvas.domain;

/**
 * Agent 角色。
 * 当前生成管线与同步预检都由 Supervisor 完成，每个角色各占一条 agent_runs 记录；
 * 其余角色预留给后续的分支委派与审查扩展。
 */
public enum AgentRole {
    /** 主控：理解任务、生成文档或直接回复 */
    SUPERVISOR,
    /** 分支执行者：只读资料和画布，返回结构化分支结果 */
    BRANCH_WORKER,
    /** 资料研究员：从单份资料提取证据，不产生画布写操作 */
    SOURCE_RESEARCHER,
    /** 语义审查员：检查大图的覆盖度、重复和语义断裂 */
    CRITIC
}
