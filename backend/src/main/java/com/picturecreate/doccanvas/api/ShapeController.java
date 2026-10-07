package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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
        // 必须先校验画布归属：这里直接按 boardId 查表，不校验就等于任何登录用户
        // 传别人的 boardId 就能读到别人画布的全部节点（同类的 update/delete 都有这一步）
        boardService.getOwned(boardId, user);
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

    /**
     * 删除图形节点。
     *
     * 无返回体必须显式声明 204：Spring 对无返回值且无 @ResponseStatus 的处理器默认回 200 + 空 body，
     * 前端 request() 只在 204 时跳过解析，200 会拿空 body 去调 response.json() 抛 SyntaxError。
     */
    @DeleteMapping("/api/shapes/{shapeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
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
