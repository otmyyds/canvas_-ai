package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Shape;

/**
 * 图形节点数据访问层。按画布查图形节点（思维导图/流程图节点）。
 */
public interface ShapeRepository extends JpaRepository<Shape, UUID> {
    // 按画布查图形节点
    List<Shape> findByBoardIdOrderByCreatedAtAsc(UUID boardId);
    // 按画布计数
    long countByBoardId(UUID boardId);
}
