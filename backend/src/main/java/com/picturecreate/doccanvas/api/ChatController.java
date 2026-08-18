package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.CardRepository;
import com.picturecreate.doccanvas.service.ChatService;
import com.picturecreate.doccanvas.service.ChatService.DocumentContext;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.FileService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 纯文本聊天接口控制器。
 *
 * 职责：AI 面板中的闲聊/询问（不走任务流程、不落卡）。
 * 区别于 AiTaskController：聊天返回自然语言文本，任务返回结构化动作并落库。
 *
 * 前端路由逻辑：
 * - 用户输入匹配到任务关键词（思维导图/流程图/总结等）→ 走任务接口
 * - 未匹配（你好/你是谁/问问题）→ 走本聊天接口
 *
 * 上下文注入：画布标题 + 卡片数 + 已解析的上传文档内容，
 * 让 AI 能回答"关于画布/文档"的问题。
 *
 * 接口：POST /api/boards/{boardId}/chat  body: { prompt }  →  { reply }
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final CurrentUserService currentUserService;
    private final BoardRepository boardRepository;
    private final CardRepository cardRepository;
    private final FileService fileService;

    public ChatController(ChatService chatService, CurrentUserService currentUserService,
            BoardRepository boardRepository, CardRepository cardRepository, FileService fileService) {
        this.chatService = chatService;
        this.currentUserService = currentUserService;
        this.boardRepository = boardRepository;
        this.cardRepository = cardRepository;
        this.fileService = fileService;
    }

    /**
     * 纯文本聊天：接收用户输入，返回 AI 自然语言回复。
     * 上下文 = 画布标题 + 卡片数 + 已解析文档内容（拼进 system prompt）。
     */
    @PostMapping("/api/boards/{boardId}/chat")
    public ChatResponse chat(@PathVariable UUID boardId, @Valid @RequestBody ChatRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("聊天请求：boardId={} prompt={}", boardId, body.prompt());
        String boardTitle = boardRepository.findById(boardId).map(b -> b.getTitle()).orElse(null);
        int cardCount = cardRepository.findByBoardIdOrderByCreatedAtAsc(boardId).size();
        // 注入已解析的上传文档内容，让 AI 能回答关于文档的问题
        List<DocumentContext> documents = fileService.parsedSources(boardId, user.id()).stream()
                .map(src -> new DocumentContext(src.filename(), src.content()))
                .toList();
        String reply = chatService.chat(boardTitle, cardCount, documents, body.prompt());
        return new ChatResponse(reply);
    }

    /** 聊天请求体：用户输入文本 */
    public record ChatRequest(@NotBlank(message = "输入不能为空") String prompt) {
    }

    /** 聊天响应体：AI 自然语言回复 */
    public record ChatResponse(String reply) {
    }
}
