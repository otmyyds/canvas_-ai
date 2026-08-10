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

/**
 * 结构图布局：为思维导图（中心辐射）/ 流程图（分层）计算卡片坐标。
 * 输入：节点 id 列表 + 边（from/to）列表；输出：节点 id → (x, y)。
 */
public final class GraphLayoutService {

    /** 卡片宽高（与前端默认一致） */
    private static final double CARD_W = 340;
    private static final double CARD_H = 220;
    /** 节点间距 */
    private static final double GAP = 120;
    /** 原点边距 */
    private static final double MARGIN = 120;

    private GraphLayoutService() {
    }

    public static Map<String, double[]> layoutMindmap(List<String> nodeIds, List<String[]> edges) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds.isEmpty()) {
            return positions;
        }
        String root = nodeIds.get(0);
        // 邻居：root 的直接分支
        List<String> children = neighbors(nodeIds, edges, root);
        children.removeIf(id -> id.equals(root));
        if (children.isEmpty()) {
            positions.put(root, new double[] { MARGIN, MARGIN });
            return positions;
        }
        // 根居中
        positions.put(root, new double[] { MARGIN + CARD_W / 2, MARGIN + CARD_H / 2 });
        // 分支沿圆分布
        int n = children.size();
        double radiusX = CARD_W + GAP;
        double radiusY = CARD_H + GAP;
        double startAngle = -Math.PI / 2;
        for (int i = 0; i < n; i++) {
            double angle = startAngle + (2 * Math.PI * i) / Math.max(1, n);
            double x = positions.get(root)[0] + radiusX * Math.cos(angle);
            double y = positions.get(root)[1] + radiusY * Math.sin(angle);
            positions.put(children.get(i), new double[] { x - CARD_W / 2, y - CARD_H / 2 });
        }
        return positions;
    }

    public static Map<String, double[]> layoutFlowchart(List<String> nodeIds, List<String[]> edges) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds.isEmpty()) {
            return positions;
        }
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
        // 每层纵向排列（y 递增），层间横向推进（x 递增）
        Map<Integer, List<String>> byLayer = new LinkedHashMap<>();
        for (String id : nodeIds) {
            byLayer.computeIfAbsent(layer.get(id), k -> new ArrayList<>()).add(id);
        }
        double x = MARGIN;
        for (int key : byLayer.keySet().stream().sorted().toList()) {
            double y = MARGIN;
            double layerMaxX = x;
            for (String id : byLayer.get(key)) {
                positions.put(id, new double[] { x, y });
                layerMaxX = Math.max(layerMaxX, x + CARD_W);
                y += CARD_H + GAP;
            }
            x = layerMaxX + GAP;
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

    private static List<String> neighbors(List<String> nodeIds, List<String[]> edges, String id) {
        Set<String> result = new LinkedHashSet<>();
        for (String[] edge : edges) {
            if (edge[0].equals(id)) {
                result.add(edge[1]);
            }
            if (edge[1].equals(id)) {
                result.add(edge[0]);
            }
        }
        return new ArrayList<>(result);
    }
}
