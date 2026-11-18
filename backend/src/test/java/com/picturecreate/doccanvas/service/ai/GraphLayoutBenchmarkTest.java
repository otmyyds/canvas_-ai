package com.picturecreate.doccanvas.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 万节点逻辑图全量布局基准。
 *
 * 不连数据库，只测「生成树 + TidyLogicLayout 全量布局」的计算耗时。
 * 图结构：根节点 1，99 个一级分支（2..100），每个分支 100 个叶子（101..10000），
 * 共 10000 个节点、9999 条边。平衡树深度只有 3，不会因为深链触发栈溢出。
 */
@DisplayName("万节点逻辑图全量布局基准")
class GraphLayoutBenchmarkTest {

    private static final int TOTAL_NODES = 500;
    private static final int BRANCH_COUNT = 99;
    private static final int LEAVES_PER_BRANCH = 100;
    private static final int WARMUP_ROUNDS = 2;
    private static final int MEASURED_ROUNDS = 5;

    @Test
    @DisplayName("10000 节点逻辑图全量布局耗时")
    void benchmarkFullLayout() {
        Fixture fixture = fixture();

        // 先跑两轮让类加载与 JIT 稳定，再记录真实耗时
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            layout(fixture);
        }

        List<Long> costs = new ArrayList<>(MEASURED_ROUNDS);
        Map<String, double[]> positions = Map.of();
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long start = System.nanoTime();
            positions = layout(fixture);
            costs.add((System.nanoTime() - start) / 1_000_000);
        }

        assertThat(positions).hasSize(TOTAL_NODES);
        assertThat(positions.values()).allSatisfy(point -> {
            assertThat(Double.isFinite(point[0])).isTrue();
            assertThat(Double.isFinite(point[1])).isTrue();
        });

        long min = costs.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = costs.stream().mapToLong(Long::longValue).max().orElseThrow();
        double average = costs.stream().mapToLong(Long::longValue).average().orElseThrow();
        System.out.printf("万节点逻辑图全量布局：节点=%d 边=%d%n", TOTAL_NODES, TOTAL_NODES - 1);
        for (int index = 0; index < costs.size(); index++) {
            System.out.printf("  第 %d 轮：%d ms%n", index + 1, costs.get(index));
        }
        System.out.printf("  最小=%d ms 最大=%d ms 平均=%.2f ms%n", min, max, average);
    }

    private static Map<String, double[]> layout(Fixture fixture) {
        return GraphLayoutService.layoutLogicChart(
                fixture.nodeIds(), fixture.edges(), fixture.sizes(), fixture.levels(), Map.of());
    }

    /** 生成平衡逻辑图：根 -> 99 个分支 -> 每个分支 100 个叶子 */
    private static Fixture fixture() {
        List<String> nodeIds = new ArrayList<>(TOTAL_NODES);
        Map<String, Integer> levels = new HashMap<>(TOTAL_NODES);
        Map<String, double[]> sizes = new LinkedHashMap<>(TOTAL_NODES);
        for (int number = 1; number <= TOTAL_NODES; number++) {
            String id = Integer.toString(number);
            nodeIds.add(id);
            levels.put(id, levelOf(number));
            sizes.put(id, new double[] { 160, 60 });
        }

        List<String[]> edges = new ArrayList<>(TOTAL_NODES - 1);
        for (int branch = 0; branch < BRANCH_COUNT; branch++) {
            int branchNumber = 2 + branch;
            edges.add(new String[] { "1", Integer.toString(branchNumber) });
            int firstLeaf = 101 + branch * LEAVES_PER_BRANCH;
            for (int leaf = 0; leaf < LEAVES_PER_BRANCH; leaf++) {
                edges.add(new String[] { Integer.toString(branchNumber),
                        Integer.toString(firstLeaf + leaf) });
            }
        }
        return new Fixture(nodeIds, edges, sizes, levels);
    }

    private static int levelOf(int number) {
        if (number == 1) {
            return 0;
        }
        return number <= 100 ? 1 : 2;
    }

    private record Fixture(List<String> nodeIds, List<String[]> edges, Map<String, double[]> sizes,
            Map<String, Integer> levels) {
    }
}
