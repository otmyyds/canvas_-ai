package com.picturecreate.doccanvas.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 布局分派判定测试。
 *
 * 径向树布局沿 children 递归下探，边集一旦成环就会一路递归到栈溢出；
 * 而 StackOverflowError 是 Error 不是 Exception，会穿过链路上所有 catch(Exception)，
 * 最终被线程池的 FutureTask 吞掉——表现是 Redis Stream 消费者静默退出、AI 任务永久卡在 RUNNING。
 * 所以成环的边集必须在调用布局前就被 isTree 拦下来。
 */
@DisplayName("图形布局：树判定与分派")
class GraphLayoutServiceTest {

    private static List<String[]> edges(String[]... pairs) {
        return List.of(pairs);
    }

    private static String[] e(String from, String to) {
        return new String[] { from, to };
    }

    @Test
    @DisplayName("真树通过")
    void realTreeAccepted() {
        assertThat(GraphLayoutService.isTree(List.of("A"), edges())).isTrue();
        assertThat(GraphLayoutService.isTree(List.of("A", "B", "C"), edges(e("A", "B"), e("A", "C")))).isTrue();
        assertThat(GraphLayoutService.isTree(List.of("A", "B", "C", "D"),
                edges(e("A", "B"), e("B", "C"), e("B", "D")))).isTrue();
    }

    @Test
    @DisplayName("环一律被拒：2 环、3 环、根不在环内")
    void cyclesRejected() {
        assertThat(GraphLayoutService.isTree(List.of("A", "B"), edges(e("A", "B"), e("B", "A")))).isFalse();
        assertThat(GraphLayoutService.isTree(List.of("A", "B", "C"),
                edges(e("A", "B"), e("B", "C"), e("C", "A")))).isFalse();
        assertThat(GraphLayoutService.isTree(List.of("R", "A", "B"),
                edges(e("R", "A"), e("A", "B"), e("B", "A")))).isFalse();
    }

    @Test
    @DisplayName("边数正好 n-1 但有环时同样被拒：只数边会漏掉孤立节点凑数的这种情况")
    void nMinusOneEdgesWithCycleRejected() {
        List<String> nodes = List.of("A", "B", "C", "D");
        List<String[]> cyclic = edges(e("A", "B"), e("B", "C"), e("C", "A"));
        assertThat(cyclic).hasSize(nodes.size() - 1);
        assertThat(GraphLayoutService.isTree(nodes, cyclic)).isFalse();
    }

    @Test
    @DisplayName("森林与重复边被拒，自环与悬空边直接忽略")
    void otherShapesRejected() {
        assertThat(GraphLayoutService.isTree(List.of("A", "B", "C", "D"),
                edges(e("A", "B"), e("C", "D")))).isFalse();
        assertThat(GraphLayoutService.isTree(List.of("A", "B"), edges(e("A", "B"), e("A", "B")))).isFalse();
        assertThat(GraphLayoutService.isTree(List.of("A"), edges(e("A", "A")))).isTrue();
        assertThat(GraphLayoutService.isTree(List.of("A"), edges(e("X", "Y")))).isTrue();
    }

    @Test
    @DisplayName("流程图布局扛得住环：每个节点都能拿到坐标")
    void flowchartHandlesCycle() {
        Map<String, double[]> positions = GraphLayoutService.layoutFlowchart(List.of("A", "B", "C"),
                edges(e("A", "B"), e("B", "C"), e("C", "A")), null);
        assertThat(positions).containsOnlyKeys("A", "B", "C");
    }
}
