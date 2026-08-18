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

    public static String validate(List<BoardAction> actions) {
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
            if (action instanceof BoardAction.CreateCard createCard) {
                if (createCard.title() == null || createCard.title().isBlank()) {
                    problems.add("存在标题为空的 create_card 动作");
                }
            } else if (action instanceof BoardAction.Connect connect) {
                if (connect.fromCardId() == null || connect.fromCardId().isBlank()
                        || connect.toCardId() == null || connect.toCardId().isBlank()) {
                    problems.add("存在端点缺失的 connect 动作");
                    continue;
                }
                if (connect.fromCardId().equals(connect.toCardId())) {
                    problems.add("存在连接同一张卡片的 connect 动作");
                    continue;
                }
                String key = connect.fromCardId() + "->" + connect.toCardId();
                if (!seenEdges.add(key)) {
                    problems.add("存在重复连线：" + key);
                }
            } else if (action instanceof BoardAction.UpdateCard updateCard) {
                if (updateCard.cardId() == null || updateCard.cardId().isBlank()) {
                    problems.add("存在 cardId 缺失的 update_card 动作");
                }
            } else if (action instanceof BoardAction.CreateGroup group) {
                if (group.title() == null || group.title().isBlank()) {
                    problems.add("存在标题为空的分组动作");
                }
            }
        }
        String result = String.join("；", problems);
        log.info("校验动作：动作数={} 问题={}", actions.size(), result.isEmpty() ? "无" : result);
        return result;
    }
}
