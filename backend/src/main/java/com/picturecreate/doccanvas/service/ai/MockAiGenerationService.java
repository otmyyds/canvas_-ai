package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiGenerationService implements AiGenerationService {

    @Override
    public BoardActionResult generateBoardActions(AiTaskContext context) {
        String title = switch (context.type()) {
            case GENERATE_IMAGE, EDIT_IMAGE, GENERATE_COVER, GENERATE_DIAGRAM_ASSET -> "图片提示词草稿";
            case STUDY_PLAN -> "学习计划";
            case PRODUCT_REQUIREMENT -> "产品需求草稿";
            case CANVAS_STRUCTURE -> "画布结构建议";
            case GENERATE_MINDMAP -> "思维导图";
            case GENERATE_FLOWCHART -> "流程图";
            case DOCUMENT_SUMMARY -> "资料摘要";
        };
        String markdown = """
                ## %s

                用户请求：%s

                这是模拟 AI 结果。任务编排、进度事件、中断检查和画布操作协议已接通。
                将 `AI_PROVIDER` 设置为 `spring-ai` 后，会调用真实模型生成内容。

                %s
                """.formatted(title, context.prompt(), sourcesText(context));

        String skillInfo = context.skill() == null
                ? ""
                : "\n已加载技能：" + context.skill().name() + "，允许工具：" + String.join("、", context.skill().allowedTools());

        List<BoardAction> actions = new ArrayList<>();
        actions.add(new BoardAction.CreateCard(null, title + skillInfo, markdown, "SUMMARY", 140, 140, 340, 220, List.of()));
        // 画布已有卡片时，追加一条连线动作，便于演示多动作链路
        if (context.cards() != null && !context.cards().isEmpty()) {
            String fromId = context.cards().get(0).cardId();
            actions.add(new BoardAction.Connect(fromId, "00000000-0000-0000-0000-000000000000", "参考", null, null, 2));
        }
        return new BoardActionResult(actions,
                "{\"mode\":\"mock\",\"actionCount\":%d,\"message\":\"模拟生成完成\"}".formatted(actions.size()));
    }

    private String sourcesText(AiTaskContext context) {
        StringBuilder text = new StringBuilder();
        if (context.cards() != null && !context.cards().isEmpty()) {
            text.append("\n画布现有卡片：\n");
            for (AiTaskContext.CardContext card : context.cards()) {
                text.append("- ").append(card.title()).append(" (").append(card.cardType()).append(")\n");
            }
        }
        if (context.sources() != null && !context.sources().isEmpty()) {
            text.append("\n已解析资料片段：");
            for (AiTaskContext.SourceContext source : context.sources()) {
                text.append("\n").append(source.filename()).append(": ").append(source.content());
            }
        } else {
            text.append("\n（当前画布没有已解析的资料）");
        }
        return text.toString();
    }
}
