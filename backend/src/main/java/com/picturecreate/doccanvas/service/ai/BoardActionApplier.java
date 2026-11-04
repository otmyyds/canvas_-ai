package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.BoardLayoutStyle;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;

/**
 * 画布动作落库器：校验后的动作先原子落库，再按最终图重排受影响的连通分量。
 *
 * 布局模式由最终节点图决定：
 * - 分量内存在 level > 0 的节点：思维导图，按画布设置走径向（树形图）或逻辑图；
 * - 其余分量：流程图，永远自上而下分层。
 * 新增节点挂到已有节点时重排整个分量，已有节点可以移动；未受影响的分量不动。
 *
 * 调用方负责开启事务：中途失败必须整体回滚，不能留下半张图。
 */
@Component
public class BoardActionApplier {

    private static final Logger log = LoggerFactory.getLogger(BoardActionApplier.class);

    /** 节点间距与画布边距 */
    private static final double GAP = 120;
    private static final double MARGIN = 120;
    /** 网格布局列数与节点间距（用于没有任何连线的并列节点） */
    private static final int GRID_COLUMNS = 5;
    private static final double GRID_GAP = 80;
    /** 尺寸未知时的缺省节点宽高 */
    private static final double DEFAULT_NODE_W = 140;
    private static final double DEFAULT_NODE_H = 60;

    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;
    private final BoardRepository boardRepository;
    private final AppProperties properties;

    public BoardActionApplier(ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository,
            BoardRepository boardRepository, AppProperties properties) {
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.boardRepository = boardRepository;
        this.properties = properties;
    }

    /**
     * 在调用方事务内应用动作；失败直接抛出，由事务整体回滚。返回实际生效数量供步骤检查。
     *
     * 先创建、更新、连线、删除，再读取最终图重排受影响的连通分量：
     * 同一批动作里的标题/层级/删除都会参与布局计算。
     */
    public ApplyReport apply(UUID boardId, List<BoardAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return ApplyReport.empty();
        }
        // 行锁：与「切换布局」接口串行，避免两边同时重排互相覆盖
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));
        Map<String, UUID> shapeIdMap = new HashMap<>();
        List<ApplyReport.CreatedShape> createdNodes = new ArrayList<>();
        Set<UUID> createdIds = new HashSet<>();
        Set<UUID> updatedIds = new HashSet<>();
        int createdShapes = 0;
        int createdEdges = 0;
        int updatedShapes = 0;
        int deletedShapes = 0;
        int skipped = 0;
        // 第一遍：创建节点，记录临时 id -> 真实 id
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.CreateShape createShape) {
                UUID created = createShape(boardId, createShape);
                createdShapes++;
                createdNodes.add(new ApplyReport.CreatedShape(created, createShape.title(), createShape.level()));
                if (createShape.id() != null && !createShape.id().isBlank()) {
                    shapeIdMap.put(createShape.id(), created);
                    createdIds.add(created);
                }
            }
        }
        // 第二遍：更新、连线、删除
        for (BoardAction action : actions) {
            switch (action) {
                case BoardAction.Connect connect -> {
                    if (connect(boardId, connect, shapeIdMap)) {
                        createdEdges++;
                    } else {
                        skipped++;
                    }
                }
                case BoardAction.UpdateShape update -> {
                    UpdateOutcome outcome = update(boardId, update);
                    if (outcome.applied()) {
                        updatedShapes++;
                        if (outcome.relayout()) {
                            updatedIds.add(outcome.shapeId());
                        }
                    } else {
                        skipped++;
                    }
                }
                case BoardAction.DeleteShape delete -> {
                    if (delete(boardId, delete)) {
                        deletedShapes++;
                    } else {
                        skipped++;
                    }
                }
                case BoardAction.CreateShape ignored -> {
                    // 第一遍已创建
                }
            }
        }
        if (!createdIds.isEmpty() || !updatedIds.isEmpty()) {
            relayoutTouched(board, createdIds, updatedIds);
        }
        return new ApplyReport(createdShapes, createdEdges, updatedShapes, deletedShapes, skipped,
                List.copyOf(createdNodes));
    }

    /**
     * 整理画布：按连通分量重新布局已有节点，不新增、不删除。
     * 显式整理允许整体重新打包，并从左上角依次排布。
     */
    public void layoutExistingBoard(UUID boardId) {
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));
        Graph graph = loadGraph(boardId);
        if (graph.shapes().isEmpty()) {
            return;
        }
        // 显式整理时按标题重新放大尺寸；只增不减，保留用户手动放大的节点
        for (Shape shape : graph.shapes()) {
            NodeSizeEstimator.NodeSize size = NodeSizeEstimator.estimate(shape.getTitle());
            shape.growTo(size.width(), size.height());
        }
        double cursorX = MARGIN;
        for (List<String> component : graph.components()) {
            Map<String, double[]> sizes = sizesOf(graph, component);
            Map<String, double[]> positions = layoutComponent(board, graph, component, sizes);
            double[] box = bounds(positions, sizes);
            placeComponent(graph, component, positions, cursorX - box[0], MARGIN - box[1]);
            cursorX += (box[2] - box[0]) + GAP;
        }
        log.info("整理画布：boardId={} 连通分量={} 节点={}", boardId, graph.components().size(), graph.shapes().size());
    }

    /**
     * 重排受本批动作影响的连通分量。
     * - 含已有节点的分量：新布局的包围盒左上角对齐旧包围盒左上角，整块图原地整理；
     * - 全新分量与孤立新节点：放到画布已有内容右侧；
     * - 未受影响的分量不动。
     */
    private void relayoutTouched(Board board, Set<UUID> createdIds, Set<UUID> updatedIds) {
        shapeRepository.flush();
        Graph graph = loadGraph(board.getId());
        Set<String> newIds = toStrings(createdIds);
        Set<String> touched = toStrings(createdIds);
        touched.addAll(toStrings(updatedIds));

        List<List<String>> anchored = new ArrayList<>();
        List<List<String>> fresh = new ArrayList<>();
        List<String> isolatedNew = new ArrayList<>();
        for (List<String> component : graph.components()) {
            if (component.stream().noneMatch(touched::contains)) {
                continue;
            }
            boolean allNew = component.stream().allMatch(newIds::contains);
            if (allNew && component.size() == 1) {
                isolatedNew.add(component.get(0));
            } else if (allNew) {
                fresh.add(component);
            } else {
                anchored.add(component);
            }
        }
        if (anchored.isEmpty() && fresh.isEmpty() && isolatedNew.isEmpty()) {
            return;
        }
        // 只有「按光标重新放置」的节点才排除出已有包围盒，原地重排的分量仍算已有内容
        Set<String> cursorPlaced = new HashSet<>(isolatedNew);
        fresh.forEach(cursorPlaced::addAll);
        double[] existing = boundsExcluding(graph, cursorPlaced);
        double cursorX = existing == null ? MARGIN : existing[2] + GAP;
        double baseY = existing == null ? MARGIN : existing[1];

        if (!isolatedNew.isEmpty()) {
            Set<String> isolatedSet = new HashSet<>(isolatedNew);
            List<String> ordered = new ArrayList<>();
            for (Shape shape : graph.shapes()) {
                String id = shape.getId().toString();
                if (isolatedSet.contains(id)) {
                    ordered.add(id);
                }
            }
            Map<String, double[]> sizes = sizesOf(graph, ordered);
            Map<String, double[]> grid = gridLayout(ordered, sizes);
            double[] box = bounds(grid, sizes);
            placeComponent(graph, ordered, grid, cursorX - box[0], baseY - box[1]);
            cursorX += (box[2] - box[0]) + GAP;
        }

        for (List<String> component : fresh) {
            Map<String, double[]> sizes = sizesOf(graph, component);
            Map<String, double[]> positions = layoutComponent(board, graph, component, sizes);
            double[] box = bounds(positions, sizes);
            placeComponent(graph, component, positions, cursorX - box[0], baseY - box[1]);
            cursorX += (box[2] - box[0]) + GAP;
        }

        for (List<String> component : anchored) {
            Map<String, double[]> sizes = sizesOf(graph, component);
            Map<String, double[]> positions = layoutComponent(board, graph, component, sizes);
            double[] box = bounds(positions, sizes);
            double[] old = existingBoundsOfComponent(graph, component, newIds);
            placeComponent(graph, component, positions, old[0] - box[0], old[1] - box[1]);
        }
        log.info("增量重排：boardId={} 原地分量={} 新分量={} 孤立新节点={}", board.getId(), anchored.size(),
                fresh.size(), isolatedNew.size());
    }

    /** 分量布局：思维导图按画布风格二选一，流程图永远分层 */
    private Map<String, double[]> layoutComponent(Board board, Graph graph, List<String> component,
            Map<String, double[]> sizes) {
        Map<String, Integer> levels = new HashMap<>();
        boolean mindmap = false;
        for (String id : component) {
            Shape shape = graph.shapeById().get(id);
            int level = shape == null ? 0 : shape.getLevel();
            levels.put(id, level);
            mindmap |= level > 0;
        }
        List<String[]> componentEdges = edgesWithin(graph, new HashSet<>(component));
        if (!mindmap) {
            return GraphLayoutService.layoutFlowchart(component, componentEdges, sizes);
        }
        int maxNodes = properties.ai().layout().radialMaxNodes();
        boolean radial = board.getLayoutStyle() == BoardLayoutStyle.RADIAL && graph.shapes().size() <= maxNodes;
        return radial
                ? GraphLayoutService.layoutMindmapTree(component, componentEdges, sizes, levels)
                : GraphLayoutService.layoutLogicChart(component, componentEdges, sizes, levels);
    }

    /** 新建节点；标题/类型缺省时给出可用默认值，尺寸按标题自适应且不小于动作给出的尺寸 */
    private UUID createShape(UUID boardId, BoardAction.CreateShape action) {
        String title = action.title() == null || action.title().isBlank() ? "节点" : action.title();
        NodeSizeEstimator.NodeSize size = NodeSizeEstimator.estimate(title);
        Shape shape = new Shape(boardId, title,
                action.shape() == null || action.shape().isBlank() ? "ellipse" : action.shape(),
                action.level(),
                action.x(),
                action.y(),
                Math.max(action.width(), size.width()),
                Math.max(action.height(), size.height()));
        return shapeRepository.save(shape).getId();
    }

    /** 更新已有节点：只支持真实 id，且必须属于当前画布；标题变长时同步放大尺寸 */
    private UpdateOutcome update(UUID boardId, BoardAction.UpdateShape action) {
        UUID shapeId = parseUuid(action.shapeId());
        if (shapeId == null) {
            log.warn("忽略非法 update_shape 目标：{}", action.shapeId());
            return UpdateOutcome.skipped();
        }
        var found = shapeRepository.findById(shapeId)
                .filter(shape -> shape.getBoardId().equals(boardId));
        if (found.isEmpty()) {
            log.warn("更新目标不存在或不属于画布：shapeId={}", shapeId);
            return UpdateOutcome.skipped();
        }
        Shape shape = found.get();
        int oldLevel = shape.getLevel();
        double oldWidth = shape.getWidth();
        double oldHeight = shape.getHeight();
        shape.applyAgentEdit(action.title(), action.shape(), action.level());
        NodeSizeEstimator.NodeSize size = NodeSizeEstimator.estimate(shape.getTitle());
        shape.growTo(size.width(), size.height());
        boolean relayout = shape.getLevel() != oldLevel
                || shape.getWidth() != oldWidth
                || shape.getHeight() != oldHeight;
        log.info("更新节点：shapeId={} title={}", shapeId, shape.getTitle());
        return new UpdateOutcome(true, relayout, shapeId);
    }

    /** 连线：端点解析为真实 id，并校验两端都属于当前画布；返回是否真正生效 */
    private boolean connect(UUID boardId, BoardAction.Connect action, Map<String, UUID> shapeIdMap) {
        UUID fromShapeId = resolveShapeId(action.fromShapeId(), shapeIdMap);
        UUID toShapeId = resolveShapeId(action.toShapeId(), shapeIdMap);
        if (fromShapeId == null || toShapeId == null || fromShapeId.equals(toShapeId)) {
            return false;
        }
        if (!nodeExistsInBoard(fromShapeId, boardId) || !nodeExistsInBoard(toShapeId, boardId)) {
            log.warn("忽略悬空连线：{} -> {}", fromShapeId, toShapeId);
            return false;
        }
        edgeRepository.save(new BoardEdge(boardId, fromShapeId, toShapeId, action.label(),
                action.style(), action.color(), action.weight() == null ? 2 : action.weight()));
        return true;
    }

    /** 删除节点及其连线；只允许删除当前画布的节点；返回是否真正生效 */
    private boolean delete(UUID boardId, BoardAction.DeleteShape action) {
        UUID shapeId = parseUuid(action.shapeId());
        if (shapeId == null) {
            log.warn("忽略非法 delete_shape 目标：{}", action.shapeId());
            return false;
        }
        var found = shapeRepository.findById(shapeId)
                .filter(shape -> shape.getBoardId().equals(boardId));
        if (found.isEmpty()) {
            log.warn("删除目标不存在或不属于画布：shapeId={}", shapeId);
            return false;
        }
        Shape shape = found.get();
        edgeRepository.deleteAll(edgeRepository.findByFromShapeIdOrToShapeId(shapeId, shapeId));
        shapeRepository.delete(shape);
        log.info("删除节点：shapeId={} title={}", shapeId, shape.getTitle());
        return true;
    }

    private boolean nodeExistsInBoard(UUID nodeId, UUID boardId) {
        return shapeRepository.findById(nodeId)
                .map(shape -> shape.getBoardId().equals(boardId))
                .orElse(false);
    }

    private UUID resolveShapeId(String shapeId, Map<String, UUID> shapeIdMap) {
        if (shapeId == null || shapeId.isBlank()) {
            return null;
        }
        UUID mapped = shapeIdMap.get(shapeId);
        return mapped != null ? mapped : parseUuid(shapeId);
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

    /** 读取整张图的节点、连线、无向邻接表与弱连通分量 */
    private Graph loadGraph(UUID boardId) {
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        Map<String, Shape> shapeById = new LinkedHashMap<>();
        Map<String, Set<String>> undirected = new LinkedHashMap<>();
        for (Shape shape : shapes) {
            String id = shape.getId().toString();
            shapeById.put(id, shape);
            undirected.put(id, new LinkedHashSet<>());
        }
        for (BoardEdge edge : edges) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            if (undirected.containsKey(from) && undirected.containsKey(to)) {
                undirected.get(from).add(to);
                undirected.get(to).add(from);
            }
        }
        List<List<String>> components = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (Shape shape : shapes) {
            String start = shape.getId().toString();
            if (!visited.add(start)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                for (String next : undirected.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            components.add(component);
        }
        return new Graph(shapes, edges, shapeById, undirected, components);
    }

    /** 分量内两端都在集合中的连线（保留方向） */
    private List<String[]> edgesWithin(Graph graph, Set<String> nodes) {
        List<String[]> result = new ArrayList<>();
        for (BoardEdge edge : graph.edges()) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            if (nodes.contains(from) && nodes.contains(to)) {
                result.add(new String[] { from, to });
            }
        }
        return result;
    }

    private Map<String, double[]> sizesOf(Graph graph, List<String> ids) {
        Map<String, double[]> sizes = new LinkedHashMap<>();
        for (String id : ids) {
            Shape shape = graph.shapeById().get(id);
            if (shape != null) {
                sizes.put(id, new double[] { shape.getWidth(), shape.getHeight() });
            }
        }
        return sizes;
    }

    private void placeComponent(Graph graph, List<String> component, Map<String, double[]> positions,
            double offsetX, double offsetY) {
        for (String id : component) {
            Shape shape = graph.shapeById().get(id);
            double[] pos = positions.get(id);
            if (shape != null && pos != null) {
                shape.update(pos[0] + offsetX, pos[1] + offsetY);
            }
        }
    }

    /** 分量内已有节点（非本批新建）的旧包围盒 */
    private double[] existingBoundsOfComponent(Graph graph, List<String> component, Set<String> newIds) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean found = false;
        for (String id : component) {
            if (newIds.contains(id)) {
                continue;
            }
            Shape shape = graph.shapeById().get(id);
            if (shape == null) {
                continue;
            }
            found = true;
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
        }
        if (!found) {
            throw new IllegalStateException("原地重排的分量缺少已有节点");
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    /** 画布内容的包围盒（排除本次会按光标重新放置的节点）；空画布返回 null */
    private double[] boundsExcluding(Graph graph, Set<String> excluded) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean found = false;
        for (Shape shape : graph.shapes()) {
            if (excluded.contains(shape.getId().toString())) {
                continue;
            }
            found = true;
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
        }
        return found ? new double[] { minX, minY, maxX, maxY } : null;
    }

    /** 布局结果的包围盒 [minX, minY, maxX, maxY] */
    private double[] bounds(Map<String, double[]> positions, Map<String, double[]> sizes) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            double[] pos = entry.getValue();
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxX = Math.max(maxX, pos[0] + widthOf(sizes, entry.getKey()));
            maxY = Math.max(maxY, pos[1] + heightOf(sizes, entry.getKey()));
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    /** 网格布局：按真实宽高排列，用于没有任何连线的并列新节点 */
    private Map<String, double[]> gridLayout(List<String> nodeIds, Map<String, double[]> sizes) {
        int rows = (nodeIds.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
        double[] columnWidth = new double[GRID_COLUMNS];
        double[] rowHeight = new double[rows];
        for (int index = 0; index < nodeIds.size(); index++) {
            String id = nodeIds.get(index);
            columnWidth[index % GRID_COLUMNS] = Math.max(columnWidth[index % GRID_COLUMNS], widthOf(sizes, id));
            rowHeight[index / GRID_COLUMNS] = Math.max(rowHeight[index / GRID_COLUMNS], heightOf(sizes, id));
        }
        double[] columnX = new double[GRID_COLUMNS];
        for (int column = 1; column < GRID_COLUMNS; column++) {
            columnX[column] = columnX[column - 1] + columnWidth[column - 1] + GRID_GAP;
        }
        double[] rowY = new double[rows];
        for (int row = 1; row < rows; row++) {
            rowY[row] = rowY[row - 1] + rowHeight[row - 1] + GRID_GAP;
        }
        Map<String, double[]> positions = new LinkedHashMap<>();
        for (int index = 0; index < nodeIds.size(); index++) {
            positions.put(nodeIds.get(index), new double[] { columnX[index % GRID_COLUMNS], rowY[index / GRID_COLUMNS] });
        }
        return positions;
    }

    private static double widthOf(Map<String, double[]> sizes, String id) {
        double[] size = sizes == null ? null : sizes.get(id);
        return size == null || size[0] <= 0 ? DEFAULT_NODE_W : size[0];
    }

    private static double heightOf(Map<String, double[]> sizes, String id) {
        double[] size = sizes == null ? null : sizes.get(id);
        return size == null || size[1] <= 0 ? DEFAULT_NODE_H : size[1];
    }

    private Set<String> toStrings(Set<UUID> ids) {
        Set<String> result = new HashSet<>();
        for (UUID id : ids) {
            result.add(id.toString());
        }
        return result;
    }

    private record Graph(List<Shape> shapes, List<BoardEdge> edges, Map<String, Shape> shapeById,
            Map<String, Set<String>> undirected, List<List<String>> components) {
    }

    private record UpdateOutcome(boolean applied, boolean relayout, UUID shapeId) {
        static UpdateOutcome skipped() {
            return new UpdateOutcome(false, false, null);
        }
    }
}
