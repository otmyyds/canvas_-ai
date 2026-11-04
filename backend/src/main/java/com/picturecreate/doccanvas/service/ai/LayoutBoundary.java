package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 分段常值轮廓：用一组 [x1, x2] 区间上的常值 y 描述子树在水平方向上的占用。
 *
 * 区间按 x 升序且互不重叠，允许空档（该 x 区间没有任何节点）。
 * 逻辑图布局用上下两条轮廓判断兄弟子树之间还能互相靠多近。
 */
final class LayoutBoundary {

    /** 轮廓合并方式 */
    enum CombineOp {
        MAX,
        MIN
    }

    /** 一段轮廓：x ∈ [x1, x2] 时 y 恒为 value */
    record Segment(double x1, double x2, double y) {
    }

    private static final double EPS = 1e-6;

    private final List<Segment> segments;

    private LayoutBoundary(List<Segment> segments) {
        this.segments = List.copyOf(segments);
    }

    /** 用若干段构造，自动按 x 排序并合并相邻同值段 */
    static LayoutBoundary of(Segment... input) {
        List<Segment> sorted = new ArrayList<>();
        for (Segment segment : input) {
            if (segment != null && segment.x2() - segment.x1() > EPS) {
                sorted.add(segment);
            }
        }
        sorted.sort((left, right) -> Double.compare(left.x1(), right.x1()));
        List<Segment> merged = new ArrayList<>();
        for (Segment segment : sorted) {
            append(merged, segment);
        }
        return new LayoutBoundary(merged);
    }

    /** 把整条轮廓平移 */
    LayoutBoundary translate(double dx, double dy) {
        List<Segment> moved = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            moved.add(new Segment(segment.x1() + dx, segment.x2() + dx, segment.y() + dy));
        }
        return new LayoutBoundary(moved);
    }

    /**
     * 与另一条轮廓合并。
     * 两边都定义时按 MAX/MIN 取值；只有一边定义时取定义的一边；两边都没有则保留空档。
     */
    LayoutBoundary combine(LayoutBoundary other, CombineOp op) {
        List<Segment> result = new ArrayList<>();
        for (double[] interval : intervalsWith(other)) {
            Double left = valueAt(interval[0], interval[1]);
            Double right = other.valueAt(interval[0], interval[1]);
            Double value = null;
            if (left != null && right != null) {
                value = op == CombineOp.MAX ? Math.max(left, right) : Math.min(left, right);
            } else if (left != null) {
                value = left;
            } else if (right != null) {
                value = right;
            }
            if (value != null) {
                append(result, new Segment(interval[0], interval[1], value));
            }
        }
        return new LayoutBoundary(result);
    }

    /**
     * 把另一条轮廓整体放到本轮廓下方时，至少需要下移多少（不含额外间距）。
     * 取共同定义区间上 (this.y - other.y) 的最大值：只有每个 x 都让开，两条轮廓才不会交叉。
     * 没有共同定义区间时返回负无穷，表示纵向位置不受约束。
     */
    double separationTo(LayoutBoundary other) {
        double max = Double.NEGATIVE_INFINITY;
        for (double[] interval : intervalsWith(other)) {
            Double left = valueAt(interval[0], interval[1]);
            Double right = other.valueAt(interval[0], interval[1]);
            if (left != null && right != null) {
                max = Math.max(max, left - right);
            }
        }
        return max;
    }

    /** 已定义区间的最小 y */
    double minY() {
        double min = Double.POSITIVE_INFINITY;
        for (Segment segment : segments) {
            min = Math.min(min, segment.y());
        }
        return min;
    }

    /** 已定义区间的最大 y */
    double maxY() {
        double max = Double.NEGATIVE_INFINITY;
        for (Segment segment : segments) {
            max = Math.max(max, segment.y());
        }
        return max;
    }

    /** 取两条边界所有端点构成的相邻区间，用于逐段求值 */
    private List<double[]> intervalsWith(LayoutBoundary other) {
        TreeSet<Double> points = new TreeSet<>();
        points.addAll(breakpoints());
        points.addAll(other.breakpoints());
        List<double[]> intervals = new ArrayList<>();
        Double previous = null;
        for (double point : points) {
            if (previous != null && point - previous > EPS) {
                intervals.add(new double[] { previous, point });
            }
            previous = point;
        }
        return intervals;
    }

    private List<Double> breakpoints() {
        List<Double> points = new ArrayList<>(segments.size() * 2);
        for (Segment segment : segments) {
            points.add(segment.x1());
            points.add(segment.x2());
        }
        return points;
    }

    /** 区间 [x1, x2] 上的常值；取中点避免端点归属歧义 */
    private Double valueAt(double x1, double x2) {
        double probe = (x1 + x2) / 2;
        for (Segment segment : segments) {
            if (probe >= segment.x1() - EPS && probe <= segment.x2() + EPS) {
                return segment.y();
            }
        }
        return null;
    }

    /** 追加一段，相邻同值段直接合并 */
    private static void append(List<Segment> target, Segment segment) {
        if (!target.isEmpty()) {
            Segment last = target.get(target.size() - 1);
            if (Math.abs(last.y() - segment.y()) <= EPS && segment.x1() <= last.x2() + EPS) {
                target.set(target.size() - 1, new Segment(last.x1(), segment.x2(), last.y()));
                return;
            }
        }
        target.add(segment);
    }
}
