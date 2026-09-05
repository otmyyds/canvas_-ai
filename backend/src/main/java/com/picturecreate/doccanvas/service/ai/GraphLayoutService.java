package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
     */
    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges,
            Map<String, double[]> sizes) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds == null || nodeIds.isEmpty()) {
            return positions;
        }
        log.info("思维导图树布局：节点数={} 边数={}", nodeIds.size(), edges == null ? 0 : edges.size());
        Set<String> all = new LinkedHashSet<>(nodeIds);
        Map<String, List<String>> children = new HashMap<>();
        for (String id : nodeIds) {
            children.put(id, new ArrayList<>());
        }
        Set<String> hasParent = new LinkedHashSet<>();
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
                children.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                hasParent.add(to);
            }
        }
        // 根：入度为 0 的节点
        String root = nodeIds.stream().filter(id -> !hasParent.contains(id)).findFirst().orElse(nodeIds.get(0));
        Map<String, Integer> leafWeight = new HashMap<>();
        computeLeafWeight(root, children, leafWeight);
        double radius = adaptiveRadius(leafWeight.getOrDefault(root, 1), sizes);
        place(root, children, leafWeight, 0, 2 * Math.PI, 0, radius, sizes, positions);
        return positions;
    }

    /** 基础半径：按叶子总数自适应放大，保证最内层叶子的弧长 >= 节点宽度 + 间距 */
    private static double adaptiveRadius(int totalLeaves, Map<String, double[]> sizes) {
        double refWidth = DEFAULT_W;
        if (sizes != null) {
            for (double[] size : sizes.values()) {
                if (size != null && size[0] > 0) {
                    refWidth = Math.max(refWidth, size[0]);
                }
            }
        }
        double minRadius = totalLeaves * (refWidth + GAP) / (2 * Math.PI);
        return Math.max(BASE_RADIUS, minRadius);
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
