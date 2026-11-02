package com.picturecreate.doccanvas.service.ai.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.service.ai.GenerationOutputParser;

/**
 * Supervisor 分支计划的确定性解析与校验。
 * 解析失败抛出带中文原因的异常，由委派方把原因回填给模型重试。
 */
public final class BranchPlanParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 分支标题上限，与 shapes.title 列长度一致 */
    private static final int TITLE_MAX_LENGTH = 255;

    /** 模型没写 maxChildren 时的默认子节点上限 */
    private static final int DEFAULT_MAX_CHILDREN = 3;

    private BranchPlanParser() {
    }

    /**
     * 解析分支计划。
     *
     * @param maxBranches      本次允许的最大分支数
     * @param maxChildrenLimit 单分支子节点上限的上限
     */
    public static BranchPlan parse(String content, int maxBranches, int maxChildrenLimit) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("未返回分支计划");
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(GenerationOutputParser.stripFence(content.trim()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("分支计划不是合法 JSON：" + exception.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("分支计划必须是 JSON 对象");
        }
        String rootTitle = text(root.get("root"));
        if (rootTitle == null || rootTitle.isBlank()) {
            throw new IllegalArgumentException("缺少根节点标题 root");
        }
        if (rootTitle.length() > TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException("根节点标题超过 " + TITLE_MAX_LENGTH + " 字");
        }
        JsonNode branchesNode = root.get("branches");
        if (branchesNode == null || !branchesNode.isArray() || branchesNode.isEmpty()) {
            throw new IllegalArgumentException("branches 必须是非空数组");
        }
        if (branchesNode.size() > maxBranches) {
            throw new IllegalArgumentException("分支数超过上限 " + maxBranches + "，实际 " + branchesNode.size());
        }
        List<BranchSpec> branches = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (JsonNode node : branchesNode) {
            String key = text(node.get("key"));
            String title = text(node.get("title"));
            String goal = text(node.get("goal"));
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("存在缺少 key 的分支");
            }
            if (!key.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("分支 key 只能包含字母、数字、下划线和短横线：" + key);
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("分支 key 重复：" + key);
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("分支 " + key + " 缺少标题");
            }
            if (title.length() > TITLE_MAX_LENGTH) {
                throw new IllegalArgumentException("分支 " + key + " 的标题超过 " + TITLE_MAX_LENGTH + " 字");
            }
            if (goal == null || goal.isBlank()) {
                throw new IllegalArgumentException("分支 " + key + " 缺少目标 goal");
            }
            int maxChildren = node.hasNonNull("maxChildren")
                    ? node.get("maxChildren").asInt(-1) : DEFAULT_MAX_CHILDREN;
            if (maxChildren < 1 || maxChildren > maxChildrenLimit) {
                throw new IllegalArgumentException("分支 " + key + " 的 maxChildren 必须在 1-" + maxChildrenLimit + " 之间");
            }
            branches.add(new BranchSpec(key, title, goal, maxChildren));
        }
        return new BranchPlan(rootTitle, List.copyOf(branches));
    }

    /** JSON 标量转字符串；缺失或 null 返回 null */
    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}
