package com.picturecreate.doccanvas.service.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 加载内置 Skill（classpath 下 ai-skills 各子目录中的 SKILL.md）。
 * 解析 YAML frontmatter（--- 分隔）与 Markdown 正文。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final String LOCATION = "classpath:ai-skills/*/SKILL.md";

    private final List<SkillDefinition> skills;

    public SkillLoader() {
        this.skills = loadAll();
        log.info("SkillLoader 加载完成：共 {} 个技能：{}", skills.size(),
                skills.stream().map(SkillDefinition::slug).toList());
    }

    /** 按任务类型返回匹配的 Skill，无匹配返回空 */
    public Optional<SkillDefinition> findForTask(String taskType) {
        return skills.stream()
                .filter(skill -> skill.supports(taskType))
                .findFirst();
    }

    public List<SkillDefinition> all() {
        return Collections.unmodifiableList(skills);
    }

    private List<SkillDefinition> loadAll() {
        List<SkillDefinition> result = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource resource : resolver.getResources(LOCATION)) {
                try (InputStream in = resource.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    parse(content).ifPresent(result::add);
                }
            }
        } catch (IOException exception) {
            // 资源缺失不阻断启动，任务执行时按无 Skill 处理
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Optional<SkillDefinition> parse(String content) {
        if (content == null || !content.startsWith("---")) {
            return Optional.empty();
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return Optional.empty();
        }
        String frontmatter = content.substring(3, end).trim();
        String body = content.substring(end + 4).trim();

        try {
            Yaml yaml = new Yaml();
            Map<String, Object> meta = yaml.load(frontmatter);
            if (meta == null) {
                return Optional.empty();
            }
            String slug = str(meta.get("slug"));
            String name = str(meta.get("name"));
            int version = intValue(meta.get("version"), 1);
            String description = str(meta.get("description"));
            List<String> taskTypes = stringList(meta.get("task_types"));
            List<String> allowedTools = stringList(meta.get("allowed_tools"));
            if (slug == null || taskTypes.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new SkillDefinition(slug, name, version, description, taskTypes, allowedTools, body));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }
}
