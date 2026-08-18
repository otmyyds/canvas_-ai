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
 * 结构图布局：为思维导图（中心辐射）/ 流程图（分层）计算卡片坐标。
 * 输入：节点 id 列表 + 边（from/to）列表；输出：节点 id → (x, y)。
 */
public final class GraphLayoutService {

    private static final Logger log = LoggerFactory.getLogger(GraphLayoutService.class);

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
        // 兼容旧调用：所有节点 level=0
        Map<String, Integer> levels = new HashMap<>();
        for (String id : nodeIds) {
            levels.put(id, 0);
        }
        return layoutMindmap3(nodeIds, edges, levels);
    }

    /**
     * 3 层辐射布局：
     * - level 0 中心主题 → 居中
     * - level 1 一级分支 → 沿大圆分布
     * - level 2 二级叶子 → 沿所属一级分支的径向方向向外延伸
     */
    public static Map<String, double[]> layoutMindmap3(List<String> nodeIds, List<String[]> edges,
            Map<String, Integer> nodeLevels) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (nodeIds.isEmpty()) {
            return positions;
        }
        log.info("思维导图布局：节点数={} 边数={}", nodeIds.size(), edges.size());
        // 根：第一个 level 0 节点，或第一个节点
        String root = nodeIds.stream().filter(id -> nodeLevels.getOrDefault(id, 0) == 0).findFirst()
                .orElse(nodeIds.get(0));
        positions.put(root, new double[] { MARGIN, MARGIN });

        // 一级分支：root 的直接子节点
        List<String> level1 = neighbors(nodeIds, edges, root);
        level1.remove(root);
        if (level1.isEmpty()) {
            return positions;
        }
        double radiusX = CARD_W + GAP;
        double radiusY = CARD_H + GAP;
        int n1 = level1.size();
        double startAngle = -Math.PI / 2;
        Map<String, Double> branchAngles = new HashMap<>();
        for (int i = 0; i < n1; i++) {
            double angle = startAngle + (2 * Math.PI * i) / Math.max(1, n1);
            String id = level1.get(i);
            positions.put(id, new double[] {
                    positions.get(root)[0] + radiusX * Math.cos(angle) - CARD_W / 2,
                    positions.get(root)[1] + radiusY * Math.sin(angle) - CARD_H / 2
            });
            branchAngles.put(id, angle);
        }

        // 二级叶子：沿所属一级分支的径向延伸
        Map<String, Set<String>> adj = buildAdj(nodeIds, edges);
        double leafRadiusX = radiusX + CARD_W;
        double leafRadiusY = radiusY + CARD_H;
        for (String branch : level1) {
            Double angle = branchAngles.get(branch);
            if (angle == null) {
                continue;
            }
            List<String> leaves = adj.getOrDefault(branch, Set.of()).stream()
                    .filter(id -> !id.equals(root) && !level1.contains(id))
                    .toList();
            int nLeaves = leaves.size();
            for (int i = 0; i < nLeaves; i++) {
                // 在分支角度的邻域内散开（避免叶子重叠）
                double spread = nLeaves <= 1 ? 0 : ((double) i / (nLeaves - 1) - 0.5) * 0.6;
                double leafAngle = angle + spread;
                String leaf = leaves.get(i);
                positions.put(leaf, new double[] {
                        positions.get(branch)[0] + CARD_W / 2 + leafRadiusX * Math.cos(leafAngle) - CARD_W / 2,
                        positions.get(branch)[1] + CARD_H / 2 + leafRadiusY * Math.sin(leafAngle) - CARD_H / 2
                });
            }
        }
        return positions;
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
