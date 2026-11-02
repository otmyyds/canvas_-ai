package com.picturecreate.doccanvas.service.ai.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.domain.AiTaskType;

/**
 * 预检的收尾工具：reply 直接回答，start_plan 制定有序计划并创建后台任务。
 * 预检不写画布；计划由后台执行器逐步执行，每一步结束后检查真实画布状态。
 */
@Component
public class AgentControlTools {

    private static final Logger log = LoggerFactory.getLogger(AgentControlTools.class);

    /** 单个计划最多步骤数 */
    private static final int MAX_PLAN_STEPS = 6;

    private static final TypeReference<List<PlanDraft>> PLAN_TYPE = new TypeReference<>() {
    };

    private final AgentJobLauncher jobLauncher;
    private final ObjectMapper objectMapper;

    public AgentControlTools(AgentJobLauncher jobLauncher, ObjectMapper objectMapper) {
        this.jobLauncher = jobLauncher;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "reply", description = "直接回答用户，不修改画布。用于闲聊、解释、澄清问题。")
    public String reply(@ToolParam(description = "回复给用户的文本") String text, ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        if (context.finished()) {
            return error("本次运行已结束，不要重复调用 reply");
        }
        if (text == null || text.isBlank()) {
            return error("回复内容不能为空");
        }
        context.markReplied(text);
        log.info("Agent 直接回复：taskId={} runId={} length={}",
                context.taskId(), context.runId(), text.length());
        return "{\"ok\":true,\"message\":\"回复已发送\"}";
    }

    @Tool(name = "start_plan",
            description = "为画布任务制定有序计划并提交执行。stepsJson 是 JSON 数组，每项含 "
                    + "type/title/description/brief。type 取 GENERATE_MINDMAP、GENERATE_FLOWCHART、"
                    + "DOCUMENT_SUMMARY、STUDY_PLAN、PRODUCT_REQUIREMENT、EDIT_ELEMENT、GENERATE_IMAGE、"
                    + "CANVAS_STRUCTURE 或 CLEAR_CANVAS。简单请求只写 1 步；复合请求按依赖顺序拆成多步。")
    public String startPlan(
            @ToolParam(description = "用户本次的总目标") String goal,
            @ToolParam(description = "有序步骤 JSON 数组，每项含 type/title/description/brief") String stepsJson,
            ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        // 同一次预检只创建一个计划，避免模型重复调用产生多个后台任务
        if (context.startedTaskId() != null) {
            return error("本次运行已创建计划，不能重复调用 start_plan");
        }
        if (context.finished()) {
            return error("本次运行已结束，不能再创建计划");
        }
        List<PlanDraft> steps;
        try {
            steps = objectMapper.readValue(stepsJson, PLAN_TYPE);
        } catch (Exception exception) {
            return planError(context, "stepsJson 不是合法的 JSON 数组：" + exception.getMessage());
        }
        String validation = validate(steps);
        if (validation != null) {
            return planError(context, validation);
        }
        String prompt = goal == null || goal.isBlank() ? context.prompt() : goal.trim();
        try {
            AiTaskResponse task = jobLauncher.launchPlan(context.boardId(), context.user(), prompt, steps,
                    context.selectedShapeIds());
            context.markTaskStarted(task.id(), AiTaskType.AGENT_PLAN);
            log.info("预检创建计划任务：taskId={} 步骤数={} 目标={}", task.id(), steps.size(), prompt);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.put("taskId", task.id().toString());
            payload.put("type", AiTaskType.AGENT_PLAN.name());
            payload.put("stepCount", steps.size());
            return json(payload);
        } catch (RuntimeException exception) {
            log.warn("预检创建计划失败：error={}", exception.getMessage());
            return planError(context, "创建计划失败：" + exception.getMessage());
        }
    }

    /** 计划结构校验；返回 null 表示通过 */
    private String validate(List<PlanDraft> steps) {
        if (steps == null || steps.isEmpty()) {
            return "计划至少需要 1 个步骤";
        }
        if (steps.size() > MAX_PLAN_STEPS) {
            return "计划最多 " + MAX_PLAN_STEPS + " 个步骤";
        }
        int clearCount = 0;
        for (int index = 0; index < steps.size(); index++) {
            PlanDraft step = steps.get(index);
            String position = "第 " + (index + 1) + " 步";
            if (step == null) {
                return position + "内容为空";
            }
            AiTaskType type = parseType(step.type());
            if (type == null) {
                return position + "的 type 无效：" + step.type();
            }
            if (isBlank(step.title())) {
                return position + "缺少 title";
            }
            if (isBlank(step.description())) {
                return position + "缺少 description";
            }
            if (isBlank(step.brief())) {
                return position + "缺少 brief";
            }
            if (type == AiTaskType.CLEAR_CANVAS) {
                clearCount++;
            }
        }
        if (clearCount > 1) {
            return "CLEAR_CANVAS 最多出现一次";
        }
        return null;
    }

    /** 步骤类型必须是可执行的子任务类型，不能是 AGENT_PLAN 本身 */
    private AiTaskType parseType(String type) {
        if (isBlank(type)) {
            return null;
        }
        try {
            AiTaskType parsed = AiTaskType.valueOf(type.trim().toUpperCase(Locale.ROOT));
            return parsed == AiTaskType.AGENT_PLAN ? null : parsed;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 记录最后一次计划错误，供预检在没有创建任务时向用户展示 */
    private String planError(AgentExecutionContext context, String message) {
        context.recordPlanError(message);
        return error(message);
    }

    private String error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message);
        return json(payload);
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
        }
    }
}
