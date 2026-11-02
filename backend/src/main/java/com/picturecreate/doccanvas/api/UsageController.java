package com.picturecreate.doccanvas.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.QuotaService;
import com.picturecreate.doccanvas.service.QuotaService.UsageSnapshot;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 当前用户的 AI 用量接口。
 * 只按当前登录用户查询，不接受 userId/boardId 等参数，从入口上排除跨用户读取。
 */
@RestController
@RequestMapping("/api")
public class UsageController {

    private final QuotaService quotaService;
    private final CurrentUserService currentUserService;

    public UsageController(QuotaService quotaService, CurrentUserService currentUserService) {
        this.quotaService = quotaService;
        this.currentUserService = currentUserService;
    }

    /** 当日任务数、模型调用次数、token 用量与上限 */
    @GetMapping("/usage/me")
    public UsageSnapshot me(HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return quotaService.snapshot(user);
    }
}
