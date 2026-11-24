package com.picturecreate.doccanvas.service.ai.agent;

import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.CurrentUser;

/**
 * 预检工具创建后台任务的入口。
 * AiTaskService 经 AgentPlanExecutor 依赖 AgentRuntime，AgentRuntime 又经工具注册表依赖本类，
 * 因此用 ObjectProvider 延迟取任务服务，打破构造期循环依赖。
 */
@Profile("!lite")
@Component
public class AgentJobLauncher {

    private final ObjectProvider<AiTaskService> taskServiceProvider;

    public AgentJobLauncher(ObjectProvider<AiTaskService> taskServiceProvider) {
        this.taskServiceProvider = taskServiceProvider;
    }

    /** 创建并投递计划任务：一个任务加 N 条有序步骤，会话 id 随任务保存 */
    public AiTaskResponse launchPlan(UUID boardId, CurrentUser user, String goal, List<PlanDraft> steps,
            List<String> selectedShapeIds, UUID sessionId) {
        return taskServiceProvider.getObject()
                .createPlan(boardId, user, goal, steps, selectedShapeIds, sessionId);
    }
}
