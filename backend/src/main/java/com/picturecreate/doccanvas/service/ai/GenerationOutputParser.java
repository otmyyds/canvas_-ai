package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 生成结果的确定性解析器。
 *
 * 模型只负责写内容，不负责调用写工具：
 * - 树型任务输出 Markdown 标题大纲，按标题井号数量解析层级与父子连线；
 * - 流程图/编辑任务输出单份 JSON 动作文档，按 type 字段分派为 BoardAction。
 *
 * 解析是纯 Java 逻辑，不暴露给模型，避免模型绕过结构约束直接拼节点和连线。
 */
public final class GenerationOutputParser {

    private static final Logger log = LoggerFactory.getLogger(GenerationOutputParser.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GenerationOutputParser() {
    }

    /**
     * 从 Markdown 标题大纲解析节点与连线。
     * 标题井号数量决定层级：一个井号是根节点，两个井号是一级分支，以此类推。
     * 每个非根节点连接到上一层最近出现的节点，同层新节点会重置其子树。
     */
    public static List<BoardAction> parseOutline(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<BoardAction> actions = new ArrayList<>();
        // parentAtLevel[level] 记录每层最近出现的节点 id（level=井号数-1），供连线到父节点
        String[] parentAtLevel = new String[8];
        int nodeCount = 0;
        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !trimmed.startsWith("#")) {
                continue;
            }
            int hashes = 0;
            while (hashes < trimmed.length() && trimmed.charAt(hashes) == '#') {
                hashes += 1;
            }
            // 标题井号后必须有空格分隔
            if (hashes >= trimmed.length() || trimmed.charAt(hashes) != ' ') {
                continue;
            }
            String title = trimmed.substring(hashes + 1).trim();
            if (title.isEmpty()) {
                continue;
            }
            int level = hashes - 1;
            // 超过最大支持深度的标题直接忽略，避免越界
            if (level >= parentAtLevel.length) {
                continue;
            }
            nodeCount += 1;
            String id = "node" + nodeCount;
            // 节点类型与尺寸按层级：0=ellipse 中心，1=rectangle 分支，>=2=oval 叶子
            String shape = level == 0 ? "ellipse" : level == 1 ? "rectangle" : "oval";
            double width = level == 0 ? 200 : level == 1 ? 180 : 160;
            double height = level == 0 ? 96 : level == 1 ? 72 : 60;
            actions.add(new BoardAction.CreateShape(id, title, shape, level, 0, 0, width, height));
            if (level > 0 && parentAtLevel[level - 1] != null) {
                actions.add(new BoardAction.Connect(parentAtLevel[level - 1], id, "", "curve", null,
                        level == 1 ? 2 : 1));
            }
            parentAtLevel[level] = id;
            // 同层新节点会重置其子树，清空更深层记录
            for (int i = level + 1; i < parentAtLevel.length; i++) {
                parentAtLevel[i] = null;
            }
        }
        return actions;
    }

    /**
     * 从单份 JSON 动作文档解析动作列表。
     * 兼容模型偶尔包裹的 ```json 代码围栏；无法解析返回空列表，由上层带错误信息重试。
     */
    @SuppressWarnings("unchecked")
    public static List<BoardAction> parseActions(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        try {
            String json = stripFence(content.trim());
            Map<String, Object> root = OBJECT_MAPPER.readValue(json, Map.class);
            Object actionsNode = root.get("actions");
            if (!(actionsNode instanceof List<?> list)) {
                return List.of();
            }
            List<BoardAction> actions = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String type = map.get("type") == null ? "" : map.get("type").toString();
                switch (type) {
                    case "create_shape" -> actions.add(new BoardAction.CreateShape(
                            str(map.get("id")),
                            str(map.get("title")),
                            str(map.get("shape"), "ellipse"),
                            intValue(map.get("level"), 0),
                            dbl(map.get("x"), 0), dbl(map.get("y"), 0),
                            dbl(map.get("width"), 140), dbl(map.get("height"), 60)));
                    case "connect" -> actions.add(new BoardAction.Connect(
                            str(map.get("fromShapeId")), str(map.get("toShapeId")),
                            str(map.get("label")),
                            str(map.get("style")),
                            str(map.get("color")),
                            intValue(map.get("weight"), 2)));
                    case "update_shape" -> actions.add(new BoardAction.UpdateShape(
                            str(map.get("shapeId")),
                            nullableStr(map.get("title")),
                            nullableStr(map.get("shape")),
                            nullableInt(map.get("level"))));
                    case "delete_shape" -> actions.add(new BoardAction.DeleteShape(
                            str(map.get("shapeId"))));
                    default -> {
                        // 忽略未知动作
                    }
                }
            }
            return actions;
        } catch (Exception exception) {
            log.warn("解析模型输出 JSON 失败：{} content: {}", exception.getMessage(),
                    content.substring(0, Math.min(200, content.length())));
            return List.of();
        }
    }

    /** 去掉 ```json 代码围栏 */
    public static String stripFence(String content) {
        if (!content.startsWith("```")) {
            return content;
        }
        int end = content.lastIndexOf("```");
        if (end <= 3) {
            return content;
        }
        String json = content.substring(3, end).trim();
        if (json.startsWith("json")) {
            json = json.substring(4).trim();
        }
        return json;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static String str(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String nullableStr(Object value) {
        return value == null ? null : value.toString();
    }

    private static double dbl(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    private static Integer nullableInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
