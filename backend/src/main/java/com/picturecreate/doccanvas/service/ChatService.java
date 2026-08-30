package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.service.ai.AiTaskContext.SourceContext;

/**
 * 纯文本对话。不走结构化 JSON 输出，不生成 BoardAction，不落库。
 * 用于 AI 面板中的闲聊、询问、非生产型意图。
 *
 * 上下文：画布标题 + RAG 检索出的相关文档片段（不再是全量文档）。
 * 检索由 RagContextService 完成，query 即用户输入，只注入 topK 个相关片段。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** RAG 检索片段数 */
    private static final int RAG_TOP_K = 5;

    private final ChatClient chatClient;
    private final BoardRepository boardRepository;
    private final RagContextService ragContextService;

    public ChatService(ChatClient.Builder builder, BoardRepository boardRepository,
            RagContextService ragContextService) {
        this.chatClient = builder.build();
        this.boardRepository = boardRepository;
        this.ragContextService = ragContextService;
    }

    /**
     * 纯文本聊天回复。
     *
     * @param boardId 画布 id（取标题 + 检索文档范围）
     * @param user 当前用户（检索范围约束）
     * @param userPrompt 用户输入
     * @return AI 回复
     */
    @Transactional(readOnly = true)
    public String chat(UUID boardId, CurrentUser user, String userPrompt) {
        String boardTitle = boardRepository.findById(boardId).map(b -> b.getTitle()).orElse(null);
        List<SourceContext> sources = ragContextService.retrieve(boardId, user.id(), userPrompt, RAG_TOP_K);
        log.info("聊天：board={} 检索片段数={} prompt={}", boardTitle, sources.size(), userPrompt);
        String systemPrompt = buildSystemPrompt(boardTitle, sources);
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

    private String buildSystemPrompt(String boardTitle, List<SourceContext> sources) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是 Doc Canvas 画布 AI 助手，一个单人 AI 文档画布应用。\n");
        builder.append("你的职责：\n");
        builder.append("- 友好地回答用户关于画布功能、AI 能力、技术问题\n");
        builder.append("- 用户上传的文档相关内容已在下方提供，回答相关问题时请依据这些内容\n");
        builder.append("- 在用户需要生成内容时给出建议（如\"需要我帮你生成思维导图吗？\"）\n");
        builder.append("- 不要生成 JSON 或代码，用自然语言回复\n");
        builder.append("- 保持回复简洁、有帮助\n");
        if (boardTitle != null && !boardTitle.isBlank()) {
            builder.append("\n当前画布：").append(boardTitle).append('\n');
        }
        if (sources != null && !sources.isEmpty()) {
            builder.append("\n与当前问题相关的资料片段（供回答参考）：\n");
            for (SourceContext source : sources) {
                builder.append("\n===== 片段（").append(source.filename()).append("） =====\n");
                builder.append(source.content());
                builder.append('\n');
            }
        } else {
            builder.append("\n（当前画布没有已解析的上传文档）\n");
        }
        return builder.toString();
    }
}
