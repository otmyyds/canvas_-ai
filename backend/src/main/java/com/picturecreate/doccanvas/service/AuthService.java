package com.picturecreate.doccanvas.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.LoginRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.UserInfo;
import com.picturecreate.doccanvas.common.JwtUtils;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

@Service
public class AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserAccountRepository userAccountRepository, PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
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
        return buildResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(java.util.UUID userId) {
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("用户不存在"));
        return buildResponse(user);
    }

    private AuthResponse buildResponse(UserAccount user) {
        String token = JwtUtils.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = JwtUtils.generateRefreshToken(user.getId());
        return new AuthResponse(token, refreshToken,
                new UserInfo(user.getId(), user.getEmail(), user.getDisplayName()));
    }
}
