package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.AiToolCall;
import com.picturecreate.doccanvas.domain.Card;
import com.picturecreate.doccanvas.domain.DocumentChunk;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.AiToolCallRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.CardRepository;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.repository.UserAccountRepository;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.DocumentChunkService;

/**
 * AI 只读工具。所有方法仅返回上下文数据，不写库。
 * 方法经 @Tool 注册后由 Spring AI 暴露给模型调用，执行结果写入 ai_tool_calls 审计。
 */
@Component
public class BoardReadTools {

    /** toolContext 中携带的任务 id key */
    public static final String TASK_ID_KEY = "taskId";
    /** toolContext 中携带的任务 owner key */
    public static final String OWNER_ID_KEY = "ownerId";

    private final BoardRepository boardRepository;
    private final CardRepository cardRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AiToolCallRepository toolCallRepository;
    private final DocumentChunkService chunkService;
    private final BoardService boardService;
    private final UserAccountRepository userAccountRepository;

    public BoardReadTools(BoardRepository boardRepository, CardRepository cardRepository,
            SourceFileRepository sourceFileRepository, AiToolCallRepository toolCallRepository,
            DocumentChunkService chunkService, BoardService boardService, UserAccountRepository userAccountRepository) {
        this.boardRepository = boardRepository;
        this.cardRepository = cardRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.toolCallRepository = toolCallRepository;
        this.chunkService = chunkService;
        this.boardService = boardService;
        this.userAccountRepository = userAccountRepository;
    }

    @Tool(name = "get_board_context", description = "获取当前画布标题与卡片摘要，供 AI 规划使用")
    public String getBoardContext(
            @ToolParam(description = "画布 id") String boardId,
            ToolContext toolContext) {
        return audit(toolContext, "get_board_context", boardId, () -> {
            UUID boardUuid = UUID.fromString(boardId);
            CurrentUser user = ownerUser(toolContext);
            boardService.getOwned(boardUuid, user);
            String title = boardRepository.findById(boardUuid).map(b -> b.getTitle()).orElse("未命名画布");
            StringBuilder result = new StringBuilder("画布标题：").append(title).append('\n');
            List<Card> cards = cardRepository.findByBoardIdOrderByCreatedAtAsc(boardUuid);
            result.append("卡片数量：").append(cards.size()).append('\n');
            for (Card card : cards) {
                result.append("- [").append(card.getId()).append("] ")
                        .append(card.getTitle()).append(" (").append(card.getCardType()).append(")：")
                        .append(truncate(card.getContentMarkdown(), 120)).append('\n');
            }
            return result.toString();
        });
    }

    @Tool(name = "get_card_content", description = "读取指定卡片的完整正文")
    public String getCardContent(
            @ToolParam(description = "卡片 id") String cardId,
            ToolContext toolContext) {
        return audit(toolContext, "get_card_content", cardId, () -> cardRepository.findById(UUID.fromString(cardId))
                .map(card -> card.getTitle() + "\n\n" + card.getContentMarkdown())
                .orElse("卡片不存在：" + cardId));
    }

    @Tool(name = "list_source_files", description = "列出当前画布已上传的资料文件名与解析状态")
    public String listSourceFiles(
            @ToolParam(description = "画布 id") String boardId,
            ToolContext toolContext) {
        return audit(toolContext, "list_source_files", boardId, () -> {
            UUID boardUuid = UUID.fromString(boardId);
            CurrentUser user = ownerUser(toolContext);
            boardService.getOwned(boardUuid, user);
            List<SourceFile> files = sourceFileRepository.findByBoardIdAndOwnerIdOrderByCreatedAtDesc(boardUuid, user.id());
            if (files.isEmpty()) {
                return "当前画布没有上传资料";
            }
            StringBuilder result = new StringBuilder();
            for (SourceFile file : files) {
                result.append("- ").append(file.getFilename())
                        .append("（").append(file.getParseStatus()).append("）\n");
            }
            return result.toString();
        });
    }

    @Tool(name = "search_document_chunks", description = "从画布资料的向量索引检索与问题最相关的文本片段")
    public String searchDocumentChunks(
            @ToolParam(description = "画布 id") String boardId,
            @ToolParam(description = "检索问题") String query,
            @ToolParam(description = "返回片段数量（默认 5）") Integer topK,
            ToolContext toolContext) {
        return audit(toolContext, "search_document_chunks", boardId + "|" + query, () -> {
            UUID boardUuid = UUID.fromString(boardId);
            CurrentUser user = ownerUser(toolContext);
            boardService.getOwned(boardUuid, user);
            int k = topK == null ? 5 : Math.max(1, Math.min(topK, 10));
            List<DocumentChunk> chunks = chunkService.search(boardUuid, user.id(), query, k);
            if (chunks.isEmpty()) {
                return "未检索到相关资料片段";
            }
            StringBuilder result = new StringBuilder();
            for (DocumentChunk chunk : chunks) {
                result.append("---\n").append(chunk.getContent()).append('\n');
            }
            return result.toString();
        });
    }

    /** 记录一次工具调用的成功/失败与耗时，返回调用结果 */
    private String audit(ToolContext toolContext, String toolName, String inputJson,
            java.util.function.Supplier<String> action) {
        UUID taskId = taskId(toolContext);
        long start = System.currentTimeMillis();
        AiToolCall record = taskId == null ? null : toolCallRepository.save(new AiToolCall(taskId, toolName, inputJson, "RUNNING"));
        try {
            String result = action.get();
            if (record != null) {
                record.succeed(result, System.currentTimeMillis() - start);
                toolCallRepository.save(record);
            }
            return result;
        } catch (RuntimeException exception) {
            if (record != null) {
                record.fail(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(),
                        System.currentTimeMillis() - start);
                toolCallRepository.save(record);
            }
            throw exception;
        }
    }

    private UUID taskId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(TASK_ID_KEY);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** 从 toolContext 解析任务 owner 用户（认证用户）；缺失回退演示用户 */
    private CurrentUser ownerUser(ToolContext toolContext) {
        UUID ownerId = null;
        if (toolContext != null && toolContext.getContext() != null) {
            Object value = toolContext.getContext().get(OWNER_ID_KEY);
            if (value != null) {
                try {
                    ownerId = UUID.fromString(value.toString());
                } catch (IllegalArgumentException ignored) {
                    // 忽略非法值
                }
            }
        }
        if (ownerId != null) {
            return userAccountRepository.findById(ownerId)
                    .map(user -> new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName()))
                    .orElse(null);
        }
        // 无 ownerId 时回退演示用户（保持本地兼容）
        UserAccount demo = userAccountRepository.findByEmail("demo@example.com")
                .orElseGet(() -> userAccountRepository.save(new UserAccount("demo@example.com", "演示用户")));
        return new CurrentUser(demo.getId(), demo.getEmail(), demo.getDisplayName());
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
