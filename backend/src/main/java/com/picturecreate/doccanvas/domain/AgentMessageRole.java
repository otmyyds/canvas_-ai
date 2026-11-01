package com.picturecreate.doccanvas.domain;

/**
 * 会话消息角色。
 * 只存用户可见消息和必要的工具摘要；完整工具输入输出写入 agent_steps。
 */
public enum AgentMessageRole {
    /** 用户输入 */
    USER,
    /** 助手回复或任务结果摘要 */
    ASSISTANT,
    /** 工具结果摘要 */
    TOOL,
    /** 系统提示或状态说明 */
    SYSTEM
}
