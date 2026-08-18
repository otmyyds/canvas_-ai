package com.picturecreate.doccanvas.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.LoginRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.service.AuthService;
import com.picturecreate.doccanvas.service.NotFoundException;

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
    public AuthResponse register(@Valid @RequestBody RegisterRequest body) {
        log.info("用户注册：email={}", body.email());
        return authService.register(body);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest body) {
        log.info("用户登录：email={}", body.email());
        return authService.login(body);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@RequestBody RefreshBody body) {
        UUID userId = body.userId() == null ? null : UUID.fromString(body.userId());
        if (userId == null) {
            throw new NotFoundException("缺少用户标识");
        }
        log.info("刷新令牌：userId={}", userId);
        return authService.refresh(userId);
    }

    public record RefreshBody(String userId, String refreshToken) {
    }
}
