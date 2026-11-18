package com.picturecreate.doccanvas.service.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

/**
 * 纯内存算法基准：不连数据库、不起 Spring 上下文，只测布局算法本体。
 *
 * 新版本（main）调用 TidyLogicLayout.layout；老版本（4b43c75）调用 LogicTreeLayout.layout。
 * 两个版本的 GraphLayoutService.SpanningTree 与 layout 签名完全一致，因此本文件可以原样移植。
 */
class AlgorithmBenchmarkTest {

    /** 6 叉 BFS 树，与 LargeLogicBoardBenchmarkTest 的结构保持一致 */
    private static final int FANOUT = 6;
    private static final int[] SIZES = {1_000, 10_000};
    private static final int ROUNDS = 5;

    @Test
    void benchmarkLayoutOnly() {
        for (int total : SIZES) {
            CaseData data = build(total);
            // 预热两轮，避免把 JIT 编译时间算进结果
            TidyLogicLayout.layout(data.ids, data.tree, data.sizes, data.sideByChild);
            TidyLogicLayout.layout(data.ids, data.tree, data.sizes, data.sideByChild);

            long[] times = new long[ROUNDS];
            for (int i = 0; i < ROUNDS; i++) {
                long start = System.nanoTime();
                Map<String, double[]> positions =
                        TidyLogicLayout.layout(data.ids, data.tree, data.sizes, data.sideByChild);
                times[i] = (System.nanoTime() - start) / 1_000_000L;
                if (positions.size() != total) {
                    throw new AssertionError("布局输出节点数不符：" + positions.size() + " != " + total);
                }
            }
            long[] sorted = times.clone();
            Arrays.sort(sorted);
            System.out.printf("[新算法 TidyLogicLayout] %5d 节点：中位数 %3d ms，5 次 %s%n",
                    total, sorted[ROUNDS / 2], Arrays.toString(times));
        }
    }

    /** 冷态首轮：不预热，直接测第一次调用，用来对照"首轮很慢"的现象 */
    @Test
    void coldFirstCall() {
        Fixture fixture = wideFixture(true);
        long start = System.nanoTime();
        GraphLayoutService.layoutLogicChart(fixture.nodeIds, fixture.edges, fixture.sizes,
                fixture.levels, Map.of());
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.printf("[冷态首轮 99×100=10000 节点] %d ms（未预热）%n", ms);
    }

    /** 把 layoutLogicChart 拆成 spanningTree / 算法本体 / 全链路三段计时，结构与 GraphLayoutBenchmarkTest 完全一致 */
    @Test
    void benchmarkPipelineBreakdown() {
        for (boolean equalWidth : new boolean[] {true, false}) {
            Fixture fixture = wideFixture(equalWidth);
            // 预热全链路两轮
            for (int i = 0; i < 2; i++) {
                GraphLayoutService.layoutLogicChart(fixture.nodeIds, fixture.edges, fixture.sizes,
                        fixture.levels, Map.of());
            }
            GraphLayoutService.SpanningTree tree =
                    GraphLayoutService.spanningTree(fixture.nodeIds, fixture.edges, fixture.levels);

            long spanning = median(this, () -> GraphLayoutService.spanningTree(
                    fixture.nodeIds, fixture.edges, fixture.levels));
            long layoutOnly = median(this, () -> TidyLogicLayout.layout(
                    fixture.nodeIds, tree, fixture.sizes, Map.of()));
            long full = median(this, () -> GraphLayoutService.layoutLogicChart(
                    fixture.nodeIds, fixture.edges, fixture.sizes, fixture.levels, Map.of()));

            System.out.printf("[拆解 99×100 %s] spanningTree=%d ms，TidyLogicLayout.layout=%d ms，"
                    + "layoutLogicChart 全链路=%d ms%n",
                    equalWidth ? "等宽 160×60" : "宽度错开 70~145", spanning, layoutOnly, full);
        }
    }

    private static long median(Object owner, Supplier<?> action) {
        long[] samples = new long[3];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            action.get();
            samples[i] = (System.nanoTime() - start) / 1_000_000L;
        }
        Arrays.sort(samples);
        return samples[1];
    }

    private record Fixture(List<String> nodeIds, List<String[]> edges, Map<String, double[]> sizes,
            Map<String, Integer> levels) {
    }

    /** 与 GraphLayoutBenchmarkTest 完全一致的 99 分支 × 100 叶子结构 */
    private static Fixture wideFixture(boolean equalWidth) {
        final int total = 10_000;
        final int branchCount = 99;
        final int leavesPerBranch = 100;
        List<String> nodeIds = new ArrayList<>(total);
        Map<String, Integer> levels = new LinkedHashMap<>(total);
        Map<String, double[]> sizes = new LinkedHashMap<>(total);
        for (int n = 1; n <= total; n++) {
            String id = Integer.toString(n);
            nodeIds.add(id);
            levels.put(id, n == 1 ? 0 : (n <= 100 ? 1 : 2));
            // 等宽用于对照 GraphLayoutBenchmarkTest；错开用于暴露轮廓分段差异
            sizes.put(id, new double[] {equalWidth ? 160 : (70 + (n % 6) * 15), 60});
        }
        List<String[]> edges = new ArrayList<>(total - 1);
        for (int b = 0; b < branchCount; b++) {
            int branch = 2 + b;
            edges.add(new String[] {"1", Integer.toString(branch)});
            int firstLeaf = 101 + b * leavesPerBranch;
            for (int leaf = 0; leaf < leavesPerBranch; leaf++) {
                edges.add(new String[] {Integer.toString(branch), Integer.toString(firstLeaf + leaf)});
            }
        }
        return new Fixture(nodeIds, edges, sizes, levels);
    }

    private record CaseData(List<String> ids, GraphLayoutService.SpanningTree tree,
            Map<String, double[]> sizes, Map<String, String> sideByChild) {
    }

    /** 6 叉 BFS 树，父节点下标一定小于子节点下标 */
    private static CaseData build(int total) {
        List<String> ids = new ArrayList<>(total);
        Map<String, List<String>> children = new LinkedHashMap<>();
        Map<String, double[]> sizes = new LinkedHashMap<>();
        Map<String, String> sideByChild = new LinkedHashMap<>();

        int cursor = 1;
        for (int parent = 0; parent < total && cursor < total; parent++) {
            List<String> kids = new ArrayList<>();
            for (int k = 0; k < FANOUT && cursor < total; k++, cursor++) {
                kids.add(String.valueOf(cursor));
            }
            if (!kids.isEmpty()) {
                children.put(String.valueOf(parent), kids);
            }
        }

        for (int i = 0; i < total; i++) {
            String id = String.valueOf(i);
            ids.add(id);
            // 宽度 60~140、高度 40~64，模拟真实节点尺寸差异
            sizes.put(id, new double[] {60 + (i % 5) * 20, 40 + (i % 3) * 12});
            children.putIfAbsent(id, new ArrayList<>());
        }
        // 一级分支指定左右侧：前 3 个 RIGHT，后 3 个 LEFT
        for (int i = 1; i <= FANOUT && i < total; i++) {
            sideByChild.put(String.valueOf(i), i <= FANOUT / 2 ? "RIGHT" : "LEFT");
        }

        GraphLayoutService.SpanningTree tree =
                new GraphLayoutService.SpanningTree(List.of("0"), children);
        return new CaseData(ids, tree, sizes, sideByChild);
    }
}
