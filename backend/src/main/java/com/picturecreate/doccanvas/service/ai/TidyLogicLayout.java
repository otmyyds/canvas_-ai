package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 逻辑图布局：van der Ploeg tidy 算法在本项目坐标系下的移植。
 *
 * 参考实现是自顶向下的（深度轴 y、兄弟轴 x）；本项目是左右展开的（深度轴 x、兄弟轴 y）。
 * 因此：
 * - 节点宽度沿深度轴、高度沿兄弟轴；
 * - 轮廓比较的是同一深度上兄弟坐标的上下边界；
 * - 深度端点用 absX + width，轮廓推进看深度端点；
 * - thread 只需兄弟轴修饰量，因为绝对 x 在 firstWalk 之前已经由父宽度累加得到。
 *
 * 与旧实现相比，轮廓从显式分段列表换成 thread 链表，合并只改指针；
 * 兄弟之间的额外间距按规则 7 用 LinkedDepthList + shiftAcceleration/shiftChange 均摊。
 */
final class TidyLogicLayout {

    /** 父内容右边缘到子内容左边缘的水平间距 */
    static final double H_GAP = 40;
    /** 相邻轮廓之间的最小垂直间距 */
    static final double V_GAP = 12;
    /** 多棵树之间的纵向间距 */
    static final double ROOT_GAP = 64;

    /** 布局节点：结构、tidy 修饰量与一次布局的中间状态 */
    static final class Node {
        final String id;
        final double width;
        final double height;
        final List<Node> children = new ArrayList<>();
        Node parent;

        /** 兄弟轴偏好中心，相对父节点子坐标原点 */
        double relCenterY;
        /** 父中心到本子树坐标原点的修饰量 */
        double mod;

        /** 顶部/底部轮廓极值节点及其修饰量 */
        Node extremeTop;
        Node extremeBottom;
        double modExtremeTop;
        double modExtremeBottom;

        /** thread 指向的节点及其兄弟轴修饰量 */
        Node threadTop;
        Node threadBottom;
        double modThreadTop;
        double modThreadBottom;

        /** 规则 7 的等差延迟：给内部小子树均摊间距 */
        double shiftAcceleration;
        double shiftChange;

        /** 深度轴绝对 x（左上角） */
        double absX;

        /** 一级分支最终左右侧（LEFT/RIGHT），非一级分支为 null */
        String effectiveSide;
        /** 一级分支持久化的子树高度，仅用于新增分支时分侧，不参与每次局部计算 */
        double subtreeHeight;
        /** 上次布局坐标（布局坐标系，未叠加画布平移），局部更新时用于提前结束遍历 */
        double layoutX;
        double layoutY;

        Node(String id, double width, double height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }

        boolean isLeaf() {
            return children.isEmpty();
        }
    }

    private static final double EPSILON = 1e-6;

    /**
     * 单节点缓存：布局输入、tidy 中间状态与上次布局坐标。
     * 每请求用数据库里的父节点/子节点顺序/宽高/分侧/折叠结果重新校验，
     * 不依赖任何变更钩子，因此多实例、重启、快照恢复后仍然正确。
     */
    static final class NodeState {
        String parentId;
        List<String> childIds = List.of();
        double width;
        double height;
        String effectiveSide;
        String extremeTopId;
        String extremeBottomId;
        String threadTopId;
        String threadBottomId;
        double relCenterY;
        double mod;
        double modExtremeTop;
        double modExtremeBottom;
        double modThreadTop;
        double modThreadBottom;
        double shiftAcceleration;
        double shiftChange;
        double layoutX;
        double layoutY;
        double subtreeHeight;
    }

    /** 一棵单根逻辑树的缓存状态 */
    static final class LayoutState {
        final Map<String, NodeState> nodes = new LinkedHashMap<>();
    }

    /** 局部布局结果：节点坐标（布局坐标系）与新缓存状态 */
    record IncrementalResult(Map<String, double[]> positions, LayoutState state, String rootId) {
    }

    /** 深度端点链表：记录每个已放置子树的轮廓深度，供规则 7 找到发生碰撞的兄弟 */
    private static final class DepthEntry {
        final int childIndex;
        final double depthEnd;
        final DepthEntry next;

        DepthEntry(int childIndex, double depthEnd, DepthEntry next) {
            this.childIndex = childIndex;
            this.depthEnd = depthEnd;
            this.next = next;
        }
    }

    /** 轮廓游标：沿子树首/末子节点或 thread 前进 */
    private static final class Contour {
        private final boolean top;
        private Node node;
        private double modSum;

        Contour(boolean top, Node node) {
            this.top = top;
            this.node = node;
            this.modSum = node.mod;
        }

        boolean done() {
            return node == null;
        }

        double depthEnd() {
            return node.absX + node.width;
        }

        /** 顶部轮廓取上边缘，底部轮廓取下边缘 */
        double edge() {
            double center = modSum + node.relCenterY;
            return top ? center - node.height / 2 : center + node.height / 2;
        }

        void next() {
            if (top) {
                if (!node.children.isEmpty()) {
                    node = node.children.get(0);
                    modSum += node.mod;
                } else {
                    modSum += node.modThreadTop;
                    node = node.threadTop;
                }
            } else {
                if (!node.children.isEmpty()) {
                    node = node.children.get(node.children.size() - 1);
                    modSum += node.mod;
                } else {
                    modSum += node.modThreadBottom;
                    node = node.threadBottom;
                }
            }
        }
    }

    private TidyLogicLayout() {
    }

    /**
     * 全量布局一棵或多棵树，返回节点 id 到左上角坐标的映射（未整体平移）。
     *
     * @param nodeIds     分量内全部节点 id，保持原有顺序
     * @param tree        由边生成的生成树
     * @param sizes       节点尺寸
     * @param sideByChild 一级分支 id -> LEFT/RIGHT；未指定时按两侧高度自动均衡
     */
    static Map<String, double[]> layout(List<String> nodeIds, GraphLayoutService.SpanningTree tree,
            Map<String, double[]> sizes, Map<String, String> sideByChild) {
        Map<String, Node> nodes = buildNodes(nodeIds, tree, sizes);

        Map<String, double[]> positions = new LinkedHashMap<>();
        double cursorY = 0;
        for (String rootId : tree.roots()) {
            Node root = nodes.get(rootId);
            if (root == null) {
                continue;
            }
            Map<String, double[]> treePositions = layoutTree(root, sideByChild);
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

    /** 按节点 id 与生成树建节点对象，挂好父子关系 */
    private static Map<String, Node> buildNodes(List<String> nodeIds, GraphLayoutService.SpanningTree tree,
            Map<String, double[]> sizes) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (String id : nodeIds) {
            if (id != null && !id.isBlank() && !nodes.containsKey(id)) {
                nodes.put(id, new Node(id, GraphLayoutService.widthOf(sizes, id),
                        GraphLayoutService.heightOf(sizes, id)));
            }
        }
        // 生成树保证每个节点只有一个父节点；只把确实存在的子节点挂上
        for (Map.Entry<String, List<String>> entry : tree.children().entrySet()) {
            Node parent = nodes.get(entry.getKey());
            if (parent == null) {
                continue;
            }
            for (String childId : entry.getValue()) {
                Node child = nodes.get(childId);
                if (child != null && child.parent == null && child != parent) {
                    child.parent = parent;
                    parent.children.add(child);
                }
            }
        }
        return nodes;
    }

    /** 单棵树：根在中间，一级子节点分左右两侧 */
    private static Map<String, double[]> layoutTree(Node root, Map<String, String> sideByChild) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        positions.put(root.id, new double[] { 0, 0 });
        if (root.children.isEmpty()) {
            return positions;
        }

        // 先独立布局每个一级子树，拿到子树高度用于左右分侧；第二遍再按侧分组重排
        double[] heights = new double[root.children.size()];
        for (int i = 0; i < root.children.size(); i++) {
            Node child = root.children.get(i);
            resetSubtree(child);
            assignDepthX(child, root.width + H_GAP);
            firstWalk(child);
            // 规则 7 的间距在 secondWalk 才回放；分侧测量前先应用一次，拿到真实子树高度
            applySpacing(child);
            heights[i] = subtreeHeight(child);
        }

        List<Node> right = new ArrayList<>();
        List<Node> left = new ArrayList<>();
        double rightHeight = 0;
        double leftHeight = 0;
        for (int i = 0; i < root.children.size(); i++) {
            Node child = root.children.get(i);
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
                rightHeight += heights[i] + V_GAP;
            } else {
                left.add(child);
                leftHeight += heights[i] + V_GAP;
            }
        }

        layoutSide(root, right, true, positions);
        layoutSide(root, left, false, positions);
        return positions;
    }

    /**
     * 一侧的一级子节点堆叠：用 tidy firstWalk 处理这一层，最后把首末子节点中心的中点
     * 对齐到根节点中心；左侧只在输出时镜像 x，y 与右侧一致。
     */
    private static void layoutSide(Node root, List<Node> siblings, boolean rightSide,
            Map<String, double[]> positions) {
        if (siblings.isEmpty()) {
            return;
        }
        Node group = new Node("", 0, 0);
        group.children.addAll(siblings);
        for (Node child : siblings) {
            resetSubtree(child);
            child.parent = group;
            assignDepthX(child, root.width + H_GAP);
        }
        firstWalk(group);
        // 先把规则 7 的等差间距回放到一级子节点，再按最终首末中心对齐根中心
        addChildSpacing(group);
        Node first = siblings.get(0);
        Node last = siblings.get(siblings.size() - 1);
        double mid = ((first.relCenterY + first.mod) + (last.relCenterY + last.mod)) / 2;
        double shift = root.height / 2 - mid;
        // 右侧直接使用外展坐标；左侧沿根内容宽度镜像 x（NaN 表示不镜像）
        double mirrorWidth = rightSide ? Double.NaN : root.width;
        for (Node child : siblings) {
            secondWalk(child, shift, 0, mirrorWidth, positions);
        }
    }

    /**
     * 局部布局：以缓存状态为起点，只重算脏节点及其祖先路径。
     *
     * 结构输入每请求从数据库重建并逐项校验，脏节点沿父链传播；
     * 两侧一级分支重新堆叠，但干净子树内部不重算，坐标不变的节点直接跳过。
     * 返回 null 表示不是单根树，由调用方走全量布局。
     */
    static IncrementalResult layoutIncremental(List<String> nodeIds, GraphLayoutService.SpanningTree tree,
            Map<String, double[]> sizes, Map<String, String> sideByChild, LayoutState previous) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return new IncrementalResult(new LinkedHashMap<>(), new LayoutState(), null);
        }
        Map<String, Node> nodes = buildNodes(nodeIds, tree, sizes);
        List<Node> roots = new ArrayList<>();
        for (String rootId : tree.roots()) {
            Node root = nodes.get(rootId);
            if (root != null) {
                roots.add(root);
            }
        }
        if (roots.size() != 1) {
            return null;
        }
        Node root = roots.get(0);
        // 缓存根不是当前树的根：结构已经换根，重新全量
        if (previous != null && !previous.nodes.containsKey(root.id)) {
            previous = null;
        }
        if (previous != null) {
            importState(nodes, previous);
            NodeState rootState = previous.nodes.get(root.id);
            root.layoutX = rootState.layoutX;
            root.layoutY = rootState.layoutY;
        }
        // 深度轴 x 先整体累加，子树高度只取决于兄弟轴，不受根坐标影响
        assignDepthX(root, root.layoutX);

        Set<Node> dirty = new HashSet<>();
        validate(nodes, previous, dirty);
        assignSides(root, sideByChild, previous, dirty);
        if (!dirty.isEmpty()) {
            // 脏节点沿父链传播到根：祖先的居中和极值都要重算
            Deque<Node> stack = new ArrayDeque<>(dirty);
            while (!stack.isEmpty()) {
                Node node = stack.pop();
                Node parent = node.parent;
                if (parent != null && dirty.add(parent)) {
                    stack.push(parent);
                }
            }
            dirty.add(root);
            if (previous != null) {
                // 只清指针，不重算极值：新节点还没有极值，重算会读到空
                for (Node node : dirty) {
                    clearThreadsOnly(node);
                }
            }
        }

        Map<String, double[]> positions = new LinkedHashMap<>();
        positions.put(root.id, new double[] { root.layoutX, root.layoutY });
        if (!root.children.isEmpty() && !dirty.isEmpty()) {
            Node rightGroup = new Node("", 0, 0);
            Node leftGroup = new Node("", 0, 0);
            for (Node child : root.children) {
                if ("LEFT".equals(child.effectiveSide)) {
                    leftGroup.children.add(child);
                } else {
                    rightGroup.children.add(child);
                }
            }
            // 根高变化会改变两侧的居中偏移，两侧都按脏组处理；
            // 组内干净子节点只做极值/thread 失效，不进入内部计算
            dirty.add(rightGroup);
            dirty.add(leftGroup);
            walkSide(root, rightGroup, true, dirty, positions);
            walkSide(root, leftGroup, false, dirty, positions);
        }
        for (Node node : nodes.values()) {
            positions.put(node.id, new double[] { node.layoutX, node.layoutY });
        }
        return new IncrementalResult(positions, exportState(nodes), root.id);
    }

    /** 逐节点比较结构输入，命中差异或消失的节点都算脏 */
    private static void validate(Map<String, Node> nodes, LayoutState previous, Set<Node> dirty) {
        if (previous == null) {
            dirty.addAll(nodes.values());
            return;
        }
        for (Node node : nodes.values()) {
            NodeState state = previous.nodes.get(node.id);
            String parentId = node.parent == null ? null : node.parent.id;
            if (state == null || !Objects.equals(state.parentId, parentId)
                    || !childIds(node).equals(state.childIds)
                    || Math.abs(state.width - node.width) > EPSILON
                    || Math.abs(state.height - node.height) > EPSILON) {
                dirty.add(node);
            }
        }
        for (Map.Entry<String, NodeState> entry : previous.nodes.entrySet()) {
            if (nodes.containsKey(entry.getKey())) {
                continue;
            }
            String parentId = entry.getValue().parentId;
            Node parent = parentId == null ? null : nodes.get(parentId);
            if (parent != null) {
                dirty.add(parent);
            }
        }
    }

    /**
     * 一级分支分侧：显式 side 优先，否则沿用缓存侧；只有新增分支才按两侧高度补位。
     * 已存在的分支不在每次编辑时重新平衡，避免整棵树左右翻转。
     */
    private static void assignSides(Node root, Map<String, String> sideByChild, LayoutState previous,
            Set<Node> dirty) {
        if (root.children.isEmpty()) {
            return;
        }
        boolean needBalance = false;
        for (Node child : root.children) {
            NodeState state = previous == null ? null : previous.nodes.get(child.id);
            String explicit = normalizeSide(sideByChild == null ? null : sideByChild.get(child.id));
            String cached = state == null ? null : state.effectiveSide;
            child.effectiveSide = explicit != null ? explicit : cached;
            if (state != null) {
                child.subtreeHeight = state.subtreeHeight;
            }
            if (child.effectiveSide == null) {
                needBalance = true;
            } else if (state != null && state.effectiveSide != null
                    && !child.effectiveSide.equals(state.effectiveSide)) {
                // 用户显式换侧：整个分支要重新走一遍，子节点的修饰量不再有效
                dirty.add(child);
            }
        }
        if (!needBalance) {
            return;
        }
        // 新增一级分支时重测全部一级子树：缓存高度可能已经过期，而且测量会重置子树状态，
        // 必须同步标脏，让随后的 filtered firstWalk 重新计算。
        for (Node child : root.children) {
            child.subtreeHeight = measureHeight(child, root);
            dirty.add(child);
        }
        double rightHeight = 0;
        double leftHeight = 0;
        for (Node child : root.children) {
            if (child.effectiveSide == null) {
                continue;
            }
            if ("LEFT".equals(child.effectiveSide)) {
                leftHeight += child.subtreeHeight + V_GAP;
            } else {
                rightHeight += child.subtreeHeight + V_GAP;
            }
        }
        // 新分支按当前两侧累计高度放到较矮一侧，等高放右侧，与全量布局规则一致
        for (Node child : root.children) {
            if (child.effectiveSide != null) {
                continue;
            }
            boolean toRight = rightHeight <= leftHeight;
            child.effectiveSide = toRight ? "RIGHT" : "LEFT";
            if (toRight) {
                rightHeight += child.subtreeHeight + V_GAP;
            } else {
                leftHeight += child.subtreeHeight + V_GAP;
            }
        }
    }

    /** 独立测量一棵一级子树的最终高度；会重置并重算该子树，调用方负责标脏 */
    private static double measureHeight(Node child, Node root) {
        resetSubtree(child);
        // 与本次 assignDepthX 使用同一个根坐标原点，避免测量子树与其它兄弟的深度坐标错位
        assignDepthX(child, root.layoutX + root.width + H_GAP);
        firstWalk(child);
        applySpacing(child);
        return subtreeHeight(child);
    }

    private static String normalizeSide(String side) {
        if (side == null) {
            return null;
        }
        if ("LEFT".equalsIgnoreCase(side)) {
            return "LEFT";
        }
        if ("RIGHT".equalsIgnoreCase(side)) {
            return "RIGHT";
        }
        return null;
    }

    /** 把缓存里的 tidy 状态灌回本次新建的节点对象；指针按 id 重新解析 */
    private static void importState(Map<String, Node> nodes, LayoutState previous) {
        for (Node node : nodes.values()) {
            NodeState state = previous.nodes.get(node.id);
            if (state == null) {
                continue;
            }
            node.relCenterY = state.relCenterY;
            node.mod = state.mod;
            node.modExtremeTop = state.modExtremeTop;
            node.modExtremeBottom = state.modExtremeBottom;
            node.modThreadTop = state.modThreadTop;
            node.modThreadBottom = state.modThreadBottom;
            node.shiftAcceleration = state.shiftAcceleration;
            node.shiftChange = state.shiftChange;
            node.effectiveSide = state.effectiveSide;
            node.subtreeHeight = state.subtreeHeight;
            node.layoutX = state.layoutX;
            node.layoutY = state.layoutY;
            node.extremeTop = resolve(nodes, state.extremeTopId);
            node.extremeBottom = resolve(nodes, state.extremeBottomId);
            node.threadTop = resolve(nodes, state.threadTopId);
            node.threadBottom = resolve(nodes, state.threadBottomId);
        }
    }

    private static Node resolve(Map<String, Node> nodes, String id) {
        return id == null ? null : nodes.get(id);
    }

    /** 导出本次布局后的缓存状态 */
    private static LayoutState exportState(Map<String, Node> nodes) {
        LayoutState state = new LayoutState();
        for (Node node : nodes.values()) {
            NodeState ns = new NodeState();
            ns.parentId = node.parent == null ? null : node.parent.id;
            ns.childIds = List.copyOf(childIds(node));
            ns.width = node.width;
            ns.height = node.height;
            ns.effectiveSide = node.effectiveSide;
            ns.extremeTopId = node.extremeTop == null ? null : node.extremeTop.id;
            ns.extremeBottomId = node.extremeBottom == null ? null : node.extremeBottom.id;
            ns.threadTopId = node.threadTop == null ? null : node.threadTop.id;
            ns.threadBottomId = node.threadBottom == null ? null : node.threadBottom.id;
            ns.relCenterY = node.relCenterY;
            ns.mod = node.mod;
            ns.modExtremeTop = node.modExtremeTop;
            ns.modExtremeBottom = node.modExtremeBottom;
            ns.modThreadTop = node.modThreadTop;
            ns.modThreadBottom = node.modThreadBottom;
            ns.shiftAcceleration = node.shiftAcceleration;
            ns.shiftChange = node.shiftChange;
            ns.layoutX = node.layoutX;
            ns.layoutY = node.layoutY;
            ns.subtreeHeight = node.subtreeHeight;
            state.nodes.put(node.id, ns);
        }
        return state;
    }

    private static List<String> childIds(Node node) {
        List<String> ids = new ArrayList<>(node.children.size());
        for (Node child : node.children) {
            ids.add(child.id);
        }
        return ids;
    }

    /** 清掉节点自身极值上的 thread 指针；极值可能已失效，不能在这里重算 */
    private static void clearThreadsOnly(Node node) {
        if (node.extremeTop != null) {
            clearThread(node.extremeTop);
        }
        if (node.extremeBottom != null) {
            clearThread(node.extremeBottom);
        }
    }

    private static void clearThread(Node node) {
        node.threadTop = null;
        node.modThreadTop = 0;
        node.threadBottom = null;
        node.modThreadBottom = 0;
    }

    /** 带过滤的后序 firstWalk：干净子树只失效 thread，不进入内部 */
    private static void firstWalkFiltered(Node root, Set<Node> dirty) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, false));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            Node node = frame.node;
            if (frame.processed) {
                finishNode(node);
                continue;
            }
            if (!dirty.contains(node)) {
                invalidateExtremeThread(node);
                continue;
            }
            if (node.children.isEmpty()) {
                setExtreme(node);
                continue;
            }
            stack.push(new Frame(node, true));
            for (int i = node.children.size() - 1; i >= 0; i--) {
                Node child = node.children.get(i);
                // 与全量 firstWalk 的起点一致：所有子树坐标原点先归零，
                // 有子节点的会在自己的 positionRoot 中重新取 -relCenterY
                child.mod = -child.relCenterY;
                stack.push(new Frame(child, false));
            }
        }
    }

    /** 失效一个干净子树的 thread：重算极值并清掉四个 thread 指针 */
    private static void invalidateExtremeThread(Node node) {
        setExtreme(node);
        clearThread(node.extremeTop);
        clearThread(node.extremeBottom);
    }

    /** 一侧一级分支的局部堆叠与坐标回放 */
    private static void walkSide(Node root, Node group, boolean rightSide, Set<Node> dirty,
            Map<String, double[]> positions) {
        if (group.children.isEmpty()) {
            return;
        }
        firstWalkFiltered(group, dirty);
        addChildSpacing(group);
        Node first = group.children.get(0);
        Node last = group.children.get(group.children.size() - 1);
        double mid = ((first.relCenterY + first.mod) + (last.relCenterY + last.mod)) / 2;
        // shift 里已经包含 root.height/2，基准点只需加上根节点左上角
        double shift = root.height / 2 - mid;
        double baseCenterY = root.layoutY;
        for (Node child : group.children) {
            secondWalkFiltered(child, shift, baseCenterY, root.layoutX, root.layoutX + root.width,
                    rightSide, dirty, positions);
        }
    }

    /** 带过滤的先序 secondWalk：坐标未变且不脏的子树直接跳过 */
    private static void secondWalkFiltered(Node root, double parentModSum, double baseCenterY,
            double mirrorRootX, double mirrorRight, boolean rightSide, Set<Node> dirty,
            Map<String, double[]> positions) {
        Deque<WalkFrame> stack = new ArrayDeque<>();
        stack.push(new WalkFrame(root, parentModSum));
        while (!stack.isEmpty()) {
            WalkFrame frame = stack.pop();
            Node node = frame.node;
            double modSum = frame.parentModSum + node.mod;
            double centerY = baseCenterY + node.relCenterY + modSum;
            // 左侧分支的 absX 同样按右侧算出，再沿根内容宽度镜像
            double x = rightSide ? node.absX : mirrorRight - (node.absX - mirrorRootX) - node.width;
            double y = centerY - node.height / 2;
            boolean moved = Math.abs(node.layoutX - x) > EPSILON || Math.abs(node.layoutY - y) > EPSILON;
            if (!moved && !dirty.contains(node)) {
                continue;
            }
            node.layoutX = x;
            node.layoutY = y;
            if (!node.id.isEmpty()) {
                positions.put(node.id, new double[] { x, y });
            }
            addChildSpacing(node);
            for (int i = node.children.size() - 1; i >= 0; i--) {
                stack.push(new WalkFrame(node.children.get(i), modSum));
            }
        }
    }

    /** 清空一棵子树的 tidy 中间状态，保留结构与尺寸 */
    private static void resetSubtree(Node root) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            node.relCenterY = 0;
            node.mod = 0;
            node.extremeTop = null;
            node.extremeBottom = null;
            node.modExtremeTop = 0;
            node.modExtremeBottom = 0;
            node.threadTop = null;
            node.threadBottom = null;
            node.modThreadTop = 0;
            node.modThreadBottom = 0;
            node.shiftAcceleration = 0;
            node.shiftChange = 0;
            node.absX = 0;
            for (Node child : node.children) {
                stack.push(child);
            }
        }
    }

    /** 先序计算深度轴绝对 x：子节点左边缘距父内容右边缘固定 H_GAP */
    private static void assignDepthX(Node root, double rootX) {
        Deque<Node> stack = new ArrayDeque<>();
        root.absX = rootX;
        stack.push(root);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            double childX = node.absX + node.width + H_GAP;
            for (Node child : node.children) {
                child.absX = childX;
                stack.push(child);
            }
        }
    }

    /**
     * 后序 firstWalk：先排子节点，再逐个与已放置兄弟做轮廓分离，最后让父节点居中并生成极值。
     * 迭代实现，深链不会爆栈。
     */
    private static void firstWalk(Node root) {
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, false));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            Node node = frame.node;
            if (frame.processed) {
                finishNode(node);
                continue;
            }
            stack.push(new Frame(node, true));
            for (int i = node.children.size() - 1; i >= 0; i--) {
                stack.push(new Frame(node.children.get(i), false));
            }
        }
    }

    private static final class Frame {
        final Node node;
        final boolean processed;

        Frame(Node node, boolean processed) {
            this.node = node;
            this.processed = processed;
        }
    }

    /** 子节点都已 firstWalk 完成，处理这一层的兄弟堆叠 */
    private static void finishNode(Node node) {
        if (node.children.isEmpty()) {
            setExtreme(node);
            return;
        }
        Node first = node.children.get(0);
        DepthEntry depthList = new DepthEntry(0, first.extremeBottom.absX + first.extremeBottom.width, null);
        for (int i = 1; i < node.children.size(); i++) {
            Node child = node.children.get(i);
            depthList = separate(node, i, depthList);
            depthList = updateDepthList(depthList, i, child.extremeTop.absX + child.extremeTop.width);
        }
        positionRoot(node);
        setExtreme(node);
    }

    /**
     * 当前子节点与已放置兄弟做轮廓分离：沿前兄弟底部轮廓与当前子节点顶部轮廓前进，
     * 需要下移时整体移动当前子树，并把额外间距按规则 7 分给夹在中间的兄弟。
     */
    private static DepthEntry separate(Node parent, int currentIndex, DepthEntry depthList) {
        Contour previous = new Contour(false, parent.children.get(currentIndex - 1));
        Contour current = new Contour(true, parent.children.get(currentIndex));
        while (!previous.done() && !current.done()) {
            while (previous.depthEnd() > depthList.depthEnd && depthList.next != null) {
                depthList = depthList.next;
            }
            double distance = previous.edge() - current.edge() + V_GAP;
            if (distance > 0) {
                current.modSum += distance;
                moveSubtree(parent, currentIndex, depthList.childIndex, distance);
            }
            double previousEnd = previous.depthEnd();
            double currentEnd = current.depthEnd();
            if (previousEnd <= currentEnd) {
                previous.next();
            }
            if (previousEnd >= currentEnd) {
                current.next();
            }
        }
        if (previous.done() && !current.done()) {
            setTopThread(parent, currentIndex, current.node, current.modSum);
        } else if (!previous.done() && current.done()) {
            setBottomThread(parent, currentIndex, previous.node, previous.modSum);
        }
        return depthList;
    }

    private static DepthEntry updateDepthList(DepthEntry head, int childIndex, double depthEnd) {
        while (head != null && head.depthEnd <= depthEnd) {
            head = head.next;
        }
        return new DepthEntry(childIndex, depthEnd, head);
    }

    private static void setTopThread(Node parent, int currentIndex, Node target, double modifier) {
        Node first = parent.children.get(0);
        Node current = parent.children.get(currentIndex);
        double diff = modifier - first.modExtremeTop - first.mod;
        first.extremeTop.threadTop = target;
        first.extremeTop.modThreadTop = diff;
        first.extremeTop = current.extremeTop;
        first.modExtremeTop = current.modExtremeTop + current.mod - first.mod;
    }

    private static void setBottomThread(Node parent, int currentIndex, Node target, double modifier) {
        Node current = parent.children.get(currentIndex);
        double diff = modifier - current.modExtremeBottom - current.mod;
        current.extremeBottom.threadBottom = target;
        current.extremeBottom.modThreadBottom = diff;
        Node previous = parent.children.get(currentIndex - 1);
        current.extremeBottom = previous.extremeBottom;
        current.modExtremeBottom = previous.modExtremeBottom + previous.mod - current.mod;
    }

    /**
     * 整体移动当前子树的挂点，并把多出来的间距均摊给 fromIndex 与 currentIndex 之间的兄弟。
     * 这是美学规则 7“小内部子树在大子树之间均匀分布”的核心。
     */
    private static void moveSubtree(Node parent, int currentIndex, int fromIndex, double distance) {
        Node current = parent.children.get(currentIndex);
        current.mod += distance;
        if (fromIndex != currentIndex - 1) {
            double indexDiff = currentIndex - fromIndex;
            parent.children.get(fromIndex + 1).shiftAcceleration += distance / indexDiff;
            current.shiftAcceleration -= distance / indexDiff;
            current.shiftChange -= distance - distance / indexDiff;
        }
    }

    /** 父节点中心对齐首末子节点中心的中点，并让自身子树坐标原点回到 0 */
    private static void positionRoot(Node node) {
        Node first = node.children.get(0);
        Node last = node.children.get(node.children.size() - 1);
        node.relCenterY = ((first.relCenterY + first.mod) + (last.relCenterY + last.mod)) / 2;
        node.mod = -node.relCenterY;
    }

    private static void setExtreme(Node node) {
        if (node.children.isEmpty()) {
            node.extremeTop = node;
            node.extremeBottom = node;
            node.modExtremeTop = 0;
            node.modExtremeBottom = 0;
            return;
        }
        Node first = node.children.get(0);
        node.extremeTop = first.extremeTop;
        node.modExtremeTop = first.mod + first.modExtremeTop;
        Node last = node.children.get(node.children.size() - 1);
        node.extremeBottom = last.extremeBottom;
        node.modExtremeBottom = last.mod + last.modExtremeBottom;
    }

    /** 先序回放规则 7 的等差间距，并计算绝对坐标 */
    private static void secondWalk(Node root, double parentModSum, double baseCenterY,
            double mirrorRootWidth, Map<String, double[]> positions) {
        Deque<WalkFrame> stack = new ArrayDeque<>();
        stack.push(new WalkFrame(root, parentModSum));
        while (!stack.isEmpty()) {
            WalkFrame frame = stack.pop();
            Node node = frame.node;
            double modSum = frame.parentModSum + node.mod;
            double centerY = baseCenterY + node.relCenterY + modSum;
            if (!node.id.isEmpty()) {
                double x = Double.isNaN(mirrorRootWidth) ? node.absX : mirrorRootWidth - node.absX - node.width;
                positions.put(node.id, new double[] { x, centerY - node.height / 2 });
            }
            addChildSpacing(node);
            for (int i = node.children.size() - 1; i >= 0; i--) {
                stack.push(new WalkFrame(node.children.get(i), modSum));
            }
        }
    }

    private static final class WalkFrame {
        final Node node;
        final double parentModSum;

        WalkFrame(Node node, double parentModSum) {
            this.node = node;
            this.parentModSum = parentModSum;
        }
    }

    /** 先序回放整棵子树的等差间距，用于测量最终高度；之后会 reset，不影响结果 */
    private static void applySpacing(Node root) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Node node = stack.pop();
            addChildSpacing(node);
            for (Node child : node.children) {
                stack.push(child);
            }
        }
    }

    private static void addChildSpacing(Node node) {
        double speed = 0;
        double delta = 0;
        for (Node child : node.children) {
            speed += child.shiftAcceleration;
            delta += speed + child.shiftChange;
            child.mod += delta;
            child.shiftAcceleration = 0;
            child.shiftChange = 0;
        }
    }

    /** 子树在兄弟轴上的总高度：先序累计修饰量，统计所有节点的上下边界 */
    private static double subtreeHeight(Node root) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        Deque<WalkFrame> stack = new ArrayDeque<>();
        stack.push(new WalkFrame(root, 0));
        while (!stack.isEmpty()) {
            WalkFrame frame = stack.pop();
            Node node = frame.node;
            double modSum = frame.parentModSum + node.mod;
            double center = node.relCenterY + modSum;
            min = Math.min(min, center - node.height / 2);
            max = Math.max(max, center + node.height / 2);
            for (Node child : node.children) {
                stack.push(new WalkFrame(child, modSum));
            }
        }
        return max - min;
    }

    /** 计算一组节点坐标的包围盒：[minX, minY, maxX, maxY] */
    private static double[] bounds(Map<String, double[]> positions, Map<String, Node> nodes) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            Node node = nodes.get(entry.getKey());
            if (node == null) {
                continue;
            }
            double[] point = entry.getValue();
            minX = Math.min(minX, point[0]);
            minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0] + node.width);
            maxY = Math.max(maxY, point[1] + node.height);
        }
        return new double[] { minX, minY, maxX, maxY };
    }
}
