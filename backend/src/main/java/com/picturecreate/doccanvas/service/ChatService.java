package com.picturecreate.doccanvas.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯文本对话。不走结构化 JSON 输出，不生成 BoardAction，不落库。
 * 用于 AI 面板中的闲聊、询问、非生产型意图。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;

    public ChatService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 纯文本聊天回复。
     * @param boardTitle 画布标题（可选）
     * @param cardCount 画布卡片数（供 AI 感知上下文）
     * @param documents 已解析的上传文档内容列表（文件名 + 文本）
     * @param userPrompt 用户的输入
     * @return AI 回复
     */
    public String chat(String boardTitle, int cardCount, List<DocumentContext> documents, String userPrompt) {
        log.info("聊天：board={} cardCount={} 文档数={} prompt={}", boardTitle, cardCount,
                documents == null ? 0 : documents.size(), userPrompt);
        String systemPrompt = buildSystemPrompt(boardTitle, cardCount, documents);
        try {
            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception exception) {
            log.warn("聊天调用失败：{}", exception.getMessage(), exception);
            return "抱歉，AI 服务暂时不可用：" + exception.getMessage();
        }
    }

    /** 上传文档的上下文片段 */
    public record DocumentContext(String filename, String content) {
    }

    private String buildSystemPrompt(String boardTitle, int cardCount, List<DocumentContext> documents) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 Doc Canvas 画布 AI 助手，一个单人 AI 文档画布应用。\n");
        builder.append("你的职责：\n");
        builder.append("- 友好地回答用户关于画布功能、AI 能力、技术问题\n");
        builder.append("- 用户上传的文档内容已在下方提供，回答相关问题时请依据这些内容\n");
        builder.append("- 在用户需要生成内容时给出建议（如\"需要我帮你生成思维导图吗？\"）\n");
        builder.append("- 不要生成 JSON 或代码，用自然语言回复\n");
        builder.append("- 保持回复简洁、有帮助\n");
        if (boardTitle != null && !boardTitle.isBlank()) {
            builder.append("\n当前画布：").append(boardTitle);
            builder.append("（").append(cardCount).append(" 张卡片）\n");
        }
        if (documents != null && !documents.isEmpty()) {
            builder.append("\n上传文档内容（供回答参考）：\n");
            for (DocumentContext doc : documents) {
                builder.append("\n===== 文档：").append(doc.filename()).append(" =====\n");
                builder.append(doc.content());
                builder.append('\n');
            }
        } else {
            builder.append("\n（当前画布没有已解析的上传文档）\n");
        }
        return builder.toString();
    }
}
