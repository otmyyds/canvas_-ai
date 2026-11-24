package com.picturecreate.doccanvas.service.ai;

import org.springframework.context.annotation.Profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.DocumentChunk;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.DocumentChunkService;
import com.picturecreate.doccanvas.service.ai.agent.AgentExecutionContext;
import com.picturecreate.doccanvas.service.ai.agent.AgentToolContext;

/**
 * Agent 只读工具。所有方法仅返回上下文数据，不写库。
 *
 * 归属由 AgentExecutionContext 决定：工具不接受 boardId 参数，
 * 模型无法通过参数访问其他画布。调用轨迹由 BudgetedToolCallback 统一写入 agent_steps。
 */
@Profile("!lite")
@Component
public class BoardReadTools {

    /** 画布摘要最多返回的节点数 */
    private static final int SUMMARY_NODE_LIMIT = 20;
    /** 子树默认/最大节点数与深度 */
    private static final int SUBTREE_DEFAULT_LIMIT = 30;
    private static final int SUBTREE_MAX_LIMIT = 60;
    private static final int SUBTREE_MAX_DEPTH = 4;
    /** 搜索结果上限 */
    private static final int SEARCH_DEFAULT_LIMIT = 10;
    /** 资料片段总字符上限 */
    private static final int CHUNK_CHAR_LIMIT = 4000;

    private final BoardRepository boardRepository;
    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;
    private final SourceFileRepository sourceFileRepository;
    private final DocumentChunkService chunkService;
    private final BoardService boardService;
    private final WebSearchService webSearchService;

    public BoardReadTools(BoardRepository boardRepository, ShapeRepository shapeRepository,
            BoardEdgeRepository edgeRepository, SourceFileRepository sourceFileRepository,
            DocumentChunkService chunkService, BoardService boardService, WebSearchService webSearchService) {
        this.boardRepository = boardRepository;
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.chunkService = chunkService;
        this.boardService = boardService;
        this.webSearchService = webSearchService;
    }

    @Tool(name = "get_board_summary",
            description = "获取当前画布标题、节点数量、层级统计、根节点和部分节点列表。用于了解画布整体结构。")
    public String getBoardSummary(ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        UUID boardId = context.boardId();
        boardService.getOwned(boardId, context.user());
        String title = boardRepository.findById(boardId).map(board -> board.getTitle()).orElse("未命名画布");
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderBySortOrderAscCreatedAtAsc(boardId);
        StringBuilder result = new StringBuilder("画布标题：").append(title).append('\n');
        result.append("节点数量：").append(shapes.size()).append("，连线数量：").append(edges.size()).append('\n');
        if (shapes.isEmpty()) {
            result.append("画布为空\n");
            appendSelected(result, context);
            return result.toString();
        }
        Map<Integer, Integer> levelCounts = new HashMap<>();
        Set<UUID> hasParent = new HashSet<>();
        for (BoardEdge edge : edges) {
            hasParent.add(edge.getToShapeId());
        }
        for (Shape shape : shapes) {
            levelCounts.merge(shape.getLevel(), 1, Integer::sum);
        }
        result.append("层级分布：");
        levelCounts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.append("level").append(entry.getKey()).append('=')
                        .append(entry.getValue()).append(' '));
        result.append('\n');
        List<Shape> roots = shapes.stream().filter(shape -> !hasParent.contains(shape.getId())).toList();
        result.append("根节点：");
        if (roots.isEmpty()) {
            result.append("无（存在环）");
        } else {
            result.append(roots.stream().map(shape -> "[" + shape.getId() + "] " + shape.getTitle())
                    .reduce((left, right) -> left + "；" + right).orElse(""));
        }
        result.append('\n');
        result.append("节点列表（最多 ").append(SUMMARY_NODE_LIMIT).append(" 个）：\n");
        shapes.stream().limit(SUMMARY_NODE_LIMIT).forEach(shape -> appendShape(result, shape));
        if (shapes.size() > SUMMARY_NODE_LIMIT) {
            result.append("... 其余 ").append(shapes.size() - SUMMARY_NODE_LIMIT)
                    .append(" 个节点请用 search_nodes 或 get_subtree 查看\n");
        }
        appendSelected(result, context);
        return result.toString();
    }

    @Tool(name = "get_node", description = "获取单个图形节点的标题、类型、层级与坐标")
    public String getNode(@ToolParam(description = "节点 id") String shapeId, ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        boardService.getOwned(context.boardId(), context.user());
        Optional<Shape> found = findOwnedShape(shapeId, context.boardId());
        if (found.isEmpty()) {
            return "节点不存在或不属于当前画布：" + shapeId;
        }
        StringBuilder result = new StringBuilder();
        Shape shape = found.get();
        result.append("[").append(shape.getId()).append("] ").append(shape.getTitle())
                .append("（").append(shape.getShape()).append(", level=").append(shape.getLevel())
                .append(", x=").append(shape.getX()).append(", y=").append(shape.getY()).append("）\n");
        appendNeighbors(result, shape.getId(), context.boardId());
        return result.toString();
    }

    @Tool(name = "get_subtree",
            description = "以某节点为根获取子树，返回节点与父子连线。深度默认 1，节点数默认 30。")
    public String getSubtree(@ToolParam(description = "根节点 id") String shapeId,
            @ToolParam(description = "向下展开深度，默认 1，最大 4") Integer depth,
            @ToolParam(description = "最多返回节点数，默认 30，最大 60") Integer maxNodes,
            ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        boardService.getOwned(context.boardId(), context.user());
        Optional<Shape> root = findOwnedShape(shapeId, context.boardId());
        if (root.isEmpty()) {
            return "节点不存在或不属于当前画布：" + shapeId;
        }
        int maxDepth = depth == null ? 1 : Math.max(0, Math.min(depth, SUBTREE_MAX_DEPTH));
        int limit = maxNodes == null ? SUBTREE_DEFAULT_LIMIT : Math.max(1, Math.min(maxNodes, SUBTREE_MAX_LIMIT));
        Map<UUID, Shape> shapes = new HashMap<>();
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(context.boardId())) {
            shapes.put(shape.getId(), shape);
        }
        Map<UUID, List<BoardEdge>> children = new HashMap<>();
        for (BoardEdge edge : edgeRepository.findByBoardIdOrderBySortOrderAscCreatedAtAsc(context.boardId())) {
            children.computeIfAbsent(edge.getFromShapeId(), key -> new ArrayList<>()).add(edge);
        }
        StringBuilder result = new StringBuilder("子树根：").append(root.get().getTitle()).append('\n');
        Deque<NodeDepth> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        queue.add(new NodeDepth(root.get().getId(), 0));
        int count = 0;
        while (!queue.isEmpty() && count < limit) {
            NodeDepth current = queue.poll();
            Shape shape = shapes.get(current.nodeId());
            if (shape == null || !visited.add(current.nodeId())) {
                continue;
            }
            appendShape(result, shape);
            count += 1;
            if (current.depth() >= maxDepth) {
                continue;
            }
            for (BoardEdge edge : children.getOrDefault(current.nodeId(), List.of())) {
                if (!visited.contains(edge.getToShapeId())) {
                    result.append("  边：").append(current.nodeId()).append(" -> ")
                            .append(edge.getToShapeId()).append('\n');
                    queue.add(new NodeDepth(edge.getToShapeId(), current.depth() + 1));
                }
            }
        }
        if (count >= limit) {
            result.append("... 已达到节点数上限 ").append(limit).append('\n');
        }
        return result.toString();
    }

    @Tool(name = "search_nodes", description = "按标题关键词搜索当前画布的节点，返回 id 与层级")
    public String searchNodes(@ToolParam(description = "标题关键词") String keyword,
            @ToolParam(description = "返回条数，默认 10") Integer limit,
            ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        boardService.getOwned(context.boardId(), context.user());
        if (keyword == null || keyword.isBlank()) {
            return "关键词不能为空";
        }
        int max = limit == null ? SEARCH_DEFAULT_LIMIT : Math.max(1, Math.min(limit, SEARCH_DEFAULT_LIMIT));
        List<Shape> matched = shapeRepository.findByBoardIdOrderByCreatedAtAsc(context.boardId()).stream()
                .filter(shape -> shape.getTitle() != null && shape.getTitle().contains(keyword))
                .limit(max)
                .toList();
        if (matched.isEmpty()) {
            return "没有标题包含「" + keyword + "」的节点";
        }
        StringBuilder result = new StringBuilder();
        matched.forEach(shape -> appendShape(result, shape));
        return result.toString();
    }

    @Tool(name = "list_selected", description = "列出用户当前选中的节点详情")
    public String listSelected(ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        boardService.getOwned(context.boardId(), context.user());
        if (context.selectedShapeIds().isEmpty()) {
            return "当前没有选中节点";
        }
        StringBuilder result = new StringBuilder("选中节点：\n");
        for (String shapeId : context.selectedShapeIds()) {
            findOwnedShape(shapeId, context.boardId()).ifPresent(shape -> appendShape(result, shape));
        }
        return result.toString();
    }

    @Tool(name = "list_source_files", description = "列出当前画布已上传的资料文件名与解析状态")
    public String listSourceFiles(ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        boardService.getOwned(context.boardId(), context.user());
        List<SourceFile> files = sourceFileRepository
                .findByBoardIdAndOwnerIdOrderByCreatedAtDesc(context.boardId(), context.user().id());
        if (files.isEmpty()) {
            return "当前画布没有上传资料";
        }
        StringBuilder result = new StringBuilder();
        for (SourceFile file : files) {
            result.append("- ").append(file.getFilename())
                    .append("（").append(file.getParseStatus()).append("）\n");
        }
        return result.toString();
    }

    @Tool(name = "search_document_chunks", description = "从画布资料的向量索引检索与问题最相关的文本片段")
    public String searchDocumentChunks(@ToolParam(description = "检索问题") String query,
            @ToolParam(description = "返回片段数量，默认 5") Integer topK,
            ToolContext toolContext) {
        AgentExecutionContext context = AgentToolContext.require(toolContext);
        boardService.getOwned(context.boardId(), context.user());
        int k = topK == null ? 5 : Math.max(1, Math.min(topK, 10));
        List<DocumentChunk> chunks = chunkService.search(context.boardId(), context.user().id(), query, k);
        if (chunks.isEmpty()) {
            return "未检索到相关资料片段";
        }
        Set<UUID> fileIds = chunks.stream().map(DocumentChunk::getSourceFileId).collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> filenames = new HashMap<>();
        sourceFileRepository.findAllById(fileIds)
                .forEach(file -> filenames.put(file.getId(), file.getFilename()));
        StringBuilder result = new StringBuilder();
        int totalChars = 0;
        for (DocumentChunk chunk : chunks) {
            String filename = filenames.getOrDefault(chunk.getSourceFileId(), "资料片段");
            String content = chunk.getContent() == null ? "" : chunk.getContent();
            if (totalChars + content.length() > CHUNK_CHAR_LIMIT) {
                content = content.substring(0, Math.max(0, CHUNK_CHAR_LIMIT - totalChars));
            }
            result.append("--- ").append(filename).append('\n').append(content).append('\n');
            totalChars += content.length();
            if (totalChars >= CHUNK_CHAR_LIMIT) {
                result.append("（已达到片段字符上限，后续内容被截断）\n");
                break;
            }
        }
        return result.toString();
    }

    @Tool(name = "web_search", description = "联网搜索最新信息。涉及版本、日期、近期事件等时效性内容时调用。")
    public String webSearch(@ToolParam(description = "搜索关键词") String query, ToolContext toolContext) {
        AgentToolContext.require(toolContext);
        return webSearchService.search(query);
    }

    private Optional<Shape> findOwnedShape(String shapeId, UUID boardId) {
        UUID id = parseUuid(shapeId);
        if (id == null) {
            return Optional.empty();
        }
        return shapeRepository.findById(id)
                .filter(shape -> shape.getBoardId().equals(boardId));
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void appendShape(StringBuilder builder, Shape shape) {
        builder.append("- [").append(shape.getId()).append("] ").append(shape.getTitle())
                .append("（").append(shape.getShape()).append(", level=").append(shape.getLevel()).append("）\n");
    }

    private void appendSelected(StringBuilder builder, AgentExecutionContext context) {
        if (context.selectedShapeIds().isEmpty()) {
            return;
        }
        builder.append("用户选中的节点：");
        builder.append(String.join("、", context.selectedShapeIds()));
        builder.append('\n');
    }

    private void appendNeighbors(StringBuilder builder, UUID shapeId, UUID boardId) {
        List<BoardEdge> edges = edgeRepository.findByFromShapeIdOrToShapeId(shapeId, shapeId);
        if (edges.isEmpty()) {
            return;
        }
        Map<UUID, String> titles = new HashMap<>();
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            titles.put(shape.getId(), shape.getTitle());
        }
        for (BoardEdge edge : edges) {
            String from = titles.getOrDefault(edge.getFromShapeId(), edge.getFromShapeId().toString());
            String to = titles.getOrDefault(edge.getToShapeId(), edge.getToShapeId().toString());
            builder.append("  连线：").append(from).append(" -> ").append(to)
                    .append(edge.getLabel() == null ? "" : "（" + edge.getLabel() + "）").append('\n');
        }
    }

    /** BFS 队列元素 */
    private record NodeDepth(UUID nodeId, int depth) {
    }
}
