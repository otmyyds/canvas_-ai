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
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;
    private final BoardLayoutCache layoutCache;

    public BoardActionApplier(ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository,
            BoardRepository boardRepository, AppProperties properties, ObjectMapper objectMapper,
            BoardLayoutCache layoutCache) {
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.boardRepository = boardRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.layoutCache = layoutCache;
    }

    /** 用户调整同级顺序时提交的一项：连线 id + 逻辑图一级分支的目标侧 */
    public record ChildOrder(UUID edgeId, String side) {
    }

    /** 纳管画布上手绘的自由图形：本地 tldraw id + 节点内容
     * @param localId 本地 tldraw id 本地id表示
     * @param visual 节点内容 包括位置、尺寸、样式等
     * */
    public record AdoptNode(String localId, String title, String shape, int level, double x, double y,
            double width, double height, Map<String, String> visual) {
    }

    /** 纳管画布上手绘的自由连线：两端为本地 tldraw id */
    public record AdoptEdge(String fromLocalId, String toLocalId) {
    }

    /** 纳管结果：本地 id → 落库后的节点，供前端替换本地图形 */
    public record AdoptedNode(String localId, Shape shape) {
    }

    public record AdoptionResult(List<AdoptedNode> nodes, List<BoardEdge> edges) {
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
        // 本批新连线追加到各父节点末尾，保持 AI 给出的兄弟顺序
        Map<UUID, Integer> nextSortOrders = nextSortOrders(boardId);
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
                // 得到创建节点后的真实 id
                UUID created = createShape(boardId, createShape);
                createdShapes++;
                // 将真实 id 记入结果
                createdNodes.add(new ApplyReport.CreatedShape(created, createShape.title(), createShape.level()));
                if (createShape.id() != null && !createShape.id().isBlank()) {
                    // 建立虚拟id与真实id的映射
                    shapeIdMap.put(createShape.id(), created);
                    createdIds.add(created);
                }
            }
        }
        // 第二遍：更新、连线、删除
        for (BoardAction action : actions) {
            switch (action) {
                case BoardAction.Connect connect -> {
                    // 判断新建的图形id是否在画布中
                    if (connect(boardId, connect, shapeIdMap, nextSortOrders)) {
                        // 如果存在就需要建立一条连线
                        createdEdges++;
                    } else {
                        // 不存在则跳过
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
        // 获取可见节点，隐藏节点不参与
        Set<String> visible = visibleNodes(graph);
        double cursorX = MARGIN;
        for (List<String> component : graph.components()) {
            // 折叠节点的后代不参与布局：整块图按剩余可见节点重新打包
            List<String> shown = visibleIn(visible, component);
            if (shown.isEmpty()) {
                continue;
            }
            // 分量布局内部会先按风格统一尺寸：逻辑图 fork 节点收缩，其他节点按 geo 标签放大
            ComponentLayout layout = layoutComponent(board, graph, shown);
            Map<String, double[]> positions = layout.positions();
            Map<String, double[]> sizes = sizesOf(graph, shown);
            double[] box = bounds(positions, sizes);
            placeComponent(graph, shown, positions, cursorX - box[0], MARGIN - box[1]);
            cursorX += (box[2] - box[0]) + GAP;
        }
        log.info("整理画布：boardId={} 连通分量={} 节点={}", boardId, graph.components().size(), graph.shapes().size());
    }

    /**
     * 用户手动新建节点：
     * - parentId 为空：新建独立根节点（中心主题，level 0）；
     * - parentId 非空：挂到父节点下并自动连线。
     *
     * 层级跟随所属分量：单节点分量视为思维导图根，新子节点取父层级 + 1；
     * 已有连线的纯 level 0 分量仍按流程图保持同层。
     * 建完后重排整个分量，新节点由布局算法放置；调用方负责开启事务。
     */
    @Transactional
    public Shape createNode(UUID boardId, UUID parentId, String title) {
        boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));

        // 无父节点：新建独立根节点，位置由孤立新节点布局决定
        if (parentId == null) {
            String nodeTitle = title == null || title.isBlank() ? "中心主题" : title.trim();
            String tempId = "new-" + UUID.randomUUID();
            ApplyReport report = apply(boardId, List.of(
                    new BoardAction.CreateShape(tempId, nodeTitle, "ellipse", 0, MARGIN, MARGIN, 0, 0)));
            UUID createdId = report.createdNodes().get(0).id();
            log.info("手动新建根节点：boardId={} shapeId={}", boardId, createdId);
            return shapeRepository.findById(createdId)
                    .orElseThrow(() -> new IllegalStateException("新建根节点失败：" + createdId));
        }

        Graph graph = loadGraph(boardId);
        Shape parent = graph.shapeById().get(parentId.toString());
        if (parent == null) {
            throw new IllegalArgumentException("父节点不存在：" + parentId);
        }
        // 新节点必须可见：父节点折叠时先展开
        parent.collapse(false);
        List<String> component = componentOf(graph, parentId.toString());
        // 单节点分量视为思维导图根：新建根的第一个子节点必须取 level 1，
        // 否则整个分量会被判定为流程图，逻辑图与树形图算法都不会生效
        boolean mindmap = component.size() == 1 || component.stream()
                .map(id -> graph.shapeById().get(id))
                .anyMatch(shape -> shape != null && shape.getLevel() > 0);
        int level = mindmap ? parent.getLevel() + 1 : parent.getLevel();
        String nodeTitle = title == null || title.isBlank() ? "新节点" : title.trim();
        // 临时 id 让同一批动作里的 connect 能引用到这个新节点
        String tempId = "new-" + UUID.randomUUID();
        ApplyReport report = apply(boardId, List.of(
                new BoardAction.CreateShape(tempId, nodeTitle, parent.getShape(), level,
                        parent.getX(), parent.getY(), 0, 0),
                new BoardAction.Connect(parentId.toString(), tempId, null, "curve", null, 2)));
        UUID createdId = report.createdNodes().get(0).id();
        log.info("手动新建节点：boardId={} parentId={} shapeId={} level={}", boardId, parentId, createdId, level);
        return shapeRepository.findById(createdId)
                .orElseThrow(() -> new IllegalStateException("新建节点失败：" + createdId));
    }

    /**
     * 用户删除节点：思维导图分量沿父子方向删掉整棵子树，其他分量（流程图 / 孤立节点）只删节点本身；
     * 关联连线一并删除，剩余节点按删除后的连通分组原地重排，不留空洞。
     * 调用方负责开启事务。
     */
    @Transactional
    public List<UUID> deleteForUser(UUID boardId, UUID shapeId) {
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));
        Graph graph = loadGraph(boardId);
        if (!graph.shapeById().containsKey(shapeId.toString())) {
            return List.of();
        }
        List<String> component = componentOf(graph, shapeId.toString());
        boolean mindmap = component.stream()
                .map(id -> graph.shapeById().get(id))
                .anyMatch(shape -> shape != null && shape.getLevel() > 0);
        Set<String> targets = new LinkedHashSet<>();
        targets.add(shapeId.toString());
        if (mindmap) {
            collectSubtree(graph, shapeId.toString(), targets);
        }
        Set<String> componentSet = new LinkedHashSet<>(component);
        List<UUID> deletedIds = targets.stream().map(UUID::fromString).toList();
        edgeRepository.deleteAll(graph.edges().stream()
                .filter(edge -> targets.contains(edge.getFromShapeId().toString())
                        || targets.contains(edge.getToShapeId().toString()))
                .toList());
        shapeRepository.deleteAllById(deletedIds);

        // 删除后分量可能拆成多块：每块按删除前的包围盒锚定，原地整理
        Graph after = loadGraph(boardId);
        Set<String> visible = visibleNodes(after);
        for (List<String> group : after.components()) {
            List<String> shown = visibleIn(visible, group);
            if (shown.isEmpty() || !componentSet.containsAll(group)) {
                continue;
            }
            double[] old = boundsOf(graph, shown);
            ComponentLayout layout = layoutComponent(board, after, shown);
            placeAnchored(after, shown, layout, old);
        }
        log.info("删除节点：boardId={} shapeId={} 连带删除={} mindmap={}", boardId, shapeId, deletedIds.size(), mindmap);
        return deletedIds;
    }

    /**
     * 用户调整某个节点的子节点顺序（逻辑图一级分支同时提交左右侧）。
     * 逐项写入 sortOrder/side 后重排该节点所在分量，整块图按原包围盒左上角锚定。
     * 调用方负责开启事务。
     */
    @Transactional
    public List<BoardEdge> reorderChildren(UUID boardId, UUID parentId, List<ChildOrder> children) {
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));
        Shape parent = shapeRepository.findById(parentId)
                .filter(shape -> shape.getBoardId().equals(boardId))
                .orElseThrow(() -> new IllegalArgumentException("父节点不存在：" + parentId));
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        Map<UUID, BoardEdge> byId = new LinkedHashMap<>();
        for (ChildOrder order : children) {
            if (order == null || order.edgeId() == null || byId.containsKey(order.edgeId())) {
                continue;
            }
            BoardEdge edge = edgeRepository.findById(order.edgeId())
                    .filter(found -> found.getBoardId().equals(boardId)
                            && found.getFromShapeId().equals(parentId))
                    .orElseThrow(() -> new IllegalArgumentException("连线不属于该父节点：" + order.edgeId()));
            byId.put(order.edgeId(), edge);
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        // side 只对一级分支有意义，其余层级统一清空
        boolean rootBranches = parent.getLevel() == 0;
        List<BoardEdge> ordered = new ArrayList<>();
        int index = 1;
        for (ChildOrder order : children) {
            if (order == null) {
                continue;
            }
            BoardEdge edge = byId.get(order.edgeId());
            if (edge == null) {
                continue;
            }
            edge.reorder(index++, rootBranches ? normalizeSide(order.side()) : null);
            ordered.add(edge);
        }

        Graph graph = loadGraph(boardId);
        List<String> component = visibleIn(visibleNodes(graph), componentOf(graph, parentId.toString()));
        if (component.isEmpty()) {
            return ordered;
        }
        double[] old = boundsOf(graph, component);
        ComponentLayout layout = layoutComponent(board, graph, component);
        placeAnchored(graph, component, layout, old);
        log.info("调整同级顺序：boardId={} parentId={} 子节点={}", boardId, parentId, ordered.size());
        return ordered;
    }

    /**
     * 折叠/展开节点：折叠后全部后代不参与布局，剩余可见节点按原包围盒锚定重排。
     * 调用方负责开启事务。
     */
    @Transactional
    public Shape setCollapsed(UUID boardId, UUID shapeId, boolean collapsed) {
        Board board = boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));
        Shape shape = shapeRepository.findById(shapeId)
                .filter(found -> found.getBoardId().equals(boardId))
                .orElseThrow(() -> new IllegalArgumentException("节点不存在：" + shapeId));
        if (shape.isCollapsed() == collapsed) {
            return shape;
        }
        shape.collapse(collapsed);

        Graph graph = loadGraph(boardId);
        List<String> component = visibleIn(visibleNodes(graph), componentOf(graph, shapeId.toString()));
        if (component.isEmpty()) {
            return shape;
        }
        double[] old = boundsOf(graph, component);
        ComponentLayout layout = layoutComponent(board, graph, component);
        placeAnchored(graph, component, layout, old);
        log.info("切换节点折叠：boardId={} shapeId={} collapsed={} 可见节点={}",
                boardId, shapeId, collapsed, component.size());
        return shape;
    }

    /**
     * 纳管画布上手绘的自由图形与连线：按前端给出的坐标尺寸原样落库，不触发布局。
     * 前端随后删除本地图形，改由结构同步统一渲染；调用方负责开启事务。
     */
    @Transactional
    public AdoptionResult adopt(UUID boardId, List<AdoptNode> nodes, List<AdoptEdge> edges) {
        boardRepository.findByIdForUpdate(boardId)
                .orElseThrow(() -> new IllegalStateException("画布不存在：" + boardId));
        if (nodes == null || nodes.isEmpty()) {
            return new AdoptionResult(List.of(), List.of());
        }
        Map<UUID, Integer> nextSortOrders = nextSortOrders(boardId);
        Map<String, Shape> createdByLocalId = new LinkedHashMap<>();
        List<AdoptedNode> adoptedNodes = new ArrayList<>();
        for (AdoptNode node : nodes) {
            if (node == null || node.localId() == null || node.localId().isBlank()
                    || createdByLocalId.containsKey(node.localId())) {
                continue;
            }
            Shape shape = adoptNode(boardId, node);
            shapeRepository.save(shape);
            createdByLocalId.put(node.localId(), shape);
            adoptedNodes.add(new AdoptedNode(node.localId(), shape));
        }
        List<BoardEdge> adoptedEdges = new ArrayList<>();
        if (edges != null) {
            for (AdoptEdge edge : edges) {
                if (edge == null) {
                    continue;
                }
                UUID fromId = resolveAdoptId(edge.fromLocalId(), createdByLocalId, boardId);
                UUID toId = resolveAdoptId(edge.toLocalId(), createdByLocalId, boardId);
                if (fromId == null || toId == null || fromId.equals(toId)) {
                    continue;
                }
                BoardEdge created = new BoardEdge(boardId, fromId, toId, null, "curve", null, 2);
                int sortOrder = nextSortOrders.getOrDefault(fromId, 1);
                nextSortOrders.put(fromId, sortOrder + 1);
                created.reorder(sortOrder, null);
                edgeRepository.save(created);
                adoptedEdges.add(created);
            }
        }
        log.info("纳管自由图形：boardId={} 节点={} 连线={}", boardId, adoptedNodes.size(), adoptedEdges.size());
        return new AdoptionResult(adoptedNodes, adoptedEdges);
    }

    private Shape adoptNode(UUID boardId, AdoptNode node) {
        String title = node.title() == null || node.title().isBlank() ? "节点" : node.title().trim();
        NodeSizeEstimator.NodeSize estimate = NodeSizeEstimator.estimate(title);
        double width = node.width() > 0 ? node.width() : estimate.width();
        double height = node.height() > 0 ? node.height() : estimate.height();
        Shape shape = new Shape(boardId, title,
                node.shape() == null || node.shape().isBlank() ? "rectangle" : node.shape(),
                Math.max(node.level(), 0), node.x(), node.y(), width, height);
        if (node.visual() != null && !node.visual().isEmpty()) {
            try {
                shape.applyVisual(objectMapper.writeValueAsString(node.visual()));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("视觉属性序列化失败", exception);
            }
        }
        return shape;
    }

    /** 纳管连线端点：优先匹配本次新纳管的本地 id，其次按已有节点 UUID 解析 */
    private UUID resolveAdoptId(String localId, Map<String, Shape> createdByLocalId, UUID boardId) {
        if (localId == null || localId.isBlank()) {
            return null;
        }
        Shape created = createdByLocalId.get(localId);
        if (created != null) {
            return created.getId();
        }
        UUID existing = parseUuid(localId);
        if (existing == null) {
            return null;
        }
        return nodeExistsInBoard(existing, boardId) ? existing : null;
    }

    private String normalizeSide(String side) {
        if (side == null) {
            return null;
        }
        String value = side.trim().toUpperCase(java.util.Locale.ROOT);
        return "LEFT".equals(value) || "RIGHT".equals(value) ? value : null;
    }

    /** 节点所在的无向连通分量；找不到时返回只含自身 */
    private List<String> componentOf(Graph graph, String shapeId) {
        Integer index = graph.componentIndexOf().get(shapeId);
        if (index == null) {
            return List.of(shapeId);
        }
        return graph.components().get(index);
    }

    /** 沿连线方向（父 → 子）收集整棵子树，包含起点自身；使用预建前向邻接表，O(子树) */
    private void collectSubtree(Graph graph, String rootId, Set<String> targets) {
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : graph.forward().getOrDefault(current, List.of())) {
                if (targets.add(child)) {
                    queue.add(child);
                }
            }
        }
    }

    /** 可见节点：折叠节点的全部后代都不参与布局与渲染 */
    private Set<String> visibleNodes(Graph graph) {
        Set<String> hidden = new HashSet<>();
        for (Shape shape : graph.shapes()) {
            if (shape.isCollapsed()) {
                collectSubtree(graph, shape.getId().toString(), hidden);
            }
        }
        Set<String> visible = new LinkedHashSet<>(graph.shapeById().keySet());
        visible.removeAll(hidden);
        return visible;
    }

    /** 按可见集合过滤分量，保留原有顺序 */
    private static List<String> visibleIn(Set<String> visible, List<String> ids) {
        List<String> result = new ArrayList<>();
        for (String id : ids) {
            if (visible.contains(id)) {
                result.add(id);
            }
        }
        return result;
    }

    /** 指定节点集合的包围盒 [minX, minY, maxX, maxY]（集合非空） */
    private double[] boundsOf(Graph graph, List<String> ids) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (String id : ids) {
            Shape shape = graph.shapeById().get(id);
            if (shape == null) {
                continue;
            }
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    /**
     * 重排受本批动作影响的连通分量。
     * - 含已有节点的分量：新布局的包围盒左上角对齐旧包围盒左上角，整块图原地整理；
     * - 全新分量与孤立新节点：放到画布已有内容右侧；
     * - 未受影响的分量不动。
     */
    private void relayoutTouched(Board board, Set<UUID> createdIds, Set<UUID> updatedIds) {
        // 不再显式 flush：紧随其后的查询会按 FlushMode.AUTO 自动刷出本批写入
        Graph graph = loadGraph(board.getId());
        // 新创建的节点
        Set<String> newIds = toStrings(createdIds);
        // 受影响的节点
        Set<String> touched = toStrings(createdIds);
        // 将更新的节点加入受影响的集合
        touched.addAll(toStrings(updatedIds));
        // 得到可见节点
        Set<String> visible = visibleNodes(graph);
        // 按受影响程度分类
        // 分类：锚点固定、全新、孤立新节点
        List<List<String>> anchored = new ArrayList<>();
        List<List<String>> fresh = new ArrayList<>();
        List<String> isolatedNew = new ArrayList<>();
        for (List<String> fullComponent : graph.components()) {
            // 过滤出该分量中可见节点
            List<String> component = visibleIn(visible, fullComponent);
            // 如果分量中没有受影响的节点，则跳过
            if (component.stream().noneMatch(touched::contains)) {
                continue;
            }
            // 判断分量是否全部由新建节点组成
            boolean allNew = component.stream().allMatch(newIds::contains);
            // 如果分量中只有一个节点且是新建的，则判断为孤立新节点
            if (allNew && component.size() == 1) {
                isolatedNew.add(component.get(0));
            } else if (allNew) {
                // 如果分量全部由新建节点组成，则判断为全新分量
                fresh.add(component);
            } else {
                // 否则判断为锚点固定分量
                anchored.add(component);
            }
        }
        // 如果没有需要重排的分量，则返回
        // 由于调用这个函数前会检查，该情况基本不可能发生（防御编程）
        if (anchored.isEmpty() && fresh.isEmpty() && isolatedNew.isEmpty()) {
            return;
        }
        // 只有「按光标重新放置」的节点才排除出已有包围盒，原地重排的分量仍算已有内容
        // 孤立新节点和全新分量不参与已有内容的包围盒计算
        Set<String> cursorPlaced = new HashSet<>(isolatedNew);
        fresh.forEach(cursorPlaced::addAll);
        double[] existing = boundsExcluding(graph, cursorPlaced, visible);
        // 最右加边距
        double cursorX = existing == null ? MARGIN : existing[2] + GAP;
        // 最上加边距
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
            // 逐个节点按自身所属分量判定 fork，避免把同批的普通孤立节点也缩成 fork 尺寸
            for (String id : ordered) {
                sizeComponent(board, graph, List.of(id));
            }
            // 计算孤立节点的尺寸
            Map<String, double[]> sizes = sizesOf(graph, ordered);
            Map<String, double[]> grid = gridLayout(ordered, sizes);
            double[] box = bounds(grid, sizes);
            placeComponent(graph, ordered, grid, cursorX - box[0], baseY - box[1]);
            cursorX += (box[2] - box[0]) + GAP;
        }

        for (List<String> component : fresh) {
            ComponentLayout layout = layoutComponent(board, graph, component);
            Map<String, double[]> positions = layout.positions();
            Map<String, double[]> sizes = sizesOf(graph, component);
            double[] box = bounds(positions, sizes);
            placeComponent(graph, component, positions, cursorX - box[0], baseY - box[1]);
            cursorX += (box[2] - box[0]) + GAP;
        }

        for (List<String> component : anchored) {
            // 锚点按重排前的包围盒计算，避免 fork 收缩后整块位移
            double[] old = existingBoundsOfComponent(graph, component, newIds);
            ComponentLayout layout = layoutComponent(board, graph, component);
            placeAnchored(graph, component, layout, old);
        }
        log.info("增量重排：boardId={} 原地分量={} 新分量={} 孤立新节点={}", board.getId(), anchored.size(),
                fresh.size(), isolatedNew.size());
    }

    /** 逻辑图里的思维导图分量：包含 level>0 的节点，使用 fork 节点和贝塞尔连线 */
    private boolean isLogicForkComponent(Board board, Graph graph, List<String> component) {
        if (board.getLayoutStyle() != BoardLayoutStyle.LOGIC) {
            return false;
        }
        for (String id : component) {
            Shape shape = graph.shapeById().get(id);
            if (shape != null && shape.getLevel() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按分量统一尺寸：
     * 逻辑图 fork 节点按文字精确收缩（可大可小），其他节点按 geo 标签放大（保留用户手动放大）。
     */
    private void sizeComponent(Board board, Graph graph, List<String> component) {
        // 是否为逻辑图非中心节点
        boolean fork = isLogicForkComponent(board, graph, component);
        for (String id : component) {
            Shape shape = graph.shapeById().get(id);
            if (shape == null) {
                continue;
            }
            if (fork) {
                // 逻辑图fork节点的文字精确收缩
                NodeSizeEstimator.NodeSize size = NodeSizeEstimator.estimateFork(shape.getTitle());
                // 重置fork节点边框大小
                shape.resizeTo(size.width(), size.height());
            } else {
                NodeSizeEstimator.NodeSize size = NodeSizeEstimator.estimate(shape.getTitle());
                shape.growTo(size.width(), size.height());
            }
        }
    }

    /**
     * 分量布局：思维导图按画布风格二选一，流程图永远分层。
     * 逻辑图单根树走局部布局；多根树或其他风格返回全量结果，rootId 为 null。
     */
    private ComponentLayout layoutComponent(Board board, Graph graph, List<String> component) {
        // 先统一尺寸，后续布局与包围盒都使用最终尺寸
        sizeComponent(board, graph, component);
        // 记录节点的尺寸
        Map<String, double[]> sizes = sizesOf(graph, component);
        // 记录节点的层级
        Map<String, Integer> levels = new HashMap<>();
        // 是否为树形思维导图
        boolean mindmap = false;
        for (String id : component) {
            Shape shape = graph.shapeById().get(id);
            int level = shape == null ? 0 : shape.getLevel();
            levels.put(id, level);
            mindmap |= level > 0;
        }
        // 记录方向（loadGraph 已按分量分桶）
        List<String[]> componentEdges = edgesWithin(graph, component);
        if (!mindmap) {
            return new ComponentLayout(GraphLayoutService.layoutFlowchart(component, componentEdges, sizes), null);
        }
        int maxNodes = properties.ai().layout().radialMaxNodes();
        // 树形思维导图并且符合最大节点数限制为true
        boolean radial = board.getLayoutStyle() == BoardLayoutStyle.RADIAL && graph.shapes().size() <= maxNodes;
        if (radial) {
            return new ComponentLayout(
                    GraphLayoutService.layoutMindmapTree(component, componentEdges, sizes, levels), null);
        }
        // 逻辑图一级分支的固定侧由连线 side 提供，loadGraph 已一次扫描建好；这里只保留本分量内的项
        Map<String, String> sideByChild = new HashMap<>();
        for (String id : component) {
            String side = graph.sideByChild().get(id);
            if (side != null) {
                sideByChild.put(id, side);
            }
        }
        GraphLayoutService.SpanningTree tree = GraphLayoutService.spanningTree(component, componentEdges, levels);
        if (tree.roots().size() == 1) {
            String rootId = tree.roots().get(0);
            TidyLogicLayout.IncrementalResult result = TidyLogicLayout.layoutIncremental(component, tree,
                    sizes, sideByChild, layoutCache.snapshot(board.getId(), BoardLayoutStyle.LOGIC, rootId));
            if (result != null) {
                layoutCache.publishAfterCommit(board.getId(), BoardLayoutStyle.LOGIC, rootId, result.state());
                return new ComponentLayout(result.positions(), rootId);
            }
        }
        // 多根树无法用单根缓存，走全量；根节点置空，调用方按包围盒锚定
        return new ComponentLayout(TidyLogicLayout.layout(component, tree, sizes, sideByChild), null);
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
        // 将 shapeId 从字符串解析为 UUID
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
        String oldTitle = shape.getTitle();
        double oldWidth = shape.getWidth();
        double oldHeight = shape.getHeight();
        shape.applyAgentEdit(action.title(), action.shape(), action.level());
        NodeSizeEstimator.NodeSize size = NodeSizeEstimator.estimate(shape.getTitle());
        // 标题变长时同步放大尺寸
        shape.growTo(size.width(), size.height());
        // 标题变化也重排：逻辑图 fork 节点需要按新文字精确收缩，growTo 只能放大
        boolean relayout = shape.getLevel() != oldLevel
                || !shape.getTitle().equals(oldTitle)
                || shape.getWidth() != oldWidth
                || shape.getHeight() != oldHeight;
        log.info("更新节点：shapeId={} title={}", shapeId, shape.getTitle());
        return new UpdateOutcome(true, relayout, shapeId);
    }

    /** 连线：端点解析为真实 id，并校验两端都属于当前画布；返回是否真正生效 */
    private boolean connect(UUID boardId, BoardAction.Connect action, Map<String, UUID> shapeIdMap,
            Map<UUID, Integer> nextSortOrders) {
        UUID fromShapeId = resolveShapeId(action.fromShapeId(), shapeIdMap);
        UUID toShapeId = resolveShapeId(action.toShapeId(), shapeIdMap);
        if (fromShapeId == null || toShapeId == null || fromShapeId.equals(toShapeId)) {
            return false;
        }
        if (!nodeExistsInBoard(fromShapeId, boardId) || !nodeExistsInBoard(toShapeId, boardId)) {
            log.warn("忽略悬空连线：{} -> {}", fromShapeId, toShapeId);
            return false;
        }
        // 新子节点必须可见：父节点折叠时先展开
        shapeRepository.findById(fromShapeId)
                .filter(parent -> parent.isCollapsed())
                .ifPresent(parent -> parent.collapse(false));
        BoardEdge edge = new BoardEdge(boardId, fromShapeId, toShapeId, action.label(),
                action.style(), action.color(), action.weight() == null ? 2 : action.weight());
        int sortOrder = nextSortOrders.getOrDefault(fromShapeId, 1);
        nextSortOrders.put(fromShapeId, sortOrder + 1);
        edge.reorder(sortOrder, null);
        edgeRepository.save(edge);
        return true;
    }

    /** 每个父节点下一个可用的兄弟顺序：已有最大 sortOrder + 1，没有子节点时从 1 开始 */
    private Map<UUID, Integer> nextSortOrders(UUID boardId) {
        Map<UUID, Integer> next = new HashMap<>();
        for (BoardEdge edge : edgeRepository.findByBoardIdOrderBySortOrderAscCreatedAtAsc(boardId)) {
            next.merge(edge.getFromShapeId(), edge.getSortOrder() + 1, Math::max);
        }
        return next;
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

    /** 解析节点 ID：先在映射中查找，再解析字符串；失败时返回 null **/
    private UUID resolveShapeId(String shapeId, Map<String, UUID> shapeIdMap) {
        if (shapeId == null || shapeId.isBlank()) {
            return null;
        }
        UUID mapped = shapeIdMap.get(shapeId);
        return mapped != null ? mapped : parseUuid(shapeId);
    }
    // 将字符串解析为 UUID，失败时返回 null
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

    /** 读取整张图的节点、连线、邻接表与弱连通分量，并预建后续布局需要的索引 */
    private Graph loadGraph(UUID boardId) {
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderBySortOrderAscCreatedAtAsc(boardId);
        Map<String, Shape> shapeById = new LinkedHashMap<>();
        // 无向邻接表用于求弱连通分量；forward 是有向邻接表，用于折叠子树和生成树
        Map<String, Set<String>> undirected = new LinkedHashMap<>();
        Map<String, List<String>> forward = new LinkedHashMap<>();
        for (Shape shape : shapes) {
            String id = shape.getId().toString();
            shapeById.put(id, shape);
            undirected.put(id, new LinkedHashSet<>());
            forward.put(id, new ArrayList<>());
        }
        // 一级分支的固定左右侧：连线上的 side 属性，一次扫描建好
        Map<String, String> sideByChild = new HashMap<>();
        for (BoardEdge edge : edges) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            if (!undirected.containsKey(from) || !undirected.containsKey(to)) {
                continue;
            }
            undirected.get(from).add(to);
            undirected.get(to).add(from);
            forward.get(from).add(to);
            if (edge.getSide() != null) {
                sideByChild.put(to, edge.getSide());
            }
        }
        List<List<String>> components = new ArrayList<>();
        Map<String, Integer> componentIndexOf = new HashMap<>();
        Set<String> visited = new HashSet<>();
        for (Shape shape : shapes) {
            String start = shape.getId().toString();
            if (!visited.add(start)) {
                continue;
            }
            List<String> component = new ArrayList<>();
            int componentIndex = components.size();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                component.add(current);
                componentIndexOf.put(current, componentIndex);
                for (String next : undirected.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            components.add(component);
        }
        // 按分量给边分桶：一条边的两个端点必然属于同一个弱连通分量
        List<List<String[]>> componentEdges = new ArrayList<>(components.size());
        for (int index = 0; index < components.size(); index++) {
            componentEdges.add(new ArrayList<>());
        }
        for (BoardEdge edge : edges) {
            String from = edge.getFromShapeId().toString();
            String to = edge.getToShapeId().toString();
            Integer index = componentIndexOf.get(from);
            if (index == null || !componentIndexOf.containsKey(to)) {
                continue;
            }
            componentEdges.get(index).add(new String[] { from, to });
        }
        log.info("该画布的连通分量数量为{}", components.size());
        return new Graph(shapes, edges, shapeById, undirected, components, forward, componentIndexOf,
                componentEdges, sideByChild);
    }

    /** 分量内的连线（保留方向）：直接取 loadGraph 已分好桶的列表 */
    private List<String[]> edgesWithin(Graph graph, List<String> component) {
        if (component.isEmpty()) {
            return List.of();
        }
        Integer index = graph.componentIndexOf().get(component.get(0));
        if (index == null) {
            return List.of();
        }
        return graph.componentEdges().get(index);
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
            if (shape == null || pos == null) {
                continue;
            }
            double targetX = pos[0] + offsetX;
            double targetY = pos[1] + offsetY;
            // 只写真正变化的坐标，避免持久化层把整分量标脏
            if (Math.abs(shape.getX() - targetX) > 0.5 || Math.abs(shape.getY() - targetY) > 0.5) {
                shape.update(targetX, targetY);
            }
        }
    }

    /**
     * 原地重排：单根逻辑树以根节点当前位置为锚点，根不动、分支相对展开；
     * 其他分量仍按旧包围盒左上角锚定，保持整块图不跳位。
     */
    private void placeAnchored(Graph graph, List<String> component, ComponentLayout layout, double[] old) {
        Map<String, double[]> positions = layout.positions();
        String rootId = layout.rootId();
        double offsetX;
        double offsetY;
        Shape root = rootId == null ? null : graph.shapeById().get(rootId);
        double[] rootPos = rootId == null ? null : positions.get(rootId);
        if (root != null && rootPos != null) {
            offsetX = root.getX() - rootPos[0];
            offsetY = root.getY() - rootPos[1];
        } else {
            Map<String, double[]> sizes = sizesOf(graph, component);
            double[] box = bounds(positions, sizes);
            offsetX = old[0] - box[0];
            offsetY = old[1] - box[1];
        }
        placeComponent(graph, component, positions, offsetX, offsetY);
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

    /** 画布内容的包围盒（排除本次会按光标重新放置的节点与折叠隐藏的节点）；空画布返回 null
    * @param graph 画布的图结构
    * @param excluded 排除的节点集合
    * @param visible 可见的节点集合
    * */
    private double[] boundsExcluding(Graph graph, Set<String> excluded, Set<String> visible) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean found = false;
        for (Shape shape : graph.shapes()) {
            String id = shape.getId().toString();
            if (excluded.contains(id) || !visible.contains(id)) {
                continue;
            }
            found = true;
            // 得到该可见节点的最坐标
            minX = Math.min(minX, shape.getX());
            minY = Math.min(minY, shape.getY());
            maxX = Math.max(maxX, shape.getX() + shape.getWidth());
            maxY = Math.max(maxY, shape.getY() + shape.getHeight());
        }
        // 左 上 右 下
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

    /**
     * 一次读库后的图快照。除节点/连线/分量外，预建了按分量分桶的边、前向邻接表和节点到分量下标的索引，
     * 后续 visibleNodes、collectSubtree、edgesWithin、componentOf 都不再重复扫描全图。
     */
    private record Graph(List<Shape> shapes, List<BoardEdge> edges, Map<String, Shape> shapeById,
            Map<String, Set<String>> undirected, List<List<String>> components,
            Map<String, List<String>> forward, Map<String, Integer> componentIndexOf,
            List<List<String[]>> componentEdges, Map<String, String> sideByChild) {
    }

    /** 分量布局结果；rootId 非空表示单根逻辑树，可以用根节点作为原地锚点 */
    private record ComponentLayout(Map<String, double[]> positions, String rootId) {
    }

    private record UpdateOutcome(boolean applied, boolean relayout, UUID shapeId) {
        static UpdateOutcome skipped() {
            return new UpdateOutcome(false, false, null);
        }
    }
}
