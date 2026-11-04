package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 径向树形图布局：半径按每层节点的外接圆尺寸递增，角度按子树所需角宽分配。
 *
 * 层间距用外接圆半径，保证任意角度下相邻层矩形不重叠；
 * 节点角宽由自身尺寸和同层间距换算，子树角宽取自身占用与子节点需求之和的较大值。
 */
final class RadialTreeLayout {

    private static final double TWO_PI = Math.PI * 2;
    private static final double EPS = 1e-9;
    /** 相邻层外接圆之间的净间距 */
    private static final double RADIAL_GAP = 80;
    /** 同层相邻节点之间的弧长间距 */
    private static final double ANGLE_GAP = 32;
    /** 多棵树之间的纵向间距 */
    private static final double ROOT_GAP = 96;
    /** 半径缩放最多迭代次数 */
    private static final int MAX_SCALE_ITERATIONS = 8;

    /** 布局节点：结构与尺寸 */
    private static final class Node {
        private final String id;
        private final double width;
        private final double height;
        private final List<Node> children = new ArrayList<>();

        private Node(String id, double width, double height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }

        /** 外接圆半径：层间距和角度占用都用它，保证任意切向下的矩形投影都被覆盖 */
        private double circumRadius() {
            return Math.hypot(width, height) / 2;
        }
    }

    private RadialTreeLayout() {
    }

    /**
     * 布局一棵或多棵树，返回节点 id 到左上角坐标的映射（未归一化）。
     *
     * @param nodeIds 分量内全部节点 id，保持原有顺序
     * @param tree    由边生成的生成树
     * @param sizes   节点尺寸
     */
    static Map<String, double[]> layout(List<String> nodeIds, GraphLayoutService.SpanningTree tree,
            Map<String, double[]> sizes) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (String id : nodeIds) {
            if (id != null && !id.isBlank() && !nodes.containsKey(id)) {
                nodes.put(id, new Node(id, GraphLayoutService.widthOf(sizes, id),
                        GraphLayoutService.heightOf(sizes, id)));
            }
        }
        Map<String, double[]> positions = new LinkedHashMap<>();
        double cursorY = 0;
        for (String rootId : tree.roots()) {
            Node root = nodes.get(rootId);
            if (root == null) {
                continue;
            }
            buildChildren(root, tree.children(), nodes);
            Map<String, double[]> treePositions = layoutTree(root);
            double[] box = bounds(treePositions, nodes);
            // 每棵树独立居中布局，按包围盒顶边对齐后纵向堆叠
            double offsetX = -box[0];
            double offsetY = cursorY - box[1];
            for (Map.Entry<String, double[]> entry : treePositions.entrySet()) {
                double[] point = entry.getValue();
                positions.put(entry.getKey(), new double[] { point[0] + offsetX, point[1] + offsetY });
            }
            cursorY += box[3] - box[1] + ROOT_GAP;
        }
        return positions;
    }

    /** 按生成树挂接子节点 */
    private static void buildChildren(Node node, Map<String, List<String>> children,
            Map<String, Node> nodes) {
        for (String childId : children.getOrDefault(node.id, List.of())) {
            Node child = nodes.get(childId);
            if (child != null) {
                node.children.add(child);
                buildChildren(child, children, nodes);
            }
        }
    }

    /** 单棵树：深度、层半径、角宽与坐标 */
    private static Map<String, double[]> layoutTree(Node root) {
        List<Node> preorder = new ArrayList<>();
        Map<Node, Integer> depths = new LinkedHashMap<>();
        collect(root, 0, preorder, depths);
        int maxDepth = 0;
        for (int depth : depths.values()) {
            maxDepth = Math.max(maxDepth, depth);
        }

        double[] radii = ringRadii(preorder, depths, maxDepth);
        Map<Node, Double> required = new LinkedHashMap<>();
        int iteration = 0;
        while (true) {
            computeRequired(preorder, depths, radii, required);
            double total = 0;
            for (Node child : root.children) {
                total += required.get(child);
            }
            if (total <= TWO_PI + EPS || iteration >= MAX_SCALE_ITERATIONS) {
                break;
            }
            // 角度需求近似与半径成反比，放大半径后重算
            double scale = Math.max(1.1, total / TWO_PI * 1.05);
            for (int depth = 1; depth < radii.length; depth++) {
                radii[depth] *= scale;
            }
            iteration++;
        }

        Map<String, double[]> positions = new LinkedHashMap<>();
        positions.put(root.id, new double[] { -root.width / 2, -root.height / 2 });
        if (root.children.isEmpty()) {
            return positions;
        }
        double total = 0;
        for (Node child : root.children) {
            total += required.get(child);
        }
        double factor = total <= EPS ? 1 : TWO_PI / total;
        double cursor = -Math.PI / 2;
        for (Node child : root.children) {
            double span = required.get(child) * factor;
            place(child, 1, cursor, cursor + span, depths, radii, required, positions);
            cursor += span;
        }
        return positions;
    }

    /** 先序收集节点并记录深度 */
    private static void collect(Node node, int depth, List<Node> preorder, Map<Node, Integer> depths) {
        preorder.add(node);
        depths.put(node, depth);
        for (Node child : node.children) {
            collect(child, depth + 1, preorder, depths);
        }
    }

    /** 层半径：r(d) = r(d-1) + R(d-1) + R(d) + RADIAL_GAP */
    private static double[] ringRadii(List<Node> preorder, Map<Node, Integer> depths, int maxDepth) {
        double[] maxRadius = new double[maxDepth + 1];
        for (Node node : preorder) {
            int depth = depths.get(node);
            maxRadius[depth] = Math.max(maxRadius[depth], node.circumRadius());
        }
        double[] radii = new double[maxDepth + 1];
        for (int depth = 1; depth <= maxDepth; depth++) {
            radii[depth] = radii[depth - 1] + maxRadius[depth - 1] + maxRadius[depth] + RADIAL_GAP;
        }
        return radii;
    }

    /** 后序计算每棵子树所需角宽 */
    private static void computeRequired(List<Node> preorder, Map<Node, Integer> depths, double[] radii,
            Map<Node, Double> required) {
        for (int index = preorder.size() - 1; index >= 0; index--) {
            Node node = preorder.get(index);
            int depth = depths.get(node);
            double own = depth == 0 ? 0 : ownAngle(node, depth, radii);
            double childrenSum = 0;
            for (Node child : node.children) {
                childrenSum += required.get(child);
            }
            required.put(node, Math.max(own, childrenSum));
        }
    }

    /** 节点在所属层上的最小角宽，两侧各摊一半 ANGLE_GAP */
    private static double ownAngle(Node node, int depth, double[] radii) {
        // 用外接圆半径而不是 max(w, h)/2：任意切向的矩形投影都不超过外接圆半径
        double ratio = (node.circumRadius() + ANGLE_GAP / 2) / radii[depth];
        return 2 * Math.asin(Math.min(1, Math.max(0, ratio)));
    }

    /** 在分配到的角度区间内按 required 比例切分给子节点，并放置当前节点 */
    private static void place(Node node, int depth, double start, double end, Map<Node, Integer> depths,
            double[] radii, Map<Node, Double> required, Map<String, double[]> positions) {
        double middle = (start + end) / 2;
        double radius = radii[depth];
        double centerX = radius * Math.cos(middle);
        double centerY = radius * Math.sin(middle);
        positions.put(node.id, new double[] { centerX - node.width / 2, centerY - node.height / 2 });
        if (node.children.isEmpty()) {
            return;
        }
        double total = 0;
        for (Node child : node.children) {
            total += required.get(child);
        }
        double factor = (end - start) / total;
        double cursor = start;
        for (Node child : node.children) {
            double span = required.get(child) * factor;
            place(child, depth + 1, cursor, cursor + span, depths, radii, required, positions);
            cursor += span;
        }
    }

    /** 计算一组节点坐标的包围盒：[minX, minY, maxX, maxY] */
    private static double[] bounds(Map<String, double[]> positions, Map<String, Node> nodes) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            Node node = nodes.get(entry.getKey());
            double[] point = entry.getValue();
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0] + node.width);
            maxY = Math.max(maxY, point[1] + node.height);
        }
        return new double[] { minX, minY, maxX, maxY };
    }
}
