package com.picturecreate.doccanvas.service.ai;

import java.util.List;

/**
 * Skill 元数据，对应 SKILL.md 的 YAML frontmatter。
 */
public record SkillDefinition(
        String slug,
        String name,
        int version,
        String description,
        List<String> taskTypes,
        List<String> allowedTools,
        String body) {

    public boolean supports(String taskType) {
        return taskTypes != null && taskTypes.contains(taskType);
    }
}
