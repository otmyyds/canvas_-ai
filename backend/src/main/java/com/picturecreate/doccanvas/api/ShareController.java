package com.picturecreate.doccanvas.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.dto.ShareDtos.ShareLinkResponse;
import com.picturecreate.doccanvas.api.dto.ShareDtos.SharedBoardResponse;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.ShareService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 分享链接接口。
 * - POST /api/boards/{boardId}/share → owner 生成只读分享链接（需登录）
 * - GET  /api/share/{token}          → 凭 token 只读读取分享画布（公开，SecurityConfig 放行）
 */
@RestController
public class ShareController {

    private final ShareService shareService;
    private final CurrentUserService currentUserService;

    public ShareController(ShareService shareService, CurrentUserService currentUserService) {
        this.shareService = shareService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/boards/{boardId}/share")
    public ShareLinkResponse createShareLink(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return shareService.createShareLink(boardId, user);
    }

    @GetMapping("/api/share/{token}")
    public SharedBoardResponse readSharedBoard(@PathVariable String token) {
        return shareService.readSharedBoard(token);
    }
}
