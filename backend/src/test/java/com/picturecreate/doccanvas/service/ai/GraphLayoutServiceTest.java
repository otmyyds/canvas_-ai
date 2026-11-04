package com.picturecreate.doccanvas.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 布局引擎测试。
 *
 * 树类布局都先构建生成树，回边不进入递归；流程图用 Kahn 最长路径分层，
 * 环上的节点由残余分量兜底。这里验证成环不会丢坐标，以及流程图始终自上而下。
 */
@DisplayName("图形布局：生成树、逻辑图与流程图")
class GraphLayoutServiceTest {

    private static List<String[]> edges(String[]... pairs) {
        return List.of(pairs);
    }

    private static String[] e(String from, String to) {
        return new String[] { from, to };
    }

    @Test
    @DisplayName("树形图扛得住环：2 环、3 环每个节点都有坐标")
    void mindmapHandlesCycle() {
        Map<String, double[]> twoCycle = GraphLayoutService.layoutMindmapTree(List.of("A", "B"),
                edges(e("A", "B"), e("B", "A")), null);
        assertThat(twoCycle).containsOnlyKeys("A", "B");

        Map<String, double[]> threeCycle = GraphLayoutService.layoutMindmapTree(List.of("A", "B", "C"),
                edges(e("A", "B"), e("B", "C"), e("C", "A")), null);
        assertThat(threeCycle).containsOnlyKeys("A", "B", "C");
    }

    @Test
    @DisplayName("逻辑图扛得住环：每个节点都有坐标，子节点在父节点右侧")
    void logicChartHandlesCycle() {
        Map<String, double[]> positions = GraphLayoutService.layoutLogicChart(List.of("A", "B", "C"),
                edges(e("A", "B"), e("B", "C"), e("C", "A")), null, Map.of("A", 0, "B", 1, "C", 2));
        assertThat(positions).containsOnlyKeys("A", "B", "C");
        assertThat(positions.get("B")[0]).isGreaterThan(positions.get("A")[0]);
    }

    @Test
    @DisplayName("流程图扛得住环：每个节点都能拿到坐标")
    void flowchartHandlesCycle() {
        Map<String, double[]> positions = GraphLayoutService.layoutFlowchart(List.of("A", "B", "C"),
                edges(e("A", "B"), e("B", "C"), e("C", "A")), null);
        assertThat(positions).containsOnlyKeys("A", "B", "C");
    }

    @Test
    @DisplayName("流程图始终自上而下：链状节点的 y 逐层递增")
    void flowchartIsVertical() {
        Map<String, double[]> positions = GraphLayoutService.layoutFlowchart(List.of("A", "B", "C"),
                edges(e("A", "B"), e("B", "C")), null);
        assertThat(positions.get("B")[1]).isGreaterThan(positions.get("A")[1]);
        assertThat(positions.get("C")[1]).isGreaterThan(positions.get("B")[1]);
    }

    @Test
    @DisplayName("流程图按最长路径分层：汇聚节点在所有前驱的下一层")
    void flowchartUsesLongestPath() {
        Map<String, double[]> positions = GraphLayoutService.layoutFlowchart(List.of("A", "B", "C", "D"),
                edges(e("A", "B"), e("A", "C"), e("B", "D"), e("C", "D")), null);
        assertThat(positions.get("B")[1]).isEqualTo(positions.get("C")[1]);
        assertThat(positions.get("D")[1]).isGreaterThan(positions.get("B")[1]);
    }
}
