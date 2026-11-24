package com.picturecreate.doccanvas.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.picturecreate.doccanvas.domain.Shape;

/**
 * 图形节点数据访问层。按画布查图形节点（思维导图/流程图节点）。
 */
public interface ShapeRepository extends JpaRepository<Shape, UUID> {
    // 按画布查图形节点
    List<Shape> findByBoardIdOrderByCreatedAtAsc(UUID boardId);
    // 按画布计数
    long countByBoardId(UUID boardId);

    /**
     * 父节点是否有 level > 0 的直接邻居。
     * 新建子节点时用它判断所属分量是否为思维导图，避免为了一个布尔值加载整张图。
     */
    @Query("""
            select count(e) > 0
            from BoardEdge e
            where e.boardId = :boardId
              and (e.fromShapeId = :parentId or e.toShapeId = :parentId)
              and exists (
                  select 1 from Shape s
                  where s.id = case when e.fromShapeId = :parentId
                                    then e.toShapeId else e.fromShapeId end
                    and s.boardId = :boardId
                    and s.level > 0
              )
            """)
    boolean hasLeveledNeighbor(@Param("boardId") UUID boardId, @Param("parentId") UUID parentId);

    /** 父节点是否有任意连线；孤立节点视为待展开的思维导图根 */
    @Query("""
            select count(e) > 0
            from BoardEdge e
            where e.boardId = :boardId
              and (e.fromShapeId = :parentId or e.toShapeId = :parentId)
            """)
    boolean hasAnyNeighbor(@Param("boardId") UUID boardId, @Param("parentId") UUID parentId);

    /**
     * 批量删除节点。删除节点及其子树时逐条 deleteAllById 会先按 id 查一遍再删，
     * 子树越大越慢；这里用一条 IN 删除替代。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Shape s where s.boardId = :boardId and s.id in :ids")
    int deleteByBoardIdAndIdIn(@Param("boardId") UUID boardId, @Param("ids") Collection<UUID> ids);

    /** 清空画布时按 board 批量删除，避免万级节点逐条 delete */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Shape s where s.boardId = :boardId")
    int deleteByBoardId(@Param("boardId") UUID boardId);

}
