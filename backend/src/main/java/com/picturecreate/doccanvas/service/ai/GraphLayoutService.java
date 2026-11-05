package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构图布局：思维导图（径向 / 逻辑图）与流程图（分层）的坐标计算。
 *
 * 输入：节点 id 列表 + 边（from/to）列表 + 节点尺寸（id → [宽, 高]）+ 可选层级（id → level）；
 * 输出：节点 id → 左上角坐标 (x, y)，坐标从 (0, 0) 起，由调用方负责整体平移。
 *
 * 所有树类布局都先构建生成树：回边、重复边、自环只影响箭头渲染，不参与递归，
 * 因此成环的边集也不会把递归布局带进栈溢出。
 */
public final class GraphLayoutService {

    private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

    /** 缺省节点宽高（sizes 未提供时用） */
    private static final double DEFAULT_W = 180;
    private static final double DEFAULT_H = 72;
    /** 流程图：同层节点间距 / 层间纵向间距 */
    private static final double FLOW_H_GAP = 80;
    private static final double FLOW_LAYER_GAP = 100;

    private GraphLayoutService() {
    }

    /** 生成树：根列表 + 每个节点的子节点（覆盖所有输入节点，无环） */
    record SpanningTree(List<String> roots, Map<String, List<String>> children) {
    }

    /**
     * 构建生成树。
     *
     * 根候选顺序：level=0 优先，其次有向入度为 0，最后按原插入顺序。
     * 按候选顺序做多源有向 BFS，每个节点只挂一个父节点；纯环分量会从最早未被访问的节点重新起根，
     * 保证所有节点都有归属，且递归深度不会超过节点数。
     */
    static SpanningTree spanningTree(List<String> nodeIds, List<String[]> edges, Map<String, Integer> levels) {
        Set<String> all = new LinkedHashSet<>();
        for (String id : nodeIds) {
            if (id != null && !id.isBlank()) {
                all.add(id);
            }
        }
        Map<String, List<String>> children = new LinkedHashMap<>();
        Map<String, List<String>> forward = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : all) {
            children.put(id, new ArrayList<>());
            forward.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        for (String[] edge : normalizeEdges(all, edges)) {
            forward.get(edge[0]).add(edge[1]);
            inDegree.merge(edge[1], 1, Integer::sum);
        }
        List<String> candidates = new ArrayList<>(all);
        candidates.sort(Comparator.comparingInt(id -> rootPriority(id, levels, inDegree)));

        List<String> roots = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String candidate : candidates) {
            if (!visited.add(candidate)) {
                continue;
            }
            roots.add(candidate);
            Deque<String> queue = new ArrayDeque<>();
            queue.add(candidate);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String next : forward.getOrDefault(current, List.of())) {
                    if (visited.add(next)) {
                        children.get(current).add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return new SpanningTree(roots, children);
    }

    private static int rootPriority(String id, Map<String, Integer> levels, Map<String, Integer> inDegree) {
        if (levels != null && levels.getOrDefault(id, -1) == 0) {
            return 0;
        }
        return inDegree.getOrDefault(id, 0) == 0 ? 1 : 2;
    }

    /** 规范化边集：丢弃空边、自环、端点不在节点集内的边与重复边，保留原顺序 */
    private static List<String[]> normalizeEdges(Set<String> all, List<String[]> edges) {
        List<String[]> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (edges == null) {
            return result;
        }
        for (String[] edge : edges) {
            if (edge == null || edge.length < 2) {
                continue;
            }
            String from = edge[0];
            String to = edge[1];
            if (from == null || to == null || from.equals(to) || !all.contains(from) || !all.contains(to)) {
                continue;
            }
            if (seen.add(from + "\u0000" + to)) {
                result.add(new String[] { from, to });
            }
        }
        return result;
    }

    /** 节点宽度（无尺寸记录时回退缺省值） */
    static double widthOf(Map<String, double[]> sizes, String id) {
        double[] size = sizes == null ? null : sizes.get(id);
        return size == null || size[0] <= 0 ? DEFAULT_W : size[0];
    }

    /** 节点高度（无尺寸记录时回退缺省值） */
    static double heightOf(Map<String, double[]> sizes, String id) {
        double[] size = sizes == null ? null : sizes.get(id);
        return size == null || size[1] <= 0 ? DEFAULT_H : size[1];
    }

    /**
     * 径向树布局（树形图）：根在中心，半径按层节点外接尺寸递增，角度按子树所需角宽分配。
     * 边集存在环时只按生成树展开，回边不进入递归。
     */
    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        return layoutMindmapTree(nodeIds, edges, sizes, null);
    }

    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes, Map<String, Integer> levels) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        log.info("树形图布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        SpanningTree tree = spanningTree(nodeIds, edges, levels);
        Map<String, double[]> positions = RadialTreeLayout.layout(nodeIds, tree, sizes);
        normalize(positions);
        return positions;
    }

    /**
     * 逻辑图布局（XMind / Freeplane 双向风格）：根在中间，一级子节点按子树高度分列左右两侧，
     * 右侧向右展开、左侧镜像向左展开。子树内部子节点 x 跟随父内容宽度推进，
     * 兄弟子树用分段轮廓互相咬合，父节点在首末子节点中心之间垂直居中。
     */
    public static Map<String, double[]> layoutLogicChart(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes, Map<String, Integer> levels) {
        return layoutLogicChart(nodeIds, edges, sizes, levels, null);
    }

    /**
     * 逻辑图布局，支持固定一级分支的左右侧。
     *
     * @param sideByChild 一级分支 id → LEFT/RIGHT；null 或未指定的分支按两侧高度自动均衡
     */
    public static Map<String, double[]> layoutLogicChart(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes, Map<String, Integer> levels, Map<String, String> sideByChild) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new LinkedHashMap<>();
        }
        log.info("逻辑图布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        SpanningTree tree = spanningTree(nodeIds, edges, levels);
        Map<String, double[]> positions = LogicTreeLayout.layout(nodeIds, tree, sizes, sideByChild);
        normalize(positions);
        return positions;
    }

    /**
     * 流程图分层布局：永远自上而下。
     *
     * 分层用 Kahn 最长路径（汇聚节点落在所有前驱的下一层），成环时对残余分量按有向 BFS 兜底，
     * 不会把环节点全部塞到第 0 层。层内按父节点重心排序并整体居中到父层重心下方。
     */
    public static Map<String, double[]> layoutFlowchart(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return positions;
        }
        log.info("流程图布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        Set<String> all = new LinkedHashSet<>(nodeIds);
        List<String[]> cleanEdges = normalizeEdges(all, edges);
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        Map<String, List<String>> predecessors = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : all) {
            adj.put(id, new LinkedHashSet<>());
            predecessors.put(id, new ArrayList<>());
            inDegree.put(id, 0);
        }
        for (String[] edge : cleanEdges) {
            adj.get(edge[0]).add(edge[1]);
            predecessors.get(edge[1]).add(edge[0]);
            inDegree.merge(edge[1], 1, Integer::sum);
        }
        Map<String, Integer> layer = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String id : all) {
            if (inDegree.get(id) == 0) {
                layer.put(id, 0);
                queue.add(id);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int nextLayer = layer.get(current) + 1;
            for (String next : adj.get(current)) {
                layer.merge(next, nextLayer, Math::max);
                if (inDegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        assignCycleLayers(all, adj, predecessors, layer);

        Map<Integer, List<String>> byLayer = new TreeMap<>();
        for (String id : all) {
            byLayer.computeIfAbsent(layer.get(id), key -> new ArrayList<>()).add(id);
        }
        Map<String, Double> centerX = new HashMap<>();
        double cursorY = 0;
        for (Map.Entry<Integer, List<String>> entry : byLayer.entrySet()) {
            int depth = entry.getKey();
            List<String> nodes = entry.getValue();
            if (depth > 0) {
                nodes.sort(Comparator.comparingDouble(id -> barycenter(id, predecessors, centerX)));
            }
            double x = 0;
            double maxHeight = 0;
            for (String id : nodes) {
                double w = widthOf(sizes, id);
                double h = heightOf(sizes, id);
                positions.put(id, new double[] { x, cursorY });
                centerX.put(id, x + w / 2);
                x += w + FLOW_H_GAP;
                maxHeight = Math.max(maxHeight, h);
            }
            double layerWidth = x - FLOW_H_GAP;
            if (depth > 0) {
                double parentCenter = parentCenter(nodes, predecessors, centerX);
                if (!Double.isNaN(parentCenter)) {
                    double shift = parentCenter - layerWidth / 2;
                    for (String id : nodes) {
                        positions.get(id)[0] += shift;
                        centerX.put(id, centerX.get(id) + shift);
                    }
                }
            }
            cursorY += maxHeight + FLOW_LAYER_GAP;
        }
        normalize(positions);
        return positions;
    }

    /** 环上的节点没有参与 Kahn 分层：按弱分量找到分层来源作为基础层，再在分量内做有向 BFS */
    private static void assignCycleLayers(Set<String> all, Map<String, Set<String>> adj,
            Map<String, List<String>> predecessors, Map<String, Integer> layer) {
        List<String> residual = new ArrayList<>();
        for (String id : all) {
            if (!layer.containsKey(id)) {
                residual.add(id);
            }
        }
        if (residual.isEmpty()) {
            return;
        }
        Set<String> residualSet = new HashSet<>(residual);
        Map<String, Integer> baseLayer = new HashMap<>();
        for (String id : residual) {
            int base = 0;
            for (String parent : predecessors.getOrDefault(id, List.of())) {
                if (!residualSet.contains(parent) && layer.containsKey(parent)) {
                    base = Math.max(base, layer.get(parent) + 1);
                }
            }
            baseLayer.put(id, base);
        }
        Set<String> visited = new HashSet<>();
        for (String seed : residual) {
            if (!visited.add(seed)) {
                continue;
            }
            layer.put(seed, baseLayer.get(seed));
            Deque<String> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                for (String next : adj.get(current)) {
                    if (residualSet.contains(next) && visited.add(next)) {
                        layer.put(next, layer.get(current) + 1);
                        queue.add(next);
                    }
                }
            }
        }
    }

    /** 父节点中心 x 的平均值；没有已放置的父节点时返回 NaN */
    private static double barycenter(String id, Map<String, List<String>> predecessors,
            Map<String, Double> centerX) {
        double sum = 0;
        int count = 0;
        for (String parent : predecessors.getOrDefault(id, List.of())) {
            Double center = centerX.get(parent);
            if (center != null) {
                sum += center;
                count++;
            }
        }
        return count == 0 ? Double.MAX_VALUE : sum / count;
    }

    /** 该层所有节点的父节点中心 x 的平均值；没有父节点时返回 NaN */
    private static double parentCenter(List<String> nodes, Map<String, List<String>> predecessors,
            Map<String, Double> centerX) {
        double sum = 0;
        int count = 0;
        for (String id : nodes) {
            for (String parent : predecessors.getOrDefault(id, List.of())) {
                Double center = centerX.get(parent);
                if (center != null) {
                    sum += center;
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /** 把整组坐标平移到左上角 (0, 0) */
    private static void normalize(Map<String, double[]> positions) {
        if (positions.isEmpty()) {
            return;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        for (double[] pos : positions.values()) {
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
        }
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            entry.getValue()[0] -= minX;
            entry.getValue()[1] -= minY;
        }
    }
}
