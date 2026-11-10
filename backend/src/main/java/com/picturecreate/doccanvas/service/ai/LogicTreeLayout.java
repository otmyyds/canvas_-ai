package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 逻辑图布局（双向思维导图风格）：根在中间，一级子节点按子树高度分到左右两侧。
 * 右侧子树向右展开，左侧子树先按右侧规则布局再沿根内容中轴镜像向左展开。
 *
 * 每侧内部自底向上计算子树轮廓，再用分段轮廓让兄弟子树在空白处互相咬合。
 * 坐标约定：x 为分支轴（右侧为正、左侧为负），y 向下为兄弟轴；节点局部原点为内容左上角。
 * 同一父节点的子内容边缘相距父内容边缘固定 H_GAP，因此不同分支的 x 可以不同。
 */
final class LogicTreeLayout {

    /** 父内容右边缘到子内容左边缘的水平间距（fork 节点已收缩，留出文字呼吸空间） */
    private static final double H_GAP = 40;
    /** 相邻轮廓之间的最小垂直间距 */
    private static final double V_GAP = 12;
    /** 多棵树之间的纵向间距 */
    private static final double ROOT_GAP = 64;

    /** 布局节点：结构与子树几何 */
    private static final class Node {
        private final String id;
        private final double width;
        private final double height;
        private final List<Node> children = new ArrayList<>();
        /** 相对父内容左上角的坐标；根节点固定为 0 */
        private double x;
        private double y;
        private double subtreeWidth;
        private double subtreeHeight;
        /** 相对本节点内容左上角的上下轮廓；父节点消费后置空以控制内存 */
        private LayoutBoundary top;
        private LayoutBoundary bottom;

        private Node(String id, double width, double height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }
    }

    private LogicTreeLayout() {
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
        return layout(nodeIds, tree, sizes, null);
    }

    /**
     * 布局一棵或多棵树，按一级分支的指定侧分区。
     *
     * @param sideByChild 一级分支节点 id → LEFT/RIGHT；未指定或为 null 时按两侧高度自动均衡
     */
    static Map<String, double[]> layout(List<String> nodeIds, GraphLayoutService.SpanningTree tree,
            Map<String, double[]> sizes, Map<String, String> sideByChild) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        // 补充节点信息（id,width,height）
        for (String id : nodeIds) {
            if (id != null && !id.isBlank() && !nodes.containsKey(id)) {
                nodes.put(id, new Node(id, GraphLayoutService.widthOf(sizes, id),
                        GraphLayoutService.heightOf(sizes, id)));
            }
        }
        Map<String, double[]> positions = new LinkedHashMap<>();
        double cursorY = 0;
        // tree 记录了节点和各个节点的子节点
        // 查找各个节点的子节点并补充节点的信息到 nodes
        for (String rootId : tree.roots()) {
            Node root = nodes.get(rootId);
            if (root == null) {
                continue;
            }
            // 补充nodes中子节点的信息包括子树高度和width
            buildChildren(root, tree.children(), nodes);
            Map<String, double[]> treePositions = layoutRoot(root, sideByChild);
            double[] box = bounds(treePositions, nodes);
            // 每棵树独立布局，按包围盒左上角对齐后纵向堆叠
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

    /**
     * 单棵树：一级子节点按指定侧分区；未指定侧的按子树高度平衡分到较矮的一侧。
     * 侧内保持 children 顺序（即后端连线排序），右侧直接向右展开、左侧镜像向左展开。
     */
    private static Map<String, double[]> layoutRoot(Node root, Map<String, String> sideByChild) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        positions.put(root.id, new double[] { 0, 0 });
        if (root.children.isEmpty()) {
            return positions;
        }
        // 先完成每棵一级子树自身的布局，才能拿到子树高度用于左右分侧
        for (Node child : root.children) {
            layoutSubtree(child);
        }
        List<Node> right = new ArrayList<>();
        List<Node> left = new ArrayList<>();
        double rightHeight = 0;
        double leftHeight = 0;
        for (Node child : root.children) {
            String side = sideByChild == null ? null : sideByChild.get(child.id);
            boolean toRight;
            if ("LEFT".equalsIgnoreCase(side)) {
                toRight = false;
            } else if ("RIGHT".equalsIgnoreCase(side)) {
                toRight = true;
            } else {
                // 未指定侧：优先放到较矮的一侧，等高时放右侧，使两侧总高度接近
                toRight = rightHeight <= leftHeight;
            }
            if (toRight) {
                right.add(child);
                rightHeight += child.subtreeHeight + V_GAP;
            } else {
                left.add(child);
                leftHeight += child.subtreeHeight + V_GAP;
            }
        }
        double childX = root.width + H_GAP;
        stackSide(right, childX, root.height / 2);
        stackSide(left, childX, root.height / 2);
        for (Node child : right) {
            placeAbsolute(child, 0, 0, positions);
        }
        for (Node child : left) {
            placeMirrored(child, 0, 0, root.width, positions);
        }
        return positions;
    }

    /**
     * 一侧的一级子节点堆叠：沿用轮廓咬合，最后把该侧首末子节点内容中心的均值
     * 对齐到根内容中心，使左右两侧都围绕根居中。
     */
    private static void stackSide(List<Node> siblings, double childX, double centerY) {
        if (siblings.isEmpty()) {
            return;
        }
        LayoutBoundary mergedBottom = null;
        for (int index = 0; index < siblings.size(); index++) {
            Node child = siblings.get(index);
            child.x = childX;
            if (index == 0) {
                child.y = 0;
            } else {
                // 当前子节点要整体避开已放置兄弟的底部轮廓，取所有共同 x 上所需下移量的最大值
                double separation = mergedBottom.separationTo(child.top.translate(childX, 0));
                child.y = Double.isInfinite(separation) ? 0 : V_GAP + separation;
            }
            LayoutBoundary placedBottom = child.bottom.translate(childX, child.y);
            mergedBottom = mergedBottom == null
                    ? placedBottom
                    : mergedBottom.combine(placedBottom, LayoutBoundary.CombineOp.MAX);
        }
        Node first = siblings.get(0);
        Node last = siblings.get(siblings.size() - 1);
        double mid = (first.y + first.height / 2 + last.y + last.height / 2) / 2;
        double shift = centerY - mid;
        for (Node child : siblings) {
            child.y += shift;
            // 轮廓已被根节点消费，及时释放
            child.top = null;
            child.bottom = null;
        }
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

    /** 后序布局：先排子节点，再堆叠兄弟，最后生成本子树轮廓 */
    private static void layoutSubtree(Node node) {
        // 叶子节点直接生成轮廓
        if (node.children.isEmpty()) {
            node.subtreeWidth = node.width;
            node.subtreeHeight = node.height;
            node.top = LayoutBoundary.of(new LayoutBoundary.Segment(0, node.width, 0));
            node.bottom = LayoutBoundary.of(new LayoutBoundary.Segment(0, node.width, node.height));
            return;
        }

        for (Node child : node.children) {
            layoutSubtree(child);
        }

        double childX = node.width + H_GAP;
        LayoutBoundary mergedBottom = null;
        for (int index = 0; index < node.children.size(); index++) {
            Node child = node.children.get(index);
            // 所有子节点内容左边缘与父内容右边缘保持固定间距
            child.x = childX;
            if (index == 0) {
                child.y = 0;
            } else {
                // 当前子节点要整体避开已放置兄弟的底部轮廓，取所有共同 x 上所需下移量的最大值
                double separation = mergedBottom.separationTo(child.top.translate(childX, 0));
                // 无共同 x 区间时纵向不受约束；兄弟共享 childX 且宽度为正，正常不会走到这里
                child.y = Double.isInfinite(separation) ? 0 : V_GAP + separation;
            }
            // 当前子节点的底部轮廓
            LayoutBoundary placedBottom = child.bottom.translate(childX, child.y);
            // 累加当前子节点的底部轮廓
            mergedBottom = mergedBottom == null
                    ? placedBottom
                    : mergedBottom.combine(placedBottom, LayoutBoundary.CombineOp.MAX);
        }

        // 父内容垂直中心对齐首末子节点内容中心的均值
        Node first = node.children.get(0);
        Node last = node.children.get(node.children.size() - 1);
        double mid = (first.y + first.height / 2 + last.y + last.height / 2) / 2;
        double shift = node.height / 2 - mid;
        for (Node child : node.children) {
            child.y += shift;
        }

        // 自身内容段与子轮廓合并：顶部取 MIN，底部取 MAX；空档保持未定义
        LayoutBoundary top = LayoutBoundary.of(new LayoutBoundary.Segment(0, node.width, 0));
        LayoutBoundary bottom = LayoutBoundary.of(new LayoutBoundary.Segment(0, node.width, node.height));
        double maxChildWidth = 0;
        for (Node child : node.children) {
            top = top.combine(child.top.translate(childX, child.y), LayoutBoundary.CombineOp.MIN);
            bottom = bottom.combine(child.bottom.translate(childX, child.y), LayoutBoundary.CombineOp.MAX);
            maxChildWidth = Math.max(maxChildWidth, child.subtreeWidth);
            // 轮廓已被父节点消费，及时释放
            child.top = null;
            child.bottom = null;
        }
        node.top = top;
        node.bottom = bottom;
        node.subtreeWidth = node.width + H_GAP + maxChildWidth;
        node.subtreeHeight = node.bottom.maxY() - node.top.minY();
    }

    /** 先序回填绝对坐标 */
    private static void placeAbsolute(Node node, double parentX, double parentY,
            Map<String, double[]> positions) {
        double absX = parentX + node.x;
        double absY = parentY + node.y;
        positions.put(node.id, new double[] { absX, absY });
        for (Node child : node.children) {
            placeAbsolute(child, absX, absY, positions);
        }
    }

    /**
     * 先序回填左侧子树的镜像坐标：沿根内容宽度翻转 x，保持父子水平间距不变。
     * localX 仍是右侧布局时的外展坐标，因此翻转只需一次公式，子树内部结构自然成为镜像。
     */
    private static void placeMirrored(Node node, double parentX, double parentY, double rootWidth,
            Map<String, double[]> positions) {
        double localX = parentX + node.x;
        double localY = parentY + node.y;
        double absX = rootWidth - localX - node.width;
        positions.put(node.id, new double[] { absX, localY });
        for (Node child : node.children) {
            placeMirrored(child, localX, localY, rootWidth, positions);
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
