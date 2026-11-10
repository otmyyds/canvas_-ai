package com.picturecreate.doccanvas.service.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.picturecreate.doccanvas.domain.BoardLayoutStyle;

/**
 * 单根逻辑树的布局缓存。
 *
 * 缓存只保存 tidy 中间状态和布局坐标，不参与正确性判断：每次布局都会用数据库结构重新校验，
 * 结构不一致的节点自动标脏重算。因此多实例、进程重启、快照恢复后都不会读到错误结果，
 * 最多退化成一次全量计算。
 *
 * 画布与分量都按访问顺序做有界缓存，避免长期运行后按画布数量无限增长。
 */
@Component
public class BoardLayoutCache {

    /** 最多缓存的画布数 */
    private static final int MAX_BOARDS = 32;
    /** 每张画布最多缓存的分量数（按根节点区分） */
    private static final int MAX_COMPONENTS = 32;

    private static final class BoardState {
        final BoardLayoutStyle style;
        final Map<String, TidyLogicLayout.LayoutState> components;

        BoardState(BoardLayoutStyle style) {
            this.style = style;
            this.components = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TidyLogicLayout.LayoutState> eldest) {
                    return size() > MAX_COMPONENTS;
                }
            };
        }
    }

    /** 访问顺序的 LRU：snapshot 命中也会把它移到队尾 */
    private final Map<UUID, BoardState> boards = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, BoardState> eldest) {
            return size() > MAX_BOARDS;
        }
    };

    /** 取某画布、某风格、某根节点对应的缓存；风格不同或没有缓存时返回 null */
    public synchronized TidyLogicLayout.LayoutState snapshot(UUID boardId, BoardLayoutStyle style, String rootId) {
        BoardState state = boards.get(boardId);
        if (state == null || state.style != style) {
            return null;
        }
        return state.components.get(rootId);
    }

    /**
     * 事务提交后再发布缓存。回滚时保留旧缓存，旧缓存与数据库不一致的部分会在下次校验时标脏，
     * 不会产生错误坐标；非事务调用（如测试）直接发布。
     */
    public void publishAfterCommit(UUID boardId, BoardLayoutStyle style, String rootId,
            TidyLogicLayout.LayoutState layout) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    merge(boardId, style, rootId, layout);
                }
            });
        } else {
            merge(boardId, style, rootId, layout);
        }
    }

    private synchronized void merge(UUID boardId, BoardLayoutStyle style, String rootId,
            TidyLogicLayout.LayoutState layout) {
        BoardState state = boards.get(boardId);
        if (state == null || state.style != style) {
            state = new BoardState(style);
            boards.put(boardId, state);
        }
        state.components.put(rootId, layout);
    }
}
