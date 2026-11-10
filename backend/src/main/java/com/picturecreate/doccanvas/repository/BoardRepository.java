package com.picturecreate.doccanvas.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.picturecreate.doccanvas.domain.Board;

import jakarta.persistence.LockModeType;

/**
 * 画布数据访问层。按 owner 查画布列表；findByIdAndOwnerId 校验归属。
 * 带 ForUpdate 的方法对画布行加悲观写锁，用于布局重排与 AI 落库互斥。
 */
public interface BoardRepository extends JpaRepository<Board, UUID> {

    /** 首页分页：按更新时间倒序（排序由调用方传入 Pageable） */
    Page<Board> findByOwnerId(UUID ownerId, Pageable pageable);

    /** 首页搜索：标题模糊匹配，忽略大小写 */
    Page<Board> findByOwnerIdAndTitleContainingIgnoreCase(UUID ownerId, String title, Pageable pageable);

    /** 用户资料页展示的画布数量 */
    long countByOwnerId(UUID ownerId);

    Optional<Board> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Board b where b.id = :id")
    Optional<Board> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Board b where b.id = :id and b.ownerId = :ownerId")
    Optional<Board> findByIdAndOwnerIdForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
