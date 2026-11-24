package com.picturecreate.doccanvas.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.service.SetupService;

import jakarta.validation.Valid;

/**
 * 本地轻量版首次启动初始化接口。
 * 不开放注册，只允许在没有任何用户时创建第一个管理员。
 */
@RestController
@RequestMapping("/api/setup")
@Profile("lite")
public class SetupController {

    private final SetupService setupService;

    public SetupController(SetupService setupService) {
        this.setupService = setupService;
    }

    @GetMapping("/status")
    public Result<SetupStatus> status() {
        return Result.success(new SetupStatus(setupService.needsSetup()));
    }

    @PostMapping("/admin")
    public Result<AuthResponse> createAdmin(@Valid @RequestBody RegisterRequest request) {
        return Result.success(setupService.createAdmin(request));
    }

    public record SetupStatus(boolean needsSetup) {
    }
}
