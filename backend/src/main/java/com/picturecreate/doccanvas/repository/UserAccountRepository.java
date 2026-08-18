package com.picturecreate.doccanvas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.UserAccount;

/**
 * 用户账户数据访问层。按邮箱查用户（登录/认证用）。
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmail(String email);
}
