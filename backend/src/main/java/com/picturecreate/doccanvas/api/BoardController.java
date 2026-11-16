package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardLayoutRequest;
import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardPageResponse;
import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardRequest;
import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardResponse;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.service.BoardLayoutService;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 画布（Board）接口控制器。
 *
 * 职责：画布的增删改查（CRUD）。
 * 所有接口都通过 CurrentUserService 解析当前用户（JWT 或演示头），
 * 数据按 owner 隔离（BoardService 校验画布归属）。
 *
 * 接口一览（前缀 /api/boards）：
 * - GET    /                  → 当前用户画布（分页 + 标题搜索）
 * - POST   /                  → 新建画布
 * - GET    /{boardId}         → 单个画布详情
 * - PATCH  /{boardId}         → 重命名画布
 * - PATCH  /{boardId}/layout  → 切换思维导图排布风格并立即重排
 * - POST   /{boardId}/layout/apply → 整理画布（按连通分量整张重排）
 * - DELETE /{boardId}         → 删除画布（连带图形节点/快照/连线）
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private static final Logger log = LoggerFactory.getLogger(BoardController.class);

    private final BoardService boardService;
    private final BoardLayoutService boardLayoutService;
    private final CurrentUserService currentUserService;
    private final AppProperties properties;

    public BoardController(BoardService boardService, BoardLayoutService boardLayoutService,
            CurrentUserService currentUserService, AppProperties properties) {
        this.boardService = boardService;
        this.boardLayoutService = boardLayoutService;
        this.currentUserService = currentUserService;
        this.properties = properties;
    }

    /** 分页获取当前用户的画布，按更新时间倒序；keyword 按标题模糊匹配 */
    @GetMapping
    public Result<BoardPageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "9") int size,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询画布列表：userId={} page={} size={} keyword={}", user.id(), page, size, keyword);
        Page<Board> result = boardService.list(user, page, size, keyword);
        List<BoardResponse> items = result.getContent().stream().map(this::toResponse).toList();
        return Result.success(new BoardPageResponse(items, result.getNumber() + 1, result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    /** 新建画布（body: { title }） */
    @PostMapping
    public Result<BoardResponse> create(@RequestBody BoardRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("新建画布：title={}", body.title());
        return Result.success(toResponse(boardService.create(user, body.title())));
    }

    /** 获取单个画布详情（校验归属，非本人画布返回 404） */
    @GetMapping("/{boardId}")
    public Result<BoardResponse> get(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询画布详情：boardId={}", boardId);
        return Result.success(toResponse(boardService.getOwned(boardId, user)));
    }

    /** 重命名画布 */
    @PatchMapping("/{boardId}")
    public Result<BoardResponse> rename(@PathVariable UUID boardId, @RequestBody BoardRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("重命名画布：boardId={} title={}", boardId, body.title());
        return Result.success(toResponse(boardService.rename(boardId, user, body.title())));
    }

    /** 切换思维导图排布风格（树形图 / 逻辑图），并立即按新风格重排画布 */
    @PatchMapping("/{boardId}/layout")
    public Result<BoardResponse> updateLayout(@PathVariable UUID boardId, @RequestBody BoardLayoutRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("切换画布布局：boardId={} style={}", boardId, body.layoutStyle());
        return Result.success(toResponse(boardLayoutService.updateLayoutStyle(boardId, user, body.layoutStyle())));
    }

    /** 整理画布：按连通分量把整张画布重新布局 */
    @PostMapping("/{boardId}/layout/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void applyLayout(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("整理画布：boardId={}", boardId);
        boardLayoutService.applyLayout(boardId, user);
    }

    /**
     * 删除画布（服务层删除，数据库级联清理关联数据）。
     *
     * 无返回体必须显式声明 204：Spring 对无返回值且无 @ResponseStatus 的处理器默认回 200 + 空 body，
     * 前端 request() 只在 204 时跳过解析，200 会拿空 body 去调 response.json() 抛 SyntaxError。
     */
    @DeleteMapping("/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除画布：boardId={}", boardId);
        boardService.delete(boardId, user);
    }

    private BoardResponse toResponse(Board board) {
        return BoardResponse.from(board, properties.ai().layout().radialMaxNodes());
    }
}
