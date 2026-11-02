package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;

/**
 * 画布动作落库器：校验、布局、原子应用。
 *
 * 布局模式由最终动作图决定，不再依赖任务类型：
 * - 全是新建节点且成树：径向树布局
 * - 全是新建节点但成环或含判断节点：分层布局
 * - 新建节点挂接到已有节点：新子图在锚点子树右侧展开
 * - 只有节点没有边：网格布局
 * - 只有更新/删除：不重排
 *
 * 调用方负责开启事务：中途失败必须整体回滚，不能留下半张图。
 */
@Component
public class BoardActionApplier {

    private static final Logger log = LoggerFactory.getLogger(BoardActionApplier.class);

    /** 节点间距与画布边距 */
    private static final double GAP = 120;
    private static final double MARGIN = 120;
    /** 网格布局列数与间距 */
    private static final int GRID_COLUMNS = 5;
    private static final double GRID_X_GAP = 220;
    private static final double GRID_Y_GAP = 140;

    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;

    public BoardActionApplier(ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository) {
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
    }

    /**
     * 计算新建节点的坐标。返回的新动作列表只替换 create_shape 的 x/y，其他动作原样保留。
     */
    public List<BoardAction> layout(UUID boardId, List<BoardAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<BoardAction.CreateShape> creates = actions.stream()
                .filter(action -> action instanceof BoardAction.CreateShape)
                .map(action -> (BoardAction.CreateShape) action)
                .filter(shape -> shape.id() != null && !shape.id().isBlank())
                .toList();
        if (creates.isEmpty()) {
            return actions;
        }
        List<String[]> allEdges = new ArrayList<>();
        List<String[]> newEdges = new ArrayList<>();
        Set<String> createIds = new HashSet<>();
        creates.forEach(shape -> createIds.add(shape.id()));
        for (BoardAction action : actions) {
            if (action instanceof BoardAction.Connect connect
                    && connect.fromShapeId() != null && !connect.fromShapeId().isBlank()
                    && connect.toShapeId() != null && !connect.toShapeId().isBlank()) {
                String[] edge = new String[] { connect.fromShapeId(), connect.toShapeId() };
                allEdges.add(edge);
                if (createIds.contains(edge[0]) || createIds.contains(edge[1])) {
                    newEdges.add(edge);
                }
            }
        }
        List<String> nodeIds = creates.stream().map(BoardAction.CreateShape::id).toList();
        Map<String, double[]> sizes = new HashMap<>();
        for (BoardAction.CreateShape shape : creates) {
            sizes.put(shape.id(), new double[] { shape.width(), shape.height() });
        }
        Map<String, double[]> positions;
        if (allEdges.isEmpty()) {
            positions = gridLayout(nodeIds, sizes);
            positions = offsetToRight(boardId, positions);
        } else if (newEdges.isEmpty()) {
            // 只有更新/删除或连线都落在已有节点上，不需要为新节点布局
            return actions;
        } else if (newEdges.size() == allEdges.size() && all(createIds, allEdges)) {
            positions = layoutGraph(nodeIds, allEdges, sizes);
            positions = offsetToRight(boardId, positions);
        } else {
            positions = layoutAttached(boardId, nodeIds, newEdges, sizes);
        }
        return rebuildWithPositions(actions, positions);
    }

    /** 在调用方事务内应用动作；失败直接抛出，由事务整体回滚。返回实际生效数量供步骤检查 */
    public ApplyReport apply(UUID boardId, List<BoardAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return ApplyReport.empty();
        }
        Map<String, UUID> shapeIdMap = new HashMap<>();
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
                if (createShape.id() != null && !createShape.id().isBlank()) {
                    shapeIdMap.put(createShape.id(), created);
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
                    if (update(boardId, update)) {
                        updatedShapes++;
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
        return new ApplyReport(createdShapes, createdEdges, updatedShapes, deletedShapes, skipped);
    }

    /**
     * 整理画布：按连通分量重新布局已有节点，不新增、不删除。
     * 树结构用径向布局，其余用分层布局；多棵树从左到右排列。
     */
    public void layoutExistingBoard(UUID boardId) {
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        if (shapes.isEmpty()) {
            return;
        }
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        Map<String, Shape> shapeById = new HashMap<>();
        Map<String, double[]> sizes = new HashMap<>();
        Map<String, Set<String>> undirected = new HashMap<>();
        for (Shape shape : shapes) {
            String id = shape.getId().toString();
            shapeById.put(id, shape);
            sizes.put(id, new double[] { shape.getWidth(), shape.getHeight() });
            undirected.put(id, new HashSet<>());
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
        double cursorX = MARGIN;
        for (List<String> component : components) {
            Set<String> nodeSet = new HashSet<>(component);
            List<String[]> treeEdges = new ArrayList<>();
            for (BoardEdge edge : edges) {
                String from = edge.getFromShapeId().toString();
                String to = edge.getToShapeId().toString();
                if (nodeSet.contains(from) && nodeSet.contains(to)) {
                    treeEdges.add(new String[] { from, to });
                }
            }
            boolean isMindmapTree = GraphLayoutService.isTree(component, treeEdges)
                    && component.stream().anyMatch(id -> shapeById.get(id).getLevel() > 0);
            Map<String, double[]> positions = isMindmapTree
                    ? GraphLayoutService.layoutMindmapTree(component, treeEdges, sizes)
                    : GraphLayoutService.layoutFlowchart(component, treeEdges, sizes);
            double[] box = boundingBox(positions);
            double offsetX = cursorX - box[0];
            double offsetY = MARGIN - box[1];
            for (Map.Entry<String, double[]> entry : positions.entrySet()) {
                Shape shape = shapeById.get(entry.getKey());
                if (shape != null) {
                    shape.update(entry.getValue()[0] + offsetX, entry.getValue()[1] + offsetY);
                    shapeRepository.save(shape);
                }
            }
            cursorX += (box[2] - box[0]) + GAP;
        }
        log.info("整理画布：boardId={} 连通分量={} 节点={}", boardId, components.size(), shapes.size());
    }

    /** 新建节点；标题/类型缺省时给出可用默认值 */
    private UUID createShape(UUID boardId, BoardAction.CreateShape action) {
        Shape shape = new Shape(boardId,
                action.title() == null || action.title().isBlank() ? "节点" : action.title(),
                action.shape() == null || action.shape().isBlank() ? "ellipse" : action.shape(),
                action.level(),
                action.x(),
                action.y(),
                action.width() <= 0 ? 140 : action.width(),
                action.height() <= 0 ? 60 : action.height());
        return shapeRepository.save(shape).getId();
    }

    /** 更新已有节点：只支持真实 id，且必须属于当前画布；返回是否真正生效 */
    private boolean update(UUID boardId, BoardAction.UpdateShape action) {
        UUID shapeId = parseUuid(action.shapeId());
        if (shapeId == null) {
            log.warn("忽略非法 update_shape 目标：{}", action.shapeId());
            return false;
        }
        var found = shapeRepository.findById(shapeId)
                .filter(shape -> shape.getBoardId().equals(boardId));
        if (found.isEmpty()) {
            log.warn("更新目标不存在或不属于画布：shapeId={}", shapeId);
            return false;
        }
        Shape shape = found.get();
        shape.applyAgentEdit(action.title(), action.shape(), action.level());
        shapeRepository.save(shape);
        log.info("更新节点：shapeId={} title={}", shapeId, shape.getTitle());
        return true;
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

    /** 全是新节点：树用径向，非树用分层 */
    private Map<String, double[]> layoutGraph(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        if (GraphLayoutService.isTree(nodeIds, edges)) {
            return GraphLayoutService.layoutMindmapTree(nodeIds, edges, sizes);
        }
        return GraphLayoutService.layoutFlowchart(nodeIds, edges, sizes);
    }

    /** 新节点挂接到已有节点：对新建部分布局，再平移到锚点子树右侧 */
    private Map<String, double[]> layoutAttached(UUID boardId, List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        Set<String> newNodeIds = new HashSet<>(nodeIds);
        List<String[]> innerEdges = edges.stream()
                .filter(edge -> newNodeIds.contains(edge[0]) && newNodeIds.contains(edge[1]))
                .toList();
        Map<String, double[]> positions = innerEdges.isEmpty()
                ? gridLayout(nodeIds, sizes)
                : layoutGraph(nodeIds, innerEdges, sizes);
        double[] box = boundingBox(positions);
        double[] anchorBounds = anchorBounds(boardId, edges, newNodeIds);
        double offsetX;
        double offsetY;
        if (anchorBounds == null) {
            double[] existing = existingBounds(boardId);
            offsetX = existing == null ? MARGIN - box[0] : existing[2] + GAP - box[0];
            offsetY = existing == null ? MARGIN - box[1] : existing[1] - box[1];
        } else {
            offsetX = anchorBounds[2] + GAP - box[0];
            offsetY = anchorBounds[1] - box[1];
        }
        Map<String, double[]> shifted = new LinkedHashMap<>();
        positions.forEach((id, pos) -> shifted.put(id, new double[] { pos[0] + offsetX, pos[1] + offsetY }));
        return shifted;
    }

    /** 计算锚点及其已有子树的包围盒 */
    private double[] anchorBounds(UUID boardId, List<String[]> edges, Set<String> newNodeIds) {
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        Map<UUID, Shape> shapeById = new HashMap<>();
        shapes.forEach(shape -> shapeById.put(shape.getId(), shape));
        List<BoardEdge> existingEdges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        Map<UUID, Set<UUID>> undirected = new HashMap<>();
        shapeById.keySet().forEach(id -> undirected.put(id, new HashSet<>()));
        for (BoardEdge edge : existingEdges) {
            undirected.computeIfAbsent(edge.getFromShapeId(), key -> new HashSet<>()).add(edge.getToShapeId());
            undirected.computeIfAbsent(edge.getToShapeId(), key -> new HashSet<>()).add(edge.getFromShapeId());
        }
        List<UUID> anchors = new ArrayList<>();
        for (String[] edge : edges) {
            for (String endpoint : edge) {
                if (newNodeIds.contains(endpoint)) {
                    continue;
                }
                UUID id = parseUuid(endpoint);
                if (id != null && shapeById.containsKey(id)) {
                    anchors.add(id);
                }
            }
        }
        if (anchors.isEmpty()) {
            return null;
        }
        Set<UUID> visited = new HashSet<>();
        Deque<UUID> queue = new ArrayDeque<>(anchors);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            Shape shape = shapeById.get(current);
            if (shape == null) {
                continue;
            }
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
            for (UUID next : undirected.getOrDefault(current, Set.of())) {
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return minX == Double.MAX_VALUE ? null : new double[] { minX, minY, maxX, maxY };
    }

    /** 网格布局，用于没有任何连线的并列节点 */
    private Map<String, double[]> gridLayout(List<String> nodeIds, Map<String, double[]> sizes) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        for (int index = 0; index < nodeIds.size(); index++) {
            int row = index / GRID_COLUMNS;
            int column = index % GRID_COLUMNS;
            positions.put(nodeIds.get(index), new double[] { column * GRID_X_GAP, row * GRID_Y_GAP });
        }
        return positions;
    }

    /** 把一组坐标整体平移到画布已有内容右侧 */
    private Map<String, double[]> offsetToRight(UUID boardId, Map<String, double[]> positions) {
        double[] box = boundingBox(positions);
        double[] existing = existingBounds(boardId);
        double offsetX;
        double offsetY;
        if (existing == null) {
            offsetX = MARGIN - box[0];
            offsetY = MARGIN - box[1];
        } else {
            offsetX = existing[2] + GAP - box[0];
            offsetY = existing[1] - box[1];
        }
        Map<String, double[]> shifted = new LinkedHashMap<>();
        positions.forEach((id, pos) -> shifted.put(id, new double[] { pos[0] + offsetX, pos[1] + offsetY }));
        return shifted;
    }

    /** 用布局坐标重建 create_shape 动作 */
    private List<BoardAction> rebuildWithPositions(List<BoardAction> actions, Map<String, double[]> positions) {
        return actions.stream().map(action -> {
            if (action instanceof BoardAction.CreateShape shape && shape.id() != null) {
                double[] pos = positions.get(shape.id());
                if (pos != null) {
                    return new BoardAction.CreateShape(shape.id(), shape.title(), shape.shape(), shape.level(),
                            pos[0], pos[1],
                            shape.width() <= 0 ? 140 : shape.width(),
                            shape.height() <= 0 ? 60 : shape.height());
                }
            }
            return action;
        }).toList();
    }

    private boolean all(Set<String> ids, List<String[]> edges) {
        for (String[] edge : edges) {
            if (!ids.contains(edge[0]) || !ids.contains(edge[1])) {
                return false;
            }
        }
        return true;
    }

    private double[] boundingBox(Map<String, double[]> positions) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            double[] pos = entry.getValue();
            // 尺寸未知时按典型节点估算
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxX = Math.max(maxX, pos[0] + GRID_X_GAP / 2);
            maxY = Math.max(maxY, pos[1] + GRID_Y_GAP / 2);
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    /** 画布已有节点的包围盒；空画布返回 null */
    private double[] existingBounds(UUID boardId) {
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        if (shapes.isEmpty()) {
            return null;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Shape shape : shapes) {
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
        }
        return new double[] { minX, minY, maxX, maxY };
    }
}
