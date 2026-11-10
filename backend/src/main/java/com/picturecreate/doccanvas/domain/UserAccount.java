package com.picturecreate.doccanvas.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 用户账户实体，对应 users 表。
 * 认证体系：email 唯一，密码 BCrypt 加密存 passwordHash。
 * JWT 登录后，CurrentUserService 用 userId 查此表。
 */
@Entity
@Table(name = "users")
public class UserAccount extends BaseEntity {

    /** 邮箱（唯一，登录标识） */
    @Column(nullable = false, unique = true)
    private String email;

    /** 显示名称（昵称） */
    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** BCrypt 加密后的密码哈希（注册时生成；演示用户为空=仅本地） */
    @Column(name = "password_hash")
    private String passwordHash;

    protected UserAccount() {
    }

    public UserAccount(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
