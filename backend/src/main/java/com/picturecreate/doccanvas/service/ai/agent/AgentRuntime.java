package com.picturecreate.doccanvas.service.ai.agent;

import org.springframework.context.annotation.Profile;

import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.service.ai.SkillDefinition;

/**
 * 单个计划步骤的生成入口。
 * 运行记录、用量与收尾由 AgentPlanExecutor 负责，这里只负责一次模型生成，
 * 保持生成阶段只产出动作、不写画布。
 */
@Profile("!lite")
@Component
public class AgentRuntime {

    private final BoardGenerationAgent generationAgent;

    public AgentRuntime(BoardGenerationAgent generationAgent) {
        this.generationAgent = generationAgent;
    }

    /** 执行一次生成：模型调用 → 解析 → 校验，失败时带错误反馈重试一次 */
    public AgentResult generate(AgentExecutionContext context, SkillDefinition skill) {
        return generationAgent.run(context, skill);
    }
}
