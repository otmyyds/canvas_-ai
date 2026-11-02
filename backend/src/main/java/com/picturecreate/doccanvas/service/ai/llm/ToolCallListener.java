package com.picturecreate.doccanvas.service.ai.llm;

/**
 * 工具调用观测接口。
 * 由 Agent 层实现，把每次工具调用写入 agent_steps；不参与业务逻辑，也不吞异常。
 */
public interface ToolCallListener {

    void onCall(String toolName, String input, String output, long latencyMs, String errorMessage);
}
