package com.picturecreate.doccanvas.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

/**
 * 本地轻量版首次启动初始化。
 * 只有数据库里没有任何用户时才允许创建管理员，避免重复初始化。
 */
@Service
@Profile("lite")
public class SetupService {

    private final UserAccountRepository userAccountRepository;
    private final AuthService authService;

    public SetupService(UserAccountRepository userAccountRepository, AuthService authService) {
        this.userAccountRepository = userAccountRepository;
        this.authService = authService;
    }

    @Transactional(readOnly = true)
    public boolean needsSetup() {
        return userAccountRepository.count() == 0;
    }

    @Transactional
    public synchronized AuthResponse createAdmin(RegisterRequest request) {
        if (!needsSetup()) {
            throw new IllegalStateException("系统已完成初始化");
        }
        return authService.createInitialAdmin(request);
    }
}
