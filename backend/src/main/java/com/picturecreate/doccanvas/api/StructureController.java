package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.ShapeController.ShapeResponse;
import com.picturecreate.doccanvas.api.dto.BoardEdgeDtos.BoardEdgeResponse;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.ShapeEditService;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptEdge;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptNode;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptionResult;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.ChildOrder;

import jakarta.servlet.http.HttpServletRequest;

/** 画布结构编辑接口：同级排序、纳管自由图形 */
@RestController
public class StructureController {

    private static final Logger log = LoggerFactory.getLogger(StructureController.class);

    private final ShapeEditService shapeEditService;
    private final CurrentUserService currentUserService;

    public StructureController(ShapeEditService shapeEditService, CurrentUserService currentUserService) {
        this.shapeEditService = shapeEditService;
        this.currentUserService = currentUserService;
    }

    /** 调整某个节点的子节点顺序；逻辑图一级分支同时提交左右侧，后端按新顺序重排分量 */
    @PatchMapping("/api/boards/{boardId}/structure/order")
    public List<BoardEdgeResponse> reorder(@PathVariable UUID boardId, @RequestBody ReorderRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        if (body == null || body.parentId() == null) {
            throw new IllegalArgumentException("缺少父节点 id");
        }
        List<ChildOrder> children = body.children() == null ? List.of()
                : body.children().stream()
                        .map(child -> new ChildOrder(child.edgeId(), child.side()))
                        .toList();
        log.info("调整同级顺序：boardId={} parentId={} 子节点数={}", boardId, body.parentId(), children.size());
        return shapeEditService.reorderChildren(boardId, user, body.parentId(), children).stream()
                .map(BoardEdgeResponse::from)
                .toList();
    }

    /** 纳管画布上手绘的图形与连线：原坐标尺寸落库，返回本地 id 到新节点的映射 */
    @PostMapping("/api/boards/{boardId}/structure/adopt")
    public AdoptResponse adopt(@PathVariable UUID boardId, @RequestBody AdoptRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        if (body == null) {
            throw new IllegalArgumentException("缺少纳管内容");
        }
        List<AdoptNode> nodes = body.nodes() == null ? List.of()
                : body.nodes().stream()
                        .map(node -> new AdoptNode(node.localId(), node.title(), node.shape(),
                                node.level() == null ? 0 : node.level(), node.x(), node.y(),
                                node.width(), node.height(), node.visual()))
                        .toList();
        List<AdoptEdge> edges = body.edges() == null ? List.of()
                : body.edges().stream()
                        .map(edge -> new AdoptEdge(edge.fromLocalId(), edge.toLocalId()))
                        .toList();
        AdoptionResult result = shapeEditService.adopt(boardId, user, nodes, edges);
        log.info("纳管自由图形：boardId={} 节点={} 连线={}", boardId, result.nodes().size(), result.edges().size());
        return new AdoptResponse(
                result.nodes().stream()
                        .map(adopted -> new AdoptedNodeResponse(adopted.localId(), ShapeResponse.from(adopted.shape())))
                        .toList(),
                result.edges().stream().map(BoardEdgeResponse::from).toList());
    }

    /** 同级排序请求：父节点 + 按目标顺序排列的子项 */
    public record ReorderRequest(UUID parentId, List<ChildItem> children) {
    }

    /** 单个子项：连线 id + 逻辑图一级分支的目标侧（LEFT/RIGHT，可空） */
    public record ChildItem(UUID edgeId, String side) {
    }

    /** 纳管请求：自由图形 + 自由连线 */
    public record AdoptRequest(List<AdoptNodeRequest> nodes, List<AdoptEdgeRequest> edges) {
    }

    public record AdoptNodeRequest(String localId, String title, String shape, Integer level, double x, double y,
            double width, double height, Map<String, String> visual) {
    }

    public record AdoptEdgeRequest(String fromLocalId, String toLocalId) {
    }

    public record AdoptResponse(List<AdoptedNodeResponse> nodes, List<BoardEdgeResponse> edges) {
    }

    public record AdoptedNodeResponse(String localId, ShapeResponse shape) {
    }
}
