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
import com.picturecreate.doccanvas.service.ai.PromptCatalog;

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
    private final PromptCatalog promptCatalog;

    public ChatService(ChatClient.Builder builder, BoardRepository boardRepository,
            RagContextService ragContextService, PromptCatalog promptCatalog) {
        this.chatClient = builder.build();
        this.boardRepository = boardRepository;
        this.ragContextService = ragContextService;
        this.promptCatalog = promptCatalog;
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
        String systemPrompt = promptCatalog.chatSystemPrompt(boardTitle, sources);
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
}
