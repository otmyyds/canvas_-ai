package com.picturecreate.doccanvas.service.ai.agent;

import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.service.ai.BoardAction;

/**
 * 一次 Agent 运行的结果。
 * actions 为空表示本次没有修改画布；reply 为直接回复文本。
 */
public record AgentResult(
        String reply,
        String summary,
        List<BoardAction> actions,
        UUID runId,
        String model,
        int modelCalls,
        int toolCalls,
        int totalTokens) {

    public static AgentResult replied(String reply, UUID runId, String model, int modelCalls, int toolCalls,
            int totalTokens) {
        return new AgentResult(reply, null, List.of(), runId, model, modelCalls, toolCalls, totalTokens);
    }

    public static AgentResult modified(String summary, List<BoardAction> actions, UUID runId, String model,
            int modelCalls, int toolCalls, int totalTokens) {
        return new AgentResult(null, summary, actions, runId, model, modelCalls, toolCalls, totalTokens);
    }

    public boolean hasActions() {
        return actions != null && !actions.isEmpty();
    }
}
