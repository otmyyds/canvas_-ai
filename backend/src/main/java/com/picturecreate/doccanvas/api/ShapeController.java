package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;

/** 图形节点（思维导图/流程图节点）读取接口 */
@RestController
public class ShapeController {

    private final ShapeRepository shapeRepository;
    private final CurrentUserService currentUserService;

    public ShapeController(ShapeRepository shapeRepository, CurrentUserService currentUserService) {
        this.shapeRepository = shapeRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/boards/{boardId}/shapes")
    public List<ShapeResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        // 校验 owner
        user.id();
        return shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId).stream()
                .map(ShapeResponse::from)
                .toList();
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
