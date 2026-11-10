package com.picturecreate.doccanvas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.domain.BoardSnapshot;

/**
 * 画布快照数据访问层。
 * 每张画布只保留一行快照，保存时原地覆盖 JSON 并递增 version，不再追加历史版本。
 */
public interface BoardSnapshotRepository extends JpaRepository<BoardSnapshot, UUID> {

    Optional<BoardSnapshot> findFirstByBoardIdOrderByCreatedAtDesc(UUID boardId);

    Optional<BoardSnapshot> findByBoardId(UUID boardId);

    /**
     * 按画布删除全部快照（清空画布时同步清掉 tldraw 快照，避免元素残留）。
     * 派生删除方法不会自动开事务，必须显式声明 @Transactional，
     * 否则 clearCanvas 里调用会抛 TransactionRequiredException。
     */
    @Transactional
    void deleteByBoardId(UUID boardId);
}
