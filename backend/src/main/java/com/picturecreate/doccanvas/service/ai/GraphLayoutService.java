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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构图布局：为思维导图（中心辐射）/ 流程图（分层）计算图形节点坐标。
 * 输入：节点 id 列表 + 边（from/to）列表 + 节点尺寸（id → [宽, 高]）；
 * 输出：节点 id → 左上角坐标 (x, y)。
 * 尺寸可选：缺省按典型节点尺寸估算，实际节点尺寸来自 CreateShape / Shape。
 */
public final class GraphLayoutService {

    private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

    /** 节点间距 */
    private static final double GAP = 120;
    /** 原点边距 */
    private static final double MARGIN = 120;
    /** 缺省节点宽高（sizes 未提供时用） */
    private static final double DEFAULT_W = 180;
    private static final double DEFAULT_H = 72;
    /** 根到一级分支的基础半径 */
    private static final double BASE_RADIUS = 240;
    /** 每加深一层的半径增量 */
    private static final double LAYER_GAP = 200;

    private GraphLayoutService() {
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
     * 递归径向树布局（支持任意深度）：
     * - 边方向 from=父 → to=子（思维导图 connect 恒为父→子）。
     * - 根 = 入度为 0 的节点（无则取第一个）。
     * - 每个节点的子节点按其叶子权重按比例瓜分父节点的角度扇区，叶子在圆周上均匀分布。
     * - 基础半径按叶子总数自适应放大，保证最内层叶子的弧长足以容纳节点宽度，避免深层内容重叠。
     * - 返回左上角坐标（根中心位于原点，调用方负责整体平移）。
     *
     * 前提：边集必须是无环的树（见 isTree）。下面三步都是沿 children 递归下探，
     * 环会让它一直往下走到栈溢出。调用方需先判，非树时改用 layoutFlowchart（它按层 BFS，天然扛得住环）。
     */
    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return positions;
        }
        log.info("思维导图树布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        Set<String> all = new LinkedHashSet<>(nodeIds);
        Map<String, List<String>> children = buildChildren(all, edges);
        Set<String> hasParent = new LinkedHashSet<>();
        children.values().forEach(hasParent::addAll);
        // 根：入度为 0 的节点
        String root = nodeIds.stream().filter(id -> !hasParent.contains(id)).findFirst().orElse(nodeIds.get(0));
        Map<String, Integer> leafWeight = new HashMap<>();
        computeLeafWeight(root, children, leafWeight);
        Map<String, Integer> depth = computeDepths(root, children);
        double radius = adaptiveRadius(root, children, leafWeight, depth, sizes);
        place(root, children, leafWeight, 0, 2 * Math.PI, 0, radius, sizes, positions);
        return positions;
    }

    /**
     * 边集在给定节点上是否构成一棵树（连通且无环），即能否用径向布局。
     *
     * 判 "节点数 n 且边数 n-1" 还不够：两棵独立的子树再挂一条回边也能凑出 n-1 条边。
     * 所以这里真的跑一遍三色 DFS 判环——n-1 条边且无环时必然连通（森林的边数是 n-分量数），
     * 两个条件合起来就是树。
     *
     * 这里和 layoutMindmapTree 共用 buildChildren，保证判的和布局用的是同一个邻接表：
     * 两边对"哪些边算数"的过滤规则（空边/自环/端点不在节点集内）一旦走岔，判环就会漏。
     */
    public static boolean isTree(List<String> nodeIds, List<String[]> edges) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return false;
        }
        Set<String> all = new LinkedHashSet<>(nodeIds);
        Map<String, List<String>> children = buildChildren(all, edges);
        int edgeCount = children.values().stream().mapToInt(kids -> kids.size()).sum();
        if (edgeCount != all.size() - 1) {
            return false;
        }
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String node : all) {
            if (hasCycle(node, children, visiting, done)) {
                return false;
            }
        }
        return true;
    }

    /** 规范化边集：丢弃空边、自环、端点不在节点集内的边，返回 from → 子节点 的邻接表（节点集内每个 id 都有键） */
    private static Map<String, List<String>> buildChildren(Set<String> all, List<String[]> edges) {
        Map<String, List<String>> children = new HashMap<>();
        for (String id : all) {
            children.put(id, new ArrayList<>());
        }
        if (edges != null) {
            for (String[] edge : edges) {
                if (edge == null || edge.length < 2) {
                    continue;
                }
                String from = edge[0];
                String to = edge[1];
                if (from == null || to == null || from.equals(to) || !all.contains(from) || !all.contains(to)) {
                    continue;
                }
                children.get(from).add(to);
            }
        }
        return children;
    }

    /** 三色 DFS 判环：visiting = 当前递归路径，再遇到即回边；done = 已确认无环的子树 */
    private static boolean hasCycle(String node, Map<String, List<String>> children,
            Set<String> visiting, Set<String> done) {
        if (done.contains(node)) {
            return false;
        }
        if (!visiting.add(node)) {
            return true;
        }
        for (String kid : children.getOrDefault(node, List.of())) {
            if (hasCycle(kid, children, visiting, done)) {
                return true;
            }
        }
        visiting.remove(node);
        done.add(node);
        return false;
    }

    /**
     * 基础半径：保证最浅一层叶子所在圆周的弧长足以容纳节点宽度。
     * 叶子按叶权重均分 2π（相邻叶子角间距 = 2π/总叶子数），最浅叶子所在层半径最小、约束最紧，
     * 据此反推基础半径（浅层叶子已在其所在圆上留够弧长，深层叶子半径更大，自然更宽松）。
     */
    private static double adaptiveRadius(String root, Map<String, List<String>> children,
            Map<String, Integer> leafWeight, Map<String, Integer> depth, Map<String, double[]> sizes) {
        int totalLeaves = leafWeight.getOrDefault(root, 1);
        double leafWidth = DEFAULT_W;
        int minLeafDepth = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : depth.entrySet()) {
            if (children.get(entry.getKey()).isEmpty()) {
                leafWidth = Math.max(leafWidth, widthOf(sizes, entry.getKey()));
                minLeafDepth = Math.min(minLeafDepth, entry.getValue());
            }
        }
        // 单节点树（根即叶子）没有圆周间距需求，直接用基础半径
        if (minLeafDepth <= 0) {
            return BASE_RADIUS;
        }
        double minRadius = totalLeaves * (leafWidth + GAP) / (2 * Math.PI) - (minLeafDepth - 1) * LAYER_GAP;
        return Math.max(BASE_RADIUS, minRadius);
    }

    /** BFS 计算每个节点相对根的深度（根=0，仅可达节点） */
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
     * 流程图分层布局：BFS 分层后，每层横向排列、层间纵向推进。
     * - x 按节点实际宽度递增（x += 宽度 + 间距），层内节点不重叠。
     * - y 按该层最大高度递增（y += 层高 + 间距），层间不重叠。
     */
    public static Map<String, double[]> layoutFlowchart(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return positions;
        }
        log.info("流程图布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        // BFS 分层：入度 0 的根在最上层
        Map<String, Set<String>> adj = buildAdj(nodeIds, edges);
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeIds) {
            inDegree.put(id, 0);
        }
        if (edges != null) {
            for (String[] edge : edges) {
                if (edge == null || edge.length < 2) {
                    continue;
                }
                String from = edge[0];
                String to = edge[1];
                if (inDegree.containsKey(from) && inDegree.containsKey(to)) {
                    inDegree.put(to, inDegree.get(to) + 1);
                }
            }
        }
        Map<String, Integer> layer = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String id : nodeIds) {
            if (inDegree.get(id) == 0) {
                layer.put(id, 0);
                queue.add(id);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentLayer = layer.get(current);
            for (String next : adj.getOrDefault(current, Set.of())) {
                if (!layer.containsKey(next)) {
                    layer.put(next, currentLayer + 1);
                    queue.add(next);
                }
            }
        }
        for (String id : nodeIds) {
            layer.putIfAbsent(id, 0);
        }
        // 每层横向排列（x 按节点实际宽度递增），层间纵向推进（y 按该层最大高度递增）
        Map<Integer, List<String>> byLayer = new LinkedHashMap<>();
        for (String id : nodeIds) {
            byLayer.computeIfAbsent(layer.get(id), k -> new ArrayList<>()).add(id);
        }
        double y = MARGIN;
        for (int key : byLayer.keySet().stream().sorted().toList()) {
            double x = MARGIN;
            double layerMaxH = 0;
            for (String id : byLayer.get(key)) {
                positions.put(id, new double[] { x, y });
                layerMaxH = Math.max(layerMaxH, heightOf(sizes, id));
                x += widthOf(sizes, id) + GAP;
            }
            y += layerMaxH + GAP;
        }
        return positions;
    }

    private static Map<String, Set<String>> buildAdj(List<String> nodeIds, List<String[]> edges) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (String id : nodeIds) {
            adj.put(id, new LinkedHashSet<>());
        }
        if (edges != null) {
            for (String[] edge : edges) {
                if (edge == null || edge.length < 2 || edge[0] == null || edge[1] == null) {
                    continue;
                }
                if (adj.containsKey(edge[0])) {
                    adj.get(edge[0]).add(edge[1]);
                }
            }
        }
        return adj;
    }
}
