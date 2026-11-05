package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.BoardEdge;

/**
 * 连线数据访问层。按画布查全部连线；按端点查连线（删除节点时清理用）。
 */
public interface BoardEdgeRepository extends JpaRepository<BoardEdge, UUID> {
    /** 按兄弟顺序取画布全部连线：布局的兄弟顺序依赖这个排序 */
    List<BoardEdge> findByBoardIdOrderBySortOrderAscCreatedAtAsc(UUID boardId);

    List<BoardEdge> findByFromShapeIdOrToShapeId(UUID fromShapeId, UUID toShapeId);
}
