package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardRequest;
import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardResponse;
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
 * - GET    /            → 当前用户所有画布
 * - POST   /            → 新建画布
 * - GET    /{boardId}   → 单个画布详情
 * - PATCH  /{boardId}   → 重命名画布
 * - DELETE /{boardId}   → 删除画布（连带图形节点/快照/连线）
 */
@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private static final Logger log = LoggerFactory.getLogger(BoardController.class);

    private final BoardService boardService;
    private final CurrentUserService currentUserService;

    public BoardController(BoardService boardService, CurrentUserService currentUserService) {
        this.boardService = boardService;
        this.currentUserService = currentUserService;
    }

    /** 获取当前用户的全部画布，按更新时间倒序 */
    @GetMapping
    public List<BoardResponse> list(HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询画布列表：userId={}", user.id());
        return boardService.list(user).stream().map(BoardResponse::from).toList();
    }

    /** 新建画布（body: { title }） */
    @PostMapping
    public BoardResponse create(@RequestBody BoardRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("新建画布：title={}", body.title());
        return BoardResponse.from(boardService.create(user, body.title()));
    }

    /** 获取单个画布详情（校验归属，非本人画布返回 404） */
    @GetMapping("/{boardId}")
    public BoardResponse get(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询画布详情：boardId={}", boardId);
        return BoardResponse.from(boardService.getOwned(boardId, user));
    }

    /** 重命名画布 */
    @PatchMapping("/{boardId}")
    public BoardResponse rename(@PathVariable UUID boardId, @RequestBody BoardRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("重命名画布：boardId={} title={}", boardId, body.title());
        return BoardResponse.from(boardService.rename(boardId, user, body.title()));
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
}
