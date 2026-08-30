package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Asset;

/**
 * 资产数据访问层。按画布查资产（AI 生成图片等）。
 */
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    List<Asset> findByBoardIdAndOwnerIdOrderByCreatedAtAsc(UUID boardId, UUID ownerId);

    Optional<Asset> findByIdAndOwnerId(UUID id, UUID ownerId);
}
