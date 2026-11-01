package com.picturecreate.doccanvas.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.picturecreate.doccanvas.domain.AgentStep;

/**
 * Agent 步骤数据访问层。
 * 步骤只追加，按运行内的 seq 升序读取。
 */
public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {

    /** 运行下的所有步骤（按执行顺序） */
    List<AgentStep> findByRunIdOrderBySeqAsc(UUID runId);

    /** 当前运行的最大序号，没有步骤时返回 0 */
    @Query("select coalesce(max(s.seq), 0) from AgentStep s where s.runId = :runId")
    int findMaxSeq(@Param("runId") UUID runId);
}
