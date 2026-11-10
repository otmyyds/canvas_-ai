package com.picturecreate.doccanvas.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.UserDtos.ChangePasswordRequest;
import com.picturecreate.doccanvas.api.dto.UserDtos.UpdateProfileRequest;
import com.picturecreate.doccanvas.api.dto.UserDtos.UserProfileResponse;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

/**
 * 用户资料：查询可展示字段，支持修改昵称/邮箱与密码。
 * 内部用户 id 只在服务端使用，不返回给前端。
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final UserAccountRepository userAccountRepository;
    private final BoardRepository boardRepository;
    private final PasswordEncoder passwordEncoder;

    public UserProfileService(UserAccountRepository userAccountRepository, BoardRepository boardRepository,
            PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.boardRepository = boardRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse profile(CurrentUser user) {
        return toResponse(requireAccount(user));
    }

    /** 修改昵称和邮箱；邮箱统一小写并校验唯一 */
    @Transactional
    public UserProfileResponse updateProfile(CurrentUser user, UpdateProfileRequest request) {
        UserAccount account = requireAccount(user);
        String email = request.email().trim().toLowerCase();
        userAccountRepository.findByEmail(email).ifPresent(existing -> {
            if (!existing.getId().equals(account.getId())) {
                throw new IllegalStateException("该邮箱已被其他账号使用");
            }
        });
        account.setEmail(email);
        account.setDisplayName(request.displayName().trim());
        // 显式 flush，让 BaseEntity 的 @PreUpdate 刷新 updatedAt 后再组装响应
        userAccountRepository.saveAndFlush(account);
        log.info("用户资料已更新：userId={}", account.getId());
        return toResponse(account);
    }

    /** 修改密码：先校验当前密码，再写入新的 BCrypt 哈希 */
    @Transactional
    public void changePassword(CurrentUser user, ChangePasswordRequest request) {
        UserAccount account = requireAccount(user);
        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(request.currentPassword(), account.getPasswordHash())) {
            throw new IllegalStateException("当前密码不正确");
        }
        account.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userAccountRepository.save(account);
        log.info("用户密码已修改：userId={}", account.getId());
    }

    private UserAccount requireAccount(CurrentUser user) {
        return userAccountRepository.findById(user.id())
                .orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    private UserProfileResponse toResponse(UserAccount account) {
        return new UserProfileResponse(account.getEmail(), account.getDisplayName(),
                account.getCreatedAt(), account.getUpdatedAt(), boardRepository.countByOwnerId(account.getId()));
    }
}
