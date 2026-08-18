package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.NotFoundException;

import jakarta.servlet.http.HttpServletRequest;

/** 图形节点（思维导图/流程图节点）读取接口 */
@RestController
public class ShapeController {

    private static final Logger log = LoggerFactory.getLogger(ShapeController.class);

    private final ShapeRepository shapeRepository;
    private final CurrentUserService currentUserService;

    public ShapeController(ShapeRepository shapeRepository, CurrentUserService currentUserService) {
        this.shapeRepository = shapeRepository;
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

    @DeleteMapping("/api/shapes/{shapeId}")
    public void delete(@PathVariable UUID shapeId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除图形节点：shapeId={}", shapeId);
        Shape shape = shapeRepository.findById(shapeId)
                .orElseThrow(() -> new NotFoundException("图形节点不存在"));
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
}
