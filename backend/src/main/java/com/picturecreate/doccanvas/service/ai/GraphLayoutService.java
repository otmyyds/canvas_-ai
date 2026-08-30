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
 * 输入：节点 id 列表 + 边（from/to）列表；输出：节点 id → (x, y)。
 */
public final class GraphLayoutService {

    private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

    /** 节点间距 */
    private static final double GAP = 120;
    /** 原点边距 */
    private static final double MARGIN = 120;

    private GraphLayoutService() {
    }

    public static Map<String, double[]> layoutMindmap(List<String> nodeIds, List<String[]> edges) {
        // 兼容旧调用：所有节点 level=0
        Map<String, Integer> levels = new HashMap<>();
        for (String id : nodeIds) {
            levels.put(id, 0);
        }
        return layoutMindmap3(nodeIds, edges, levels);
    }

    /** 思维导图树节点尺寸（径向布局用 shape 节点典型尺寸） */
    private static final double NODE_W = 180;
    private static final double NODE_H = 72;
    /** 根到一级分支的基础半径 */
    private static final double BASE_RADIUS = 240;
    /** 每加深一层的半径增量 */
    private static final double LAYER_GAP = 200;

    /**
     * 思维导图树布局（兼容旧入口，层级不再限定三层）。
     * nodeLevels 仅保留签名兼容，递归布局内部按边方向推断层级。
     */
    public static Map<String, double[]> layoutMindmap3(List<String> nodeIds, List<String[]> edges,
            Map<String, Integer> nodeLevels) {
        return layoutMindmapTree(nodeIds, edges);
    }

    /**
     * 递归径向树布局（支持任意深度）：
     * - 边方向 from=父 → to=子（思维导图 connect 恒为父→子）。
     * - 根 = 入度为 0 的节点（无则取第一个）。
     * - 每个节点的子节点按其叶子权重按比例瓜分父节点的角度扇区，叶子在圆周上均匀分布、深层不重叠。
     * - 返回左上角坐标（根中心位于原点，调用方负责整体平移）。
     */
    public static Map<String, double[]> layoutMindmapTree(List<String> nodeIds, List<String[]> edges) {
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
        place(root, children, leafWeight, 0, 2 * Math.PI, 0, positions);
        return positions;
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
            double angleStart, double angleEnd, int depth, Map<String, double[]> positions) {
        double mid = (angleStart + angleEnd) / 2;
        double radius = depth <= 0 ? 0 : BASE_RADIUS + (depth - 1) * LAYER_GAP;
        double cx = radius * Math.cos(mid);
        double cy = radius * Math.sin(mid);
        positions.put(node, new double[] { cx - NODE_W / 2, cy - NODE_H / 2 });
        List<String> kids = children.getOrDefault(node, List.of());
        if (kids.isEmpty()) {
            return;
        }
        double total = weight.getOrDefault(node, kids.size());
        double span = angleEnd - angleStart;
        double cursor = angleStart;
        for (String kid : kids) {
            double kidSpan = span * (weight.getOrDefault(kid, 1) / (double) total);
            place(kid, children, weight, cursor, cursor + kidSpan, depth + 1, positions);
            cursor += kidSpan;
        }
    }

    public static Map<String, double[]> layoutFlowchart(List<String> nodeIds, List<String[]> edges) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds.isEmpty()) {
            return positions;
        }
        log.info("流程图布局：节点数={} 边数={}", nodeIds.size(), edges.size());
        // BFS 分层：入度 0 的根在最上层
        Map<String, Set<String>> adj = buildAdj(nodeIds, edges);
        Map<String, Integer> inDegree = new HashMap<>();
        for (String id : nodeIds) {
            inDegree.put(id, 0);
        }
        for (String[] edge : edges) {
            String from = edge[0];
            String to = edge[1];
            if (inDegree.containsKey(from) && inDegree.containsKey(to)) {
                inDegree.put(to, inDegree.get(to) + 1);
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
        // 每层横向排列（x 递增），层间纵向推进（y 递增，自上而下）
        Map<Integer, List<String>> byLayer = new LinkedHashMap<>();
        for (String id : nodeIds) {
            byLayer.computeIfAbsent(layer.get(id), k -> new ArrayList<>()).add(id);
        }
        double y = MARGIN;
        for (int key : byLayer.keySet().stream().sorted().toList()) {
            double x = MARGIN;
            double layerMaxY = y;
            for (String id : byLayer.get(key)) {
                positions.put(id, new double[] { x, y });
                layerMaxY = Math.max(layerMaxY, y + NODE_H);
                x += NODE_W + GAP;
            }
            y = layerMaxY + GAP;
        }
        return positions;
    }

    private static Map<String, Set<String>> buildAdj(List<String> nodeIds, List<String[]> edges) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (String id : nodeIds) {
            adj.put(id, new LinkedHashSet<>());
        }
        for (String[] edge : edges) {
            if (adj.containsKey(edge[0])) {
                adj.get(edge[0]).add(edge[1]);
            }
        }
        return adj;
    }
}
