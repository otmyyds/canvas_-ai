package com.picturecreate.doccanvas.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.LoginRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RefreshRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.service.AuthService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest body) {
        log.info("用户注册：email={}", body.email());
        return Result.success(authService.register(body));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest body) {
        log.info("用户登录：email={}", body.email());
        return Result.success(authService.login(body));
    }

    /** 刷新访问令牌：只接收 refreshToken，用户 id 由后端验签后从 claims 解析 */
    @PostMapping("/refresh")
    public Result<AuthResponse> refresh(@Valid @RequestBody RefreshRequest body) {
        log.info("刷新访问令牌");
        return Result.success(authService.refresh(body.refreshToken()));
    }
}
