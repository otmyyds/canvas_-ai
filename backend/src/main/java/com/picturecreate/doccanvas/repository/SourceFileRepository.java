package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.FileParseStatus;
import com.picturecreate.doccanvas.domain.SourceFile;

/**
 * 资料文件数据访问层。按画布+owner 查；按解析状态筛选（供 AI 上下文）。
 */
public interface SourceFileRepository extends JpaRepository<SourceFile, UUID> {

    List<SourceFile> findByBoardIdAndOwnerIdOrderByCreatedAtDesc(UUID boardId, UUID ownerId);

    List<SourceFile> findByBoardIdAndOwnerIdAndParseStatus(UUID boardId, UUID ownerId, FileParseStatus parseStatus);

    Optional<SourceFile> findByIdAndOwnerId(UUID id, UUID ownerId);
}
