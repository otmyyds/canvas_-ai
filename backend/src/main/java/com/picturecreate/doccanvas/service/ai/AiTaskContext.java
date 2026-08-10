package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.domain.AiTaskType;

public record AiTaskContext(
        UUID taskId,
        UUID boardId,
        UUID ownerId,
        AiTaskType type,
        String prompt,
        List<SourceContext> sources,
        List<CardContext> cards,
        SkillDefinition skill) {

    public AiTaskContext(UUID taskId, UUID boardId, UUID ownerId, AiTaskType type, String prompt) {
        this(taskId, boardId, ownerId, type, prompt, List.of(), List.of(), null);
    }

    /** 画布资料库中已解析的文本片段 */
    public record SourceContext(String filename, String content) {
    }

    /** 画布中已存在的卡片，供 AI 规划时参考 */
    public record CardContext(String cardId, String title, String cardType, String content) {
    }
}
