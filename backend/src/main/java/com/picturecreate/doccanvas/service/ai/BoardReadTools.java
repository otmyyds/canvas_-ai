package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.AiToolCall;
import com.picturecreate.doccanvas.domain.DocumentChunk;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.AiToolCallRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
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

    private static final Logger log = LoggerFactory.getLogger(BoardReadTools.class);

    /** toolContext 中携带的任务 id key */
    public static final String TASK_ID_KEY = "taskId";
    /** toolContext 中携带的任务 owner key */
    public static final String OWNER_ID_KEY = "ownerId";

    private final BoardRepository boardRepository;
    private final ShapeRepository shapeRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AiToolCallRepository toolCallRepository;
    private final DocumentChunkService chunkService;
    private final BoardService boardService;
    private final UserAccountRepository userAccountRepository;
    private final WebSearchService webSearchService;

    public BoardReadTools(BoardRepository boardRepository, ShapeRepository shapeRepository,
            SourceFileRepository sourceFileRepository, AiToolCallRepository toolCallRepository,
            DocumentChunkService chunkService, BoardService boardService, UserAccountRepository userAccountRepository,
            WebSearchService webSearchService) {
        this.boardRepository = boardRepository;
        this.shapeRepository = shapeRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.toolCallRepository = toolCallRepository;
        this.chunkService = chunkService;
        this.boardService = boardService;
        this.userAccountRepository = userAccountRepository;
        this.webSearchService = webSearchService;
    }

    @Tool(name = "get_board_context", description = "获取当前画布标题与图形节点摘要，供 AI 规划使用")
    public String getBoardContext(
            @ToolParam(description = "画布 id") String boardId,
            ToolContext toolContext) {
        return audit(toolContext, "get_board_context", boardId, () -> {
            UUID boardUuid = UUID.fromString(boardId);
            CurrentUser user = ownerUser(toolContext);
            boardService.getOwned(boardUuid, user);
            String title = boardRepository.findById(boardUuid).map(b -> b.getTitle()).orElse("未命名画布");
            StringBuilder result = new StringBuilder("画布标题：").append(title).append('\n');
            List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardUuid);
            result.append("图形节点数量：").append(shapes.size()).append('\n');
            for (Shape shape : shapes) {
                result.append("- [").append(shape.getId()).append("] ")
                        .append(shape.getTitle()).append(" (").append(shape.getShape())
                        .append(", level=").append(shape.getLevel()).append(")\n");
            }
            return result.toString();
        });
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

    @Tool(name = "web_search", description = "联网搜索最新信息。当用户问题涉及版本、日期、时间、近期事件、最新动态等时效性内容时调用，返回实时搜索结果。")
    public String webSearch(
            @ToolParam(description = "搜索关键词") String query,
            ToolContext toolContext) {
        return audit(toolContext, "web_search", query, () -> webSearchService.search(query));
    }

    /** 记录一次工具调用的成功/失败与耗时，返回调用结果，并打日志便于排查 */
    private String audit(ToolContext toolContext, String toolName, String inputJson,
            java.util.function.Supplier<String> action) {
        UUID taskId = taskId(toolContext);
        long start = System.currentTimeMillis();
        log.info("AI 工具调用：taskId={} tool={} input={}", taskId, toolName, inputJson);
        AiToolCall record = taskId == null ? null : toolCallRepository.save(new AiToolCall(taskId, toolName, inputJson, "RUNNING"));
        try {
            String result = action.get();
            long duration = System.currentTimeMillis() - start;
            log.info("AI 工具返回：taskId={} tool={} 耗时={}ms 结果={}", taskId, toolName, duration,
                    truncate(result, 300));
            if (record != null) {
                record.succeed(result, duration);
                toolCallRepository.save(record);
            }
            return result;
        } catch (RuntimeException exception) {
            log.warn("AI 工具失败：taskId={} tool={} error={}", taskId, toolName, exception.getMessage());
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
