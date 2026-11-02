package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 校验 Agent 产出的动作。返回校验失败原因（字符串），空串表示通过。
 * 只做可确定性判断的结构校验：空动作、空标题、悬空引用、重复连线、数量上限。
 * 标题允许重复：文档中不同分支出现相同短语是正常内容，节点以 id 区分。
 * 归属校验在应用时由 BoardActionApplier 完成。
 */
public final class BoardActionValidator {

    private static final Logger log = LoggerFactory.getLogger(BoardActionValidator.class);

    /** shapes.title 列长度 */
    private static final int TITLE_MAX_LENGTH = 255;

    private BoardActionValidator() {
    }

    public static String validate(List<BoardAction> actions, int maxNodes) {
        if (actions == null || actions.isEmpty()) {
            return "模型未返回任何动作";
        }
        List<String> problems = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        Set<String> createIds = new HashSet<>();
        int nodeChanges = 0;
        for (BoardAction action : actions) {
            if (action == null) {
                problems.add("存在空动作");
                continue;
            }
            if (action instanceof BoardAction.CreateShape createShape) {
                nodeChanges += 1;
                if (createShape.title() == null || createShape.title().isBlank()) {
                    problems.add("存在标题为空的 create_shape 动作");
                } else if (createShape.title().length() > TITLE_MAX_LENGTH) {
                    problems.add("节点标题超过 " + TITLE_MAX_LENGTH + " 字：" + createShape.title());
                }
                if (createShape.id() != null && !createShape.id().isBlank()) {
                    createIds.add(createShape.id());
                }
            } else if (action instanceof BoardAction.UpdateShape updateShape) {
                nodeChanges += 1;
                if (updateShape.shapeId() == null || updateShape.shapeId().isBlank()) {
                    problems.add("存在 shapeId 缺失的 update_shape 动作");
                }
                if (updateShape.title() == null && updateShape.shape() == null && updateShape.level() == null) {
                    problems.add("存在没有任何修改字段的 update_shape 动作");
                }
                if (updateShape.title() != null && updateShape.title().length() > TITLE_MAX_LENGTH) {
                    problems.add("更新后的标题超过 " + TITLE_MAX_LENGTH + " 字");
                }
            } else if (action instanceof BoardAction.Connect connect) {
                if (connect.fromShapeId() == null || connect.fromShapeId().isBlank()
                        || connect.toShapeId() == null || connect.toShapeId().isBlank()) {
                    problems.add("存在端点缺失的 connect 动作");
                    continue;
                }
                if (connect.fromShapeId().equals(connect.toShapeId())) {
                    problems.add("存在连接同一节点的 connect 动作");
                    continue;
                }
                String dangling = danglingRef(connect.fromShapeId(), createIds);
                if (dangling == null) {
                    dangling = danglingRef(connect.toShapeId(), createIds);
                }
                if (dangling != null) {
                    problems.add("连线引用了不存在的节点：" + dangling);
                    continue;
                }
                String key = connect.fromShapeId() + "->" + connect.toShapeId();
                if (!seenEdges.add(key)) {
                    problems.add("存在重复连线：" + key);
                }
            } else if (action instanceof BoardAction.DeleteShape deleteShape) {
                nodeChanges += 1;
                if (deleteShape.shapeId() == null || deleteShape.shapeId().isBlank()) {
                    problems.add("存在 shapeId 缺失的 delete_shape 动作");
                    continue;
                }
                String dangling = danglingRef(deleteShape.shapeId(), createIds);
                if (dangling != null) {
                    problems.add("删除动作引用了不存在的临时节点：" + dangling);
                }
            }
        }
        if (maxNodes > 0 && nodeChanges > maxNodes) {
            problems.add("节点修改数超过上限：" + maxNodes);
        }
        String result = String.join("；", problems);
        log.info("校验动作：动作数={} 问题={}", actions.size(), result.isEmpty() ? "无" : result);
        return result;
    }

    /**
     * 引用必须出现在本批 create_shape 的 id 里，或是合法的真实 UUID（归属交给应用阶段校验）。
     * 不再限定临时 id 的具体格式，和应用阶段「按 id 建映射」的解析方式保持一致。
     * 返回非空表示悬空引用。
     */
    private static String danglingRef(String ref, Set<String> createIds) {
        if (createIds.contains(ref)) {
            return null;
        }
        try {
            UUID.fromString(ref);
            return null;
        } catch (IllegalArgumentException exception) {
            return ref;
        }
    }
}
