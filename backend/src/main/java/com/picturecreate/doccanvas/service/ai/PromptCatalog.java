package com.picturecreate.doccanvas.service.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.AiTaskType;

/**
 * Agent 提示词统一管理：提示词文案外置到 resources/prompts/*.st，
 * 用 Spring AI PromptTemplate 加载渲染，改提示词无需改代码。
 */
@Component
public class PromptCatalog {

    private final PromptTemplate generationOutlineTemplate;
    private final PromptTemplate generationActionsTemplate;
    private final PromptTemplate agentPreflightTemplate;
    private final PromptTemplate branchOutlineTemplate;
    private final PromptTemplate branchWorkerTemplate;

    public PromptCatalog(
            @Value("classpath:prompts/generation-outline.st") Resource outlineResource,
            @Value("classpath:prompts/generation-actions.st") Resource actionsResource,
            @Value("classpath:prompts/agent-preflight.st") Resource preflightResource,
            @Value("classpath:prompts/agent-branch-outline.st") Resource branchOutlineResource,
            @Value("classpath:prompts/agent-branch-worker.st") Resource branchWorkerResource) {
        this.generationOutlineTemplate = new PromptTemplate(read(outlineResource));
        this.generationActionsTemplate = new PromptTemplate(read(actionsResource));
        this.agentPreflightTemplate = new PromptTemplate(read(preflightResource));
        this.branchOutlineTemplate = new PromptTemplate(read(branchOutlineResource));
        this.branchWorkerTemplate = new PromptTemplate(read(branchWorkerResource));
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取提示词模板失败：" + resource.getFilename(), exception);
        }
    }

    /** 树型任务：单份 Markdown 标题大纲 */
    public String generationOutlinePrompt(UUID boardId, AiTaskType taskType, List<String> toolNames,
            String prompt, String selected, String skill) {
        return generationOutlineTemplate.render(Map.of(
                "boardId", boardId.toString(),
                "date", LocalDate.now().toString(),
                "taskHint", taskType.name(),
                "requirement", generationRequirement(taskType),
                "tools", renderToolList(toolNames),
                "prompt", prompt == null ? "" : prompt,
                "selected", selected == null || selected.isBlank() ? "无" : selected,
                "skill", skill == null || skill.isBlank() ? "无" : skill));
    }

    /** 流程图/编辑任务：单份 JSON 动作文档 */
    public String generationActionsPrompt(UUID boardId, AiTaskType taskType, List<String> toolNames,
            String prompt, String selected, String skill) {
        String editConstraint = taskType == AiTaskType.EDIT_ELEMENT ? editElementConstraint() : "";
        return generationActionsTemplate.render(Map.of(
                "boardId", boardId.toString(),
                "date", LocalDate.now().toString(),
                "taskHint", taskType.name(),
                "requirement", generationRequirement(taskType),
                "tools", renderToolList(toolNames),
                "prompt", prompt == null ? "" : prompt,
                "selected", selected == null || selected.isBlank() ? "无" : selected,
                "skill", skill == null || skill.isBlank() ? "无" : skill,
                "editConstraint", editConstraint));
    }

    /** 同步预检系统提示词 */
    public String agentPreflightPrompt(UUID boardId, List<String> toolNames, String prompt, String selected) {
        return agentPreflightTemplate.render(Map.of(
                "boardId", boardId.toString(),
                "date", LocalDate.now().toString(),
                "tools", renderToolList(toolNames),
                "prompt", prompt == null ? "" : prompt,
                "selected", selected == null || selected.isBlank() ? "无" : selected));
    }

    /** Supervisor 拆解分支：只输出根节点与一级分支的 JSON */
    public String branchOutlinePrompt(UUID boardId, AiTaskType taskType, List<String> toolNames,
            String prompt, String skill, int maxBranches, int maxChildren) {
        return branchOutlineTemplate.render(Map.of(
                "boardId", boardId.toString(),
                "date", LocalDate.now().toString(),
                "taskHint", taskType.name(),
                "requirement", generationRequirement(taskType),
                "tools", renderToolList(toolNames),
                "prompt", prompt == null ? "" : prompt,
                "skill", skill == null || skill.isBlank() ? "无" : skill,
                "maxBranches", String.valueOf(maxBranches),
                "maxChildrenLimit", String.valueOf(maxChildren)));
    }

    /** 分支 Worker：只展开一个分支，输出 Markdown 一级子标题 */
    public String branchWorkerPrompt(AiTaskType taskType, List<String> toolNames, String prompt, String skill,
            String rootTitle, String branchTitle, String branchGoal, int maxChildren) {
        return branchWorkerTemplate.render(Map.of(
                "taskHint", taskType.name(),
                "requirement", generationRequirement(taskType),
                "tools", renderToolList(toolNames),
                "prompt", prompt == null ? "" : prompt,
                "skill", skill == null || skill.isBlank() ? "无" : skill,
                "rootTitle", rootTitle,
                "branchTitle", branchTitle,
                "branchGoal", branchGoal,
                "maxChildren", String.valueOf(maxChildren)));
    }

    /** 按任务类型给出一句产出要求 */
    private String generationRequirement(AiTaskType taskType) {
        return switch (taskType) {
            case DOCUMENT_SUMMARY -> "把资料核心内容归纳为要点分支，所有要点必须来自资料实际内容。\n";
            case STUDY_PLAN -> "把学习计划组织为阶段到步骤的树形结构，每个阶段包含可执行的步骤与练习。\n";
            case PRODUCT_REQUIREMENT -> "围绕目标、用户、范围、风险、里程碑展开分支。\n";
            case GENERATE_MINDMAP -> "以中心主题为根，逐层辐射展开知识点。\n";
            case GENERATE_FLOWCHART -> "用节点和连线表达完整流程，判断节点必须分出带条件的分支。\n";
            case EDIT_ELEMENT -> "只修改用户选中的节点及新增的关联节点，不要改动无关内容。\n";
            default -> "";
        };
    }

    /** 编辑选中元素的任务约束 */
    private String editElementConstraint() {
        return """
                编辑任务约束：
                1. 用户选中的节点真实 id 见下方「用户选中的节点」；需要节点详情时先调用 list_selected 或 get_node 读取。
                2. 删除选中节点用 delete_shape；给选中节点补充分支用 create_shape 加 connect。
                3. 只修改标题用 update_shape；actions 里的 id 必须是工具返回的真实 id，不要编造。
                """;
    }

    private String renderToolList(List<String> toolNames) {
        StringBuilder builder = new StringBuilder();
        for (String name : toolNames) {
            builder.append("- ").append(name).append('\n');
        }
        return builder.toString();
    }
}
