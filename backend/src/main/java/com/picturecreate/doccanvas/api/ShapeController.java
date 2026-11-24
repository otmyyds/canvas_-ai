package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.NotFoundException;
import com.picturecreate.doccanvas.service.ShapeEditService;

import jakarta.servlet.http.HttpServletRequest;

/** 图形节点（思维导图/流程图节点）读取与编辑接口 */
@RestController
public class ShapeController {

    private static final Logger log = LoggerFactory.getLogger(ShapeController.class);
    private final ShapeRepository shapeRepository;
    private final BoardService boardService;
    private final ShapeEditService shapeEditService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public ShapeController(ShapeRepository shapeRepository, BoardService boardService,
            ShapeEditService shapeEditService, CurrentUserService currentUserService, ObjectMapper objectMapper) {
        this.shapeRepository = shapeRepository;
        this.boardService = boardService;
        this.shapeEditService = shapeEditService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    /** 新建节点（body: { parentId, title }）：parentId 为空时创建独立根节点，否则挂到父节点下并重排分量 */
    @PostMapping("/api/boards/{boardId}/shapes")
    public Result<ShapeResponse> create(@PathVariable UUID boardId, @RequestBody ShapeCreateRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("新建图形节点：boardId={} parentId={} title={}", boardId, body.parentId(), body.title());
        Shape shape = shapeEditService.createNode(boardId, user, body.parentId(), body.title());
        return Result.success(ShapeResponse.from(shape));
    }

    @GetMapping("/api/boards/{boardId}/shapes")
    public Result<List<ShapeResponse>> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        // 必须先校验画布归属：这里直接按 boardId 查表，不校验就等于任何登录用户
        // 传别人的 boardId 就能读到别人画布的全部节点（同类的 update/delete 都有这一步）
        boardService.getOwned(boardId, user);
        log.info("查询图形节点列表：boardId={}", boardId);
        return Result.success(shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId).stream()
                .map(ShapeResponse::from)
                .toList());
    }

    /** 回写用户手动编辑（拖动位置 / 改标题 / 改尺寸），统一 tldraw 快照与 shapes 表 */
    @PatchMapping("/api/shapes/{shapeId}")
    public Result<ShapeResponse> update(@PathVariable UUID shapeId, @RequestBody ShapeUpdateRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        Shape shape = shapeRepository.findById(shapeId)
                .orElseThrow(() -> new NotFoundException("图形节点不存在"));
        boardService.getOwned(shape.getBoardId(), user);
        String visualJson = body.visual() == null ? null : writeVisual(body.visual());
        // 标题变化会影响节点尺寸和兄弟位置，交给布局服务按新文字重排所属分量；
        // 拖动、改色、改尺寸等普通编辑仍然只写字段，不触发布局。
        if (body.title() != null && !body.title().equals(shape.getTitle())) {
            Shape saved = shapeEditService.updateTitle(shape.getBoardId(), user, shapeId, body.title(), visualJson);
            log.info("修改图形节点标题：shapeId={} title={} pos=({},{}) size={}x{}", shapeId, saved.getTitle(),
                    saved.getX(), saved.getY(), saved.getWidth(), saved.getHeight());
            return Result.success(ShapeResponse.from(saved));
        }
        Shape saved = shapeEditService.updateFields(shape.getBoardId(), user, shapeId, body.title(),
                body.x(), body.y(), body.width(), body.height(), visualJson);
        log.info("更新图形节点：shapeId={} title={} pos=({},{}) size={}x{}", shapeId, saved.getTitle(),
                saved.getX(), saved.getY(), saved.getWidth(), saved.getHeight());
        return Result.success(ShapeResponse.from(saved));
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
        // 思维导图分量删整棵子树并清理连线，剩余节点原地重排
        shapeEditService.delete(shape.getBoardId(), user, shapeId);
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
            double height,
            Map<String, String> visual,
            boolean collapsed) {

        /** 仅用于解析库里存的视觉属性 JSON，字段都是字符串，不需要全局序列化配置 */
        private static final ObjectMapper VISUAL_MAPPER = new ObjectMapper();

        public static ShapeResponse from(Shape shape) {
            return new ShapeResponse(shape.getId(), shape.getBoardId(), shape.getTitle(), shape.getShape(),
                    shape.getLevel(), shape.getX(), shape.getY(), shape.getWidth(), shape.getHeight(),
                    readVisual(shape.getVisualJson()), shape.isCollapsed());
        }

        private static Map<String, String> readVisual(String visualJson) {
            if (visualJson == null || visualJson.isBlank()) {
                return null;
            }
            try {
                return VISUAL_MAPPER.readValue(visualJson, new TypeReference<Map<String, String>>() {
                });
            } catch (JsonProcessingException exception) {
                return null;
            }
        }
    }

    /** 用户编辑图形节点的请求体（标题/位置/尺寸/视觉属性） */
    public record ShapeUpdateRequest(String title, double x, double y, double width, double height,
            Map<String, String> visual) {
    }

    /** 新建节点的请求体：父节点 id（可为空，表示创建根节点）+ 可选标题 */
    public record ShapeCreateRequest(UUID parentId, String title) {
    }

    /** 视觉属性序列化：写入前转成库里的 JSON 文本 */
    private String writeVisual(Map<String, String> visual) {
        try {
            return objectMapper.writeValueAsString(visual);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("视觉属性格式不正确", exception);
        }
    }
}
