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

    /** 节点间距 */
    private static final double GAP = 120;
    /** 缺省节点宽高（sizes 未提供时用） */
    private static final double DEFAULT_W = 180;
    private static final double DEFAULT_H = 72;
    /** 根到一级分支的基础半径 */
    private static final double BASE_RADIUS = 240;
    /** 每加深一层的半径增量 */
    private static final double LAYER_GAP = 200;
    /** 逻辑图：父子列间距 / 兄弟子树间距 / 多树之间的纵向间距 */
    private static final double LOGIC_H_GAP = 160;
    private static final double LOGIC_V_GAP = 32;
    private static final double ROOT_GAP = 80;
    /** 流程图：同层节点间距 / 层间纵向间距 */
    private static final double FLOW_H_GAP = 80;
    private static final double FLOW_LAYER_GAP = 100;
    private static final double TWO_PI = Math.PI * 2;

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
    private static double widthOf(Map<String, double[]> sizes, String id) {
        double[] size = sizes == null ? null : sizes.get(id);
        return size == null || size[0] <= 0 ? DEFAULT_W : size[0];
    }

    /** 节点高度（无尺寸记录时回退缺省值） */
    private static double heightOf(Map<String, double[]> sizes, String id) {
        double[] size = sizes == null ? null : sizes.get(id);
        return size == null || size[1] <= 0 ? DEFAULT_H : size[1];
    }

    /**
     * 径向树布局（树形图）：根在中心，子节点按叶子权重瓜分父节点的角度扇区。
     * 边集存在环时只按生成树展开，回边不进入递归。
     */
    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        return layoutMindmapTree(nodeIds, edges, sizes, null);
    }

    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes, Map<String, Integer> levels) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return positions;
        }
        log.info("树形图布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        SpanningTree tree = spanningTree(nodeIds, edges, levels);
        // 多个根（森林或纯环）按纵向堆叠，互不重叠
        double cursorY = 0;
        for (String root : tree.roots()) {
            Map<String, Integer> leafWeight = new HashMap<>();
            computeLeafWeight(root, tree.children(), leafWeight);
            Map<String, Integer> depth = computeDepths(root, tree.children());
            double radius = adaptiveRadius(root, tree.children(), leafWeight, depth, sizes);
            Map<String, double[]> local = new LinkedHashMap<>();
            place(root, tree.children(), leafWeight, 0, TWO_PI, 0, radius, sizes, local);
            double[] box = bounds(local, sizes);
            double offsetX = -box[0];
            double offsetY = cursorY - box[1];
            local.forEach((id, pos) ->
                    positions.put(id, new double[] { pos[0] + offsetX, pos[1] + offsetY }));
            cursorY += (box[3] - box[1]) + ROOT_GAP;
        }
        return positions;
    }

    /**
     * 逻辑图布局（XMind / Freeplane 风格）：从左到右分列，父节点在子块内垂直居中，兄弟子树不重叠。
     */
    public static Map<String, double[]> layoutLogicChart(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes, Map<String, Integer> levels) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return positions;
        }
        log.info("逻辑图布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        SpanningTree tree = spanningTree(nodeIds, edges, levels);
        Map<String, Integer> depth = new HashMap<>();
        for (String root : tree.roots()) {
            assignDepth(root, tree.children(), 0, depth);
        }
        // 每列宽度取该层最宽节点，列坐标按层推进
        Map<Integer, Double> layerWidth = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : depth.entrySet()) {
            layerWidth.merge(entry.getValue(), widthOf(sizes, entry.getKey()), Math::max);
        }
        Map<Integer, Double> columnX = new TreeMap<>();
        double cursorX = 0;
        for (Map.Entry<Integer, Double> entry : layerWidth.entrySet()) {
            columnX.put(entry.getKey(), cursorX);
            cursorX += entry.getValue() + LOGIC_H_GAP;
        }
        Map<String, Double> subtreeHeight = new HashMap<>();
        for (String root : tree.roots()) {
            computeSubtreeHeight(root, tree.children(), sizes, subtreeHeight);
        }
        double cursorY = 0;
        for (String root : tree.roots()) {
            assignLogicPosition(root, tree.children(), sizes, depth, columnX, subtreeHeight, cursorY, positions);
            cursorY += subtreeHeight.getOrDefault(root, heightOf(sizes, root)) + ROOT_GAP;
        }
        normalize(positions);
        return positions;
    }

    private static void assignDepth(String node, Map<String, List<String>> children, int value,
            Map<String, Integer> depth) {
        depth.put(node, value);
        for (String kid : children.getOrDefault(node, List.of())) {
            assignDepth(kid, children, value + 1, depth);
        }
    }

    /** 子树高度：叶子取自身高度，内部节点取子块高度（含间距）与自身高度的较大值 */
    private static double computeSubtreeHeight(String node, Map<String, List<String>> children,
            Map<String, double[]> sizes, Map<String, Double> result) {
        List<String> kids = children.getOrDefault(node, List.of());
        double own = heightOf(sizes, node);
        if (kids.isEmpty()) {
            result.put(node, own);
            return own;
        }
        double sum = 0;
        for (String kid : kids) {
            sum += computeSubtreeHeight(kid, children, sizes, result);
        }
        sum += LOGIC_V_GAP * (kids.size() - 1);
        double block = Math.max(own, sum);
        result.put(node, block);
        return block;
    }

    private static void assignLogicPosition(String node, Map<String, List<String>> children,
            Map<String, double[]> sizes, Map<String, Integer> depth, Map<Integer, Double> columnX,
            Map<String, Double> subtreeHeight, double top, Map<String, double[]> positions) {
        double block = subtreeHeight.getOrDefault(node, heightOf(sizes, node));
        double own = heightOf(sizes, node);
        positions.put(node, new double[] { columnX.getOrDefault(depth.get(node), 0.0), top + (block - own) / 2 });
        List<String> kids = children.getOrDefault(node, List.of());
        if (kids.isEmpty()) {
            return;
        }
        double kidsBlock = 0;
        for (String kid : kids) {
            kidsBlock += subtreeHeight.getOrDefault(kid, heightOf(sizes, kid));
        }
        kidsBlock += LOGIC_V_GAP * (kids.size() - 1);
        double childTop = top + (block - kidsBlock) / 2;
        for (String kid : kids) {
            assignLogicPosition(kid, children, sizes, depth, columnX, subtreeHeight, childTop, positions);
            childTop += subtreeHeight.getOrDefault(kid, heightOf(sizes, kid)) + LOGIC_V_GAP;
        }
    }

    /**
     * 基础半径：保证最浅一层叶子所在圆周的弧长足以容纳节点外接尺寸。
     * 叶子按叶权重均分 2π，最浅叶子所在层半径最小、约束最紧，据此反推基础半径。
     */
    private static double adaptiveRadius(String root, Map<String, List<String>> children,
            Map<String, Integer> leafWeight, Map<String, Integer> depth, Map<String, double[]> sizes) {
        int totalLeaves = leafWeight.getOrDefault(root, 1);
        double maxExtent = 0;
        int minLeafDepth = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : depth.entrySet()) {
            if (children.get(entry.getKey()).isEmpty()) {
                maxExtent = Math.max(maxExtent, Math.max(widthOf(sizes, entry.getKey()), heightOf(sizes, entry.getKey())));
                minLeafDepth = Math.min(minLeafDepth, entry.getValue());
            }
        }
        // 单节点树（根即叶子）没有圆周间距需求
        if (minLeafDepth <= 0) {
            return BASE_RADIUS;
        }
        double minRadius = totalLeaves * (maxExtent + GAP) / TWO_PI - (minLeafDepth - 1) * LAYER_GAP;
        return Math.max(BASE_RADIUS, minRadius);
    }

    /** BFS 计算每个节点相对根的深度（根=0，仅生成树可达节点） */
    private static Map<String, Integer> computeDepths(String root, Map<String, List<String>> children) {
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        depth.put(root, 0);
        queue.add(root);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            int d = depth.get(node);
            for (String kid : children.getOrDefault(node, List.of())) {
                depth.put(kid, d + 1);
                queue.add(kid);
            }
        }
        return depth;
    }

    /** 计算每个节点的叶子权重（子树叶子数）：叶子权重 1，内部节点为子节点权重之和 */
    private static int computeLeafWeight(String node, Map<String, List<String>> children,
            Map<String, Integer> weight) {
        List<String> kids = children.getOrDefault(node, List.of());
        if (kids.isEmpty()) {
            weight.put(node, 1);
            return 1;
        }
        int sum = 0;
        for (String kid : kids) {
            sum += computeLeafWeight(kid, children, weight);
        }
        weight.put(node, sum);
        return sum;
    }

    /** 递归分配节点角度扇区并写入坐标（左上角，根中心在原点） */
    private static void place(String node, Map<String, List<String>> children, Map<String, Integer> weight,
            double angleStart, double angleEnd, int depth, double baseRadius, Map<String, double[]> sizes,
            Map<String, double[]> positions) {
        double mid = (angleStart + angleEnd) / 2;
        double radius = depth <= 0 ? 0 : baseRadius + (depth - 1) * LAYER_GAP;
        double cx = radius * Math.cos(mid);
        double cy = radius * Math.sin(mid);
        double w = widthOf(sizes, node);
        double h = heightOf(sizes, node);
        positions.put(node, new double[] { cx - w / 2, cy - h / 2 });
        List<String> kids = children.getOrDefault(node, List.of());
        if (kids.isEmpty()) {
            return;
        }
        double total = weight.getOrDefault(node, kids.size());
        double span = angleEnd - angleStart;
        double cursor = angleStart;
        for (String kid : kids) {
            double kidSpan = span * (weight.getOrDefault(kid, 1) / (double) total);
            place(kid, children, weight, cursor, cursor + kidSpan, depth + 1, baseRadius, sizes, positions);
            cursor += kidSpan;
        }
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

    /** 坐标包围盒 [minX, minY, maxX, maxY]，尺寸按节点实际宽高计算 */
    private static double[] bounds(Map<String, double[]> positions, Map<String, double[]> sizes) {
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
