package com.picturecreate.doccanvas.service;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardLayoutStyle;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier;

/**
 * 画布布局风格：校验、持久化并立即按新风格重排。
 * 与 AI 落库共用画布行锁，避免两边同时重排互相覆盖。
 */
@Service
public class BoardLayoutService {

    private static final Logger log = LoggerFactory.getLogger(BoardLayoutService.class);

    private final BoardRepository boardRepository;
    private final ShapeRepository shapeRepository;
    private final BoardActionApplier actionApplier;
    private final AppProperties properties;

    public BoardLayoutService(BoardRepository boardRepository, ShapeRepository shapeRepository,
            BoardActionApplier actionApplier, AppProperties properties) {
        this.boardRepository = boardRepository;
        this.shapeRepository = shapeRepository;
        this.actionApplier = actionApplier;
        this.properties = properties;
    }

    /** 切换布局风格；节点数超过上限时不允许树形图（径向） */
    @Transactional
    public Board updateLayoutStyle(UUID boardId, CurrentUser user, String layoutStyle) {
        Board board = boardRepository.findByIdAndOwnerIdForUpdate(boardId, user.id())
                .orElseThrow(() -> new NotFoundException("画布不存在"));
        BoardLayoutStyle target = parse(layoutStyle);
        int radialMaxNodes = properties.ai().layout().radialMaxNodes();
        long nodeCount = shapeRepository.countByBoardId(boardId);
        if (target == BoardLayoutStyle.RADIAL && nodeCount > radialMaxNodes) {
            throw new IllegalArgumentException("画布节点数超过 " + radialMaxNodes + "，仅支持逻辑图");
        }
        board.changeLayoutStyle(target);
        actionApplier.layoutExistingBoard(boardId);
        log.info("画布布局已切换：boardId={} style={} 节点={}", boardId, target, nodeCount);
        return board;
    }

    private BoardLayoutStyle parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("布局风格不能为空");
        }
        try {
            return BoardLayoutStyle.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的布局风格：" + value);
        }
    }
}
