package com.picturecreate.doccanvas.service.ai;

import java.util.List;

/**
 * Skill 定义，对应 SKILL.md 的 YAML frontmatter 与正文。
 * 只承载任务类型匹配与领域约束；工具权限由 Agent 角色决定，不写在技能里。
 */
public record SkillDefinition(String slug, String name, List<String> taskTypes, String body) {

    public boolean supports(String taskType) {
        return taskTypes != null && taskTypes.contains(taskType);
    }
}
