package com.picturecreate.doccanvas.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.LoginRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.UserInfo;
import com.picturecreate.doccanvas.common.JwtService;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        userAccountRepository.findByEmail(request.email().trim().toLowerCase())
                .ifPresent(existing -> {
                    throw new IllegalStateException("该邮箱已被注册");
                });
        UserAccount user = new UserAccount(request.email().trim().toLowerCase(), request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userAccountRepository.save(user);
        log.info("用户注册成功：userId={} email={}", user.getId(), user.getEmail());
        return buildResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        UserAccount user = userAccountRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("邮箱或密码错误"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalStateException("邮箱或密码错误");
        }
        log.info("用户登录成功：userId={} email={}", user.getId(), email);
        return buildResponse(user);
    }

    /** 刷新访问令牌：用户 id 只从验签通过的 refreshToken 中解析，不信任请求体里的用户标识 */
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        UUID userId = jwtService.parseRefreshToken(refreshToken);
        if (userId == null) {
            throw new IllegalStateException("刷新令牌无效或已过期");
        }
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
        log.info("刷新令牌：userId={}", userId);
        return buildResponse(user);
    }

    private AuthResponse buildResponse(UserAccount user) {
        String token = jwtService.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtService.generateRefreshToken(user.getId());
        return new AuthResponse(token, refreshToken,
                new UserInfo(user.getEmail(), user.getDisplayName()));
    }
}
