package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiGenerationService implements AiGenerationService {

    private static final Logger log = LoggerFactory.getLogger(MockAiGenerationService.class);

    @Override
    public BoardActionResult generateBoardActions(AiTaskContext context) {
        log.info("Mock 生成：type={} prompt={} 图形数={} 资料数={}", context.type(), context.prompt(),
                context.shapes() == null ? 0 : context.shapes().size(),
                context.sources() == null ? 0 : context.sources().size());
        String title = switch (context.type()) {
            case STUDY_PLAN -> "学习计划";
            case PRODUCT_REQUIREMENT -> "产品需求草稿";
            case CANVAS_STRUCTURE -> "画布结构建议";
            case CLEAR_CANVAS -> "清空画布";
            case GENERATE_MINDMAP -> "思维导图";
            case GENERATE_FLOWCHART -> "流程图";
            case DOCUMENT_SUMMARY -> "资料摘要";
            case EDIT_ELEMENT -> "编辑选中元素";
        };

        String skillInfo = context.skill() == null
                ? ""
                : "\n已加载技能：" + context.skill().name() + "，允许工具：" + String.join("、", context.skill().allowedTools());

        List<BoardAction> actions = new ArrayList<>();
        actions.add(new BoardAction.CreateShape(null, title + skillInfo, "ellipse", 0, 140, 140, 200, 96));
        return new BoardActionResult(actions,
                "{\"mode\":\"mock\",\"actionCount\":%d,\"message\":\"模拟生成完成\"}".formatted(actions.size()));
    }
}
