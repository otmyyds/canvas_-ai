package com.picturecreate.doccanvas.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.BoardLayoutStyle;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
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
    private final BoardEdgeRepository edgeRepository;
    private final BoardActionApplier actionApplier;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public BoardLayoutService(BoardRepository boardRepository, ShapeRepository shapeRepository,
            BoardEdgeRepository edgeRepository, BoardActionApplier actionApplier, AppProperties properties,
            ObjectMapper objectMapper) {
        this.boardRepository = boardRepository;
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.actionApplier = actionApplier;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 树形图排列记忆：结构签名 + 每个节点的坐标尺寸 */
    private record RadialMemory(String signature, List<RadialNode> nodes) {
    }

    private record RadialNode(String id, double x, double y, double width, double height) {
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
        BoardLayoutStyle previous = board.getLayoutStyle();
        if (previous == BoardLayoutStyle.RADIAL && target == BoardLayoutStyle.LOGIC) {
            // 进逻辑图前记住树形图排列，再按逻辑图算法重排
            board.rememberRadialLayout(captureRadialLayout(boardId));
            board.changeLayoutStyle(target);
            actionApplier.layoutExistingBoard(boardId);
        } else if (previous == BoardLayoutStyle.LOGIC && target == BoardLayoutStyle.RADIAL) {
            // 先改风格：重排按 board 上的风格选算法，顺序反了会用旧风格排版
            board.changeLayoutStyle(target);
            if (!restoreRadialLayout(board, boardId)) {
                // 结构变了（逻辑图里增删过节点）：丢弃旧排列，按树形图算法重排
                board.rememberRadialLayout(null);
                actionApplier.layoutExistingBoard(boardId);
            }
        } else {
            board.changeLayoutStyle(target);
            actionApplier.layoutExistingBoard(boardId);
        }
        log.info("画布布局已切换：boardId={} style={} 节点={}", boardId, target, nodeCount);
        return board;
    }

    /** 保存当前树形图排列：签名用于判断切回来时结构是否变化 */
    private String captureRadialLayout(UUID boardId) {
        List<RadialNode> nodes = new ArrayList<>();
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            nodes.add(new RadialNode(shape.getId().toString(), shape.getX(), shape.getY(),
                    shape.getWidth(), shape.getHeight()));
        }
        try {
            return objectMapper.writeValueAsString(new RadialMemory(signature(boardId), nodes));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("树形图排列记忆序列化失败", exception);
        }
    }

    /** 结构签名一致时恢复坐标尺寸；否则返回 false 交给树形图算法重排 */
    private boolean restoreRadialLayout(Board board, UUID boardId) {
        String json = board.getRadialLayoutJson();
        if (json == null || json.isBlank()) {
            return false;
        }
        RadialMemory memory;
        try {
            memory = objectMapper.readValue(json, RadialMemory.class);
        } catch (JsonProcessingException exception) {
            log.warn("树形图排列记忆解析失败，按算法重排：boardId={}", boardId);
            return false;
        }
        if (!signature(boardId).equals(memory.signature())) {
            return false;
        }
        Map<String, RadialNode> byId = new LinkedHashMap<>();
        for (RadialNode node : memory.nodes()) {
            byId.put(node.id(), node);
        }
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            RadialNode node = byId.get(shape.getId().toString());
            if (node != null) {
                shape.update(node.x(), node.y());
                shape.resizeTo(node.width(), node.height());
            }
        }
        return true;
    }

    /** 结构签名：排序后的节点 id 集合 + 排序后的有向边集合，顺序无关 */
    private String signature(UUID boardId) {
        List<String> nodeIds = new ArrayList<>();
        for (Shape shape : shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)) {
            nodeIds.add(shape.getId().toString());
        }
        Collections.sort(nodeIds);
        List<String> edgeKeys = new ArrayList<>();
        for (BoardEdge edge : edgeRepository.findByBoardIdOrderBySortOrderAscCreatedAtAsc(boardId)) {
            edgeKeys.add(edge.getFromShapeId() + "#" + edge.getToShapeId());
        }
        Collections.sort(edgeKeys);
        return String.join(",", nodeIds) + "|" + String.join(",", edgeKeys);
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
