package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;

/** 图形节点（思维导图/流程图节点）读取与编辑接口 */
@RestController
public class ShapeController {

    private static final Logger log = LoggerFactory.getLogger(ShapeController.class);

    private final ShapeRepository shapeRepository;
    private final BoardService boardService;
    private final CurrentUserService currentUserService;

    public ShapeController(ShapeRepository shapeRepository, BoardService boardService,
            CurrentUserService currentUserService) {
        this.shapeRepository = shapeRepository;
        this.boardService = boardService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/boards/{boardId}/shapes")
    public List<ShapeResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询图形节点列表：boardId={}", boardId);
        return shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId).stream()
                .map(ShapeResponse::from)
                .toList();
    }

    /** 回写用户手动编辑（拖动位置 / 改标题 / 改尺寸），统一 tldraw 快照与 shapes 表 */
    @PatchMapping("/api/shapes/{shapeId}")
    public ShapeResponse update(@PathVariable UUID shapeId, @RequestBody ShapeUpdateRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        Shape shape = shapeRepository.findById(shapeId)
                .orElseThrow(() -> new NotFoundException("图形节点不存在"));
        boardService.getOwned(shape.getBoardId(), user);
        shape.applyUserEdit(body.title(), body.x(), body.y(), body.width(), body.height());
        Shape saved = shapeRepository.save(shape);
        log.info("更新图形节点：shapeId={} title={} pos=({},{}) size={}x{}", shapeId, saved.getTitle(),
                saved.getX(), saved.getY(), saved.getWidth(), saved.getHeight());
        return ShapeResponse.from(saved);
    }

    @DeleteMapping("/api/shapes/{shapeId}")
    public void delete(@PathVariable UUID shapeId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除图形节点：shapeId={}", shapeId);
        Shape shape = shapeRepository.findById(shapeId)
                .orElseThrow(() -> new NotFoundException("图形节点不存在"));
        boardService.getOwned(shape.getBoardId(), user);
        shapeRepository.delete(shape);
    }

    public record ShapeResponse(
            UUID id,
            UUID boardId,
            String title,
            String shape,
            int level,
            double x,
            double y,
            double width,
            double height) {

        public static ShapeResponse from(Shape shape) {
            return new ShapeResponse(shape.getId(), shape.getBoardId(), shape.getTitle(), shape.getShape(),
                    shape.getLevel(), shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight());
        }
    }

    /** 用户编辑图形节点的请求体（标题/位置/尺寸） */
    public record ShapeUpdateRequest(String title, double x, double y, double width, double height) {
    }
}
