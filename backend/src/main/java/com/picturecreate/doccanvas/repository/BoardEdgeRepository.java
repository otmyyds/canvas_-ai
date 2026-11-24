package com.picturecreate.doccanvas.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.picturecreate.doccanvas.domain.BoardEdge;

/**
 * 连线数据访问层。按画布查全部连线；按端点查连线（删除节点时清理用）。
 */
public interface BoardEdgeRepository extends JpaRepository<BoardEdge, UUID> {
    /** 按兄弟顺序取画布全部连线：布局的兄弟顺序依赖这个排序 */
    List<BoardEdge> findByBoardIdOrderBySortOrderAscCreatedAtAsc(UUID boardId);

    List<BoardEdge> findByFromShapeIdOrToShapeId(UUID fromShapeId, UUID toShapeId);

    /** 批量删除与这些节点相连的连线，避免删除子树时逐条加载实体再删除 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from BoardEdge e
            where e.boardId = :boardId
              and (e.fromShapeId in :ids or e.toShapeId in :ids)
            """)
    int deleteByBoardIdAndEndpointIn(@Param("boardId") UUID boardId, @Param("ids") Collection<UUID> ids);

    /** 清空画布时按 board 批量删除连线 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from BoardEdge e where e.boardId = :boardId")
    int deleteByBoardId(@Param("boardId") UUID boardId);
}
