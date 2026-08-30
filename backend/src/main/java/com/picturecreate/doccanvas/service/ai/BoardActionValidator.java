package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 校验模型产出的动作。返回校验失败原因（字符串），空串表示通过。
 * 仅做可自动修复/丢弃的校验：重复连线、自连、空动作。归属校验在应用时由 AiTaskService 完成。
 */
public final class BoardActionValidator {

    private static final Logger log = LoggerFactory.getLogger(BoardActionValidator.class);

    private BoardActionValidator() {
    }

    public static String validate(List<BoardAction> actions, AiTaskContext context) {
        if (actions == null || actions.isEmpty()) {
            return "模型未返回任何动作";
        }
        List<String> problems = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        for (BoardAction action : actions) {
            if (action == null) {
                problems.add("存在空动作");
                continue;
            }
            if (action instanceof BoardAction.CreateShape createShape) {
                if (createShape.title() == null || createShape.title().isBlank()) {
                    problems.add("存在标题为空的 create_shape 动作");
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
                String key = connect.fromShapeId() + "->" + connect.toShapeId();
                if (!seenEdges.add(key)) {
                    problems.add("存在重复连线：" + key);
                }
            } else if (action instanceof BoardAction.DeleteShape deleteShape) {
                if (deleteShape.shapeId() == null || deleteShape.shapeId().isBlank()) {
                    problems.add("存在 shapeId 缺失的 delete_shape 动作");
                }
            }
        }
        String result = String.join("；", problems);
        log.info("校验动作：动作数={} 问题={}", actions.size(), result.isEmpty() ? "无" : result);
        return result;
    }
}
