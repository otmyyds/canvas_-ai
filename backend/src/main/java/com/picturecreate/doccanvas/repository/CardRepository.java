package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.picturecreate.doccanvas.domain.Card;

/**
 * 卡片数据访问层。按画布查卡片（创建时间升序，供 AI 上下文/渲染）。
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findByBoardIdOrderByCreatedAtAsc(UUID boardId);
}
