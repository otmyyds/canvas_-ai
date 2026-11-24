package com.picturecreate.doccanvas.service;

import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.repository.UserAiUsageRepository;
import com.picturecreate.doccanvas.repository.UserAiUsageRepository.UsageRow;
import com.picturecreate.doccanvas.service.ai.agent.AgentMetrics;

/**
 * 每用户每日 AI 配额。
 *
 * 配额是软上限：只阻止新任务和新对话，不中断正在执行的任务。
 * 任务数在创建任务的事务内原子 +1，超限抛异常让整个事务回滚，避免并发创建时超卖；
 * token 用量由 AgentRunRecorder 在运行收尾时写入，写失败只记日志，不影响任务。
 */
@Profile("!lite")
@Service
public class QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);

    private final UserAiUsageRepository usageRepository;
    private final AppProperties properties;
    private final AgentMetrics metrics;
    /** 用量累计必须独立提交：任务事务回滚时不能把已经发生的模型消耗一起回滚 */
    private final TransactionTemplate newTransaction;

    public QuotaService(UserAiUsageRepository usageRepository, AppProperties properties, AgentMetrics metrics,
            PlatformTransactionManager transactionManager) {
        this.usageRepository = usageRepository;
        this.properties = properties;
        this.metrics = metrics;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 当前用户当日用量与上限，供 /api/usage/me 展示 */
    public UsageSnapshot snapshot(CurrentUser user) {
        AppProperties.Quota config = properties.ai().quota();
        UsageRow row = usageRepository.find(user.id(), LocalDate.now()).orElse(UsageRow.EMPTY);
        return new UsageSnapshot(LocalDate.now(), row.taskCount(), row.modelCalls(), row.totalTokens(),
                config.dailyTasksPerUser(), config.dailyTokensPerUser());
    }

    /**
     * 对话/预检前的 token 检查。
     * 任务数上限不在这里拦截：用户仍可以继续对话，只是不能再创建新任务。
     */
    public void assertTokensAvailable(CurrentUser user) {
        UsageSnapshot snapshot = snapshot(user);
        if (overTokenLimit(snapshot.totalTokens(), snapshot.dailyTokenLimit())) {
            metrics.quotaRejected("tokens");
            throw new QuotaExceededException("今日 AI token 用量已达上限（" + snapshot.dailyTokenLimit()
                    + "），请明天再试");
        }
    }

    /**
     * 创建任务前原子预占一个名额。
     * 检查和 +1 是同一条条件更新语句：同一用户的并发创建由数据库行锁串行化，
     * 不会出现「都读到未超限、然后一起加」的超卖。
     */
    public void reserveTask(CurrentUser user) {
        AppProperties.Quota config = properties.ai().quota();
        long taskLimit = config.dailyTasksPerUser() > 0 ? config.dailyTasksPerUser() : Long.MAX_VALUE;
        long tokenLimit = config.dailyTokensPerUser() > 0 ? config.dailyTokensPerUser() : Long.MAX_VALUE;
        LocalDate today = LocalDate.now();
        if (usageRepository.reserveTask(user.id(), today, taskLimit, tokenLimit) > 0) {
            return;
        }
        // 预占失败后再读一次，只用于区分是任务数还是 token 超限
        UsageRow row = usageRepository.find(user.id(), today).orElse(UsageRow.EMPTY);
        if (row.taskCount() >= taskLimit) {
            metrics.quotaRejected("tasks");
            throw new QuotaExceededException("今日 AI 任务数已达上限（" + config.dailyTasksPerUser()
                    + " 个），请明天再试");
        }
        metrics.quotaRejected("tokens");
        throw new QuotaExceededException("今日 AI token 用量已达上限（" + config.dailyTokensPerUser()
                + "），请明天再试");
    }

    /**
     * 累加一次运行的模型调用与 token。
     * 只由顶层运行调用（分支、审查运行会把用量同时记到父运行），避免重复计数。
     */
    public void recordUsage(UUID userId, int modelCalls, int promptTokens, int completionTokens) {
        long totalTokens = (long) promptTokens + completionTokens;
        if (modelCalls <= 0 && totalTokens <= 0) {
            return;
        }
        try {
            newTransaction.executeWithoutResult(
                    status -> usageRepository.addUsage(userId, LocalDate.now(), modelCalls, totalTokens));
        } catch (RuntimeException exception) {
            // 用量统计失败不能反向影响任务本身
            log.warn("写入每日用量失败：userId={} modelCalls={} tokens={} error={}", userId, modelCalls,
                    totalTokens, exception.getMessage());
        }
    }

    /** 0 表示不限制 */
    private boolean overTokenLimit(long totalTokens, long limit) {
        return limit > 0 && totalTokens >= limit;
    }

    /** 当日用量快照；上限为 0 时表示不限制 */
    public record UsageSnapshot(LocalDate date, long taskCount, long modelCalls, long totalTokens,
            long dailyTaskLimit, long dailyTokenLimit) {
    }
}
