package com.picturecreate.doccanvas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.ShareLink;

/**
 * 分享链接数据访问层。按 token 查分享链接（公开只读访问用）。
 */
public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    Optional<ShareLink> findByToken(String token);
}
