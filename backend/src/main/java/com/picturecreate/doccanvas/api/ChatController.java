package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

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

@RestController
public class ChatController {

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

    @PostMapping("/api/boards/{boardId}/chat")
    public ChatResponse chat(@PathVariable UUID boardId, @Valid @RequestBody ChatRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        String boardTitle = boardRepository.findById(boardId).map(b -> b.getTitle()).orElse(null);
        int cardCount = cardRepository.findByBoardIdOrderByCreatedAtAsc(boardId).size();
        // 注入已解析的上传文档内容，让 AI 能回答关于文档的问题
        List<DocumentContext> documents = fileService.parsedSources(boardId, user.id()).stream()
                .map(src -> new DocumentContext(src.filename(), src.content()))
                .toList();
        String reply = chatService.chat(boardTitle, cardCount, documents, body.prompt());
        return new ChatResponse(reply);
    }

    public record ChatRequest(@NotBlank(message = "输入不能为空") String prompt) {
    }

    public record ChatResponse(String reply) {
    }
}
