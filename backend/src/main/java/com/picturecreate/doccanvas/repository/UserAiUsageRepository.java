package com.picturecreate.doccanvas.repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 每用户每日 AI 用量的读写。
 *
 * 累加一律用 `insert ... on conflict do update`：同一用户同一天的并发累加由数据库行锁串行化，
 * 不需要应用层加锁，也不会出现读-改-写导致的丢计数。
 */
@Repository
public class UserAiUsageRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserAiUsageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询某用户某天的用量；没有记录说明当天还没用过 */
    public Optional<UsageRow> find(UUID userId, LocalDate usageDate) {
        return jdbcTemplate.query("""
                select task_count, model_calls, total_tokens
                  from user_ai_usage_daily
                 where user_id = ? and usage_date = ?
                """,
                (resultSet, rowNum) -> new UsageRow(resultSet.getLong("task_count"),
                        resultSet.getLong("model_calls"), resultSet.getLong("total_tokens")),
                userId, usageDate).stream().findFirst();
    }

    /**
     * 原子预占一个任务名额：只有任务数与 token 都在上限内才会 +1。
     * 条件写在冲突更新里，检查和累加是同一个语句，并发创建也不会超卖；
     * 返回 0 表示用户今日配额已用尽，调用方据此返回 429。
     */
    public int reserveTask(UUID userId, LocalDate usageDate, long taskLimit, long tokenLimit) {
        return jdbcTemplate.update("""
                insert into user_ai_usage_daily (user_id, usage_date, task_count)
                values (?, ?, 1)
                on conflict (user_id, usage_date) do update
                   set task_count = user_ai_usage_daily.task_count + 1
                 where user_ai_usage_daily.task_count < ?
                   and user_ai_usage_daily.total_tokens < ?
                """, userId, usageDate, taskLimit, tokenLimit);
    }

    /** 模型调用次数与 token 用量累加 */
    public void addUsage(UUID userId, LocalDate usageDate, int modelCalls, long totalTokens) {
        jdbcTemplate.update("""
                insert into user_ai_usage_daily (user_id, usage_date, model_calls, total_tokens)
                values (?, ?, ?, ?)
                on conflict (user_id, usage_date) do update
                   set model_calls = user_ai_usage_daily.model_calls + excluded.model_calls,
                       total_tokens = user_ai_usage_daily.total_tokens + excluded.total_tokens
                """, userId, usageDate, modelCalls, totalTokens);
    }

    /** 当日用量行 */
    public record UsageRow(long taskCount, long modelCalls, long totalTokens) {

        public static final UsageRow EMPTY = new UsageRow(0, 0, 0);
    }
}
