package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Shape;

public interface ShapeRepository extends JpaRepository<Shape, UUID> {

    List<Shape> findByBoardIdOrderByCreatedAtAsc(UUID boardId);
}
