package com.picturecreate.doccanvas.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.domain.BoardCheckpoint;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.BoardSnapshot;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.AssetRepository;
import com.picturecreate.doccanvas.repository.BoardCheckpointRepository;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;

/**
 * 计划级画布检查点：执行计划前抓取 shapes/edges/assets 全量行与最新 tldraw 快照，
 * 失败或中断时按原始 id 重建。
 *
 * 恢复必须走 JDBC：实体主键是 @GeneratedValue，JPA 对带 id 的对象执行 merge 会重新生成 id，
 * 而前端 tldraw 元素 id 就是 createShapeId(后端 id)，id 变了快照与数据库就对不上。
 */
@Service
public class BoardCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(BoardCheckpointService.class);

    private final BoardCheckpointRepository checkpointRepository;
    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;
    private final AssetRepository assetRepository;
    private final BoardSnapshotRepository snapshotRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public BoardCheckpointService(BoardCheckpointRepository checkpointRepository,
            ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository,
            AssetRepository assetRepository, BoardSnapshotRepository snapshotRepository,
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.checkpointRepository = checkpointRepository;
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.assetRepository = assetRepository;
        this.snapshotRepository = snapshotRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 抓取计划开始前的画布状态；必须在任何步骤执行之前调用 */
    @Transactional
    public void capture(AiTask task) {
        UUID boardId = task.getBoardId();
        List<Shape> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<BoardEdge> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId);
        List<Asset> assets = assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId,
                task.getOwnerId());
        String snapshotJson = snapshotRepository.findFirstByBoardIdOrderByCreatedAtDesc(boardId)
                .map(BoardSnapshot::getSnapshotJson)
                .orElse(null);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("shapes", shapes.stream().map(this::shapeRow).toList());
        state.put("edges", edges.stream().map(this::edgeRow).toList());
        state.put("assets", assets.stream().map(this::assetRow).toList());
        state.put("snapshot", snapshotJson);
        try {
            String json = objectMapper.writeValueAsString(state);
            checkpointRepository.save(new BoardCheckpoint(boardId, task.getId(), json));
        } catch (Exception exception) {
            throw new IllegalStateException("保存画布检查点失败：" + exception.getMessage(), exception);
        }
        log.info("画布检查点已保存：taskId={} boardId={} 节点={} 连线={} 资产={} 快照={}", task.getId(), boardId,
                shapes.size(), edges.size(), assets.size(), snapshotJson == null ? "无" : "有");
    }

    /** 按检查点重建画布；没有检查点返回 false。调用方负责在失败时记录日志 */
    @Transactional
    public boolean restore(AiTask task) {
        Optional<BoardCheckpoint> found = checkpointRepository.findByTaskId(task.getId());
        if (found.isEmpty()) {
            return false;
        }
        JsonNode state;
        try {
            state = objectMapper.readTree(found.get().getStateJson());
        } catch (Exception exception) {
            throw new IllegalStateException("解析画布检查点失败：" + exception.getMessage(), exception);
        }
        UUID boardId = task.getBoardId();
        jdbcTemplate.update("delete from board_edges where board_id = ?", boardId);
        jdbcTemplate.update("delete from shapes where board_id = ?", boardId);
        jdbcTemplate.update("delete from assets where board_id = ?", boardId);
        jdbcTemplate.update("delete from board_snapshots where board_id = ?", boardId);
        int shapes = insertShapes(state.path("shapes"));
        int edges = insertEdges(state.path("edges"));
        int assets = insertAssets(state.path("assets"));
        String snapshotJson = text(state, "snapshot");
        if (snapshotJson != null && !snapshotJson.isBlank()) {
            Instant now = Instant.now();
            jdbcTemplate.update("""
                    insert into board_snapshots (id, board_id, snapshot_json, version, kind, created_at, updated_at)
                    values (?, ?, ?, 0, 'CURRENT', ?, ?)
                    """, UUID.randomUUID(), boardId, snapshotJson, Timestamp.from(now), Timestamp.from(now));
        }
        log.info("画布已按检查点恢复：taskId={} boardId={} 节点={} 连线={} 资产={} 快照={}", task.getId(), boardId,
                shapes, edges, assets, snapshotJson == null ? "无" : "有");
        return true;
    }

    /** 删除检查点；计划成功、失败、中断收尾后都要删除 */
    @Transactional
    public void delete(UUID taskId) {
        checkpointRepository.deleteByTaskId(taskId);
    }

    private Map<String, Object> shapeRow(Shape shape) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", shape.getId().toString());
        row.put("boardId", shape.getBoardId().toString());
        row.put("title", shape.getTitle());
        row.put("shape", shape.getShape());
        row.put("level", shape.getLevel());
        row.put("x", shape.getX());
        row.put("y", shape.getY());
        row.put("width", shape.getWidth());
        row.put("height", shape.getHeight());
        row.put("createdAt", shape.getCreatedAt().toString());
        row.put("updatedAt", shape.getUpdatedAt().toString());
        return row;
    }

    private Map<String, Object> edgeRow(BoardEdge edge) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", edge.getId().toString());
        row.put("boardId", edge.getBoardId().toString());
        row.put("fromShapeId", edge.getFromShapeId().toString());
        row.put("toShapeId", edge.getToShapeId().toString());
        row.put("label", edge.getLabel());
        row.put("style", edge.getStyle());
        row.put("color", edge.getColor());
        row.put("weight", edge.getWeight());
        row.put("createdAt", edge.getCreatedAt().toString());
        row.put("updatedAt", edge.getUpdatedAt().toString());
        return row;
    }

    private Map<String, Object> assetRow(Asset asset) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", asset.getId().toString());
        row.put("ownerId", asset.getOwnerId().toString());
        row.put("boardId", asset.getBoardId().toString());
        row.put("sourceTaskId", asset.getSourceTaskId() == null ? null : asset.getSourceTaskId().toString());
        row.put("storageBucket", asset.getStorageBucket());
        row.put("storageKey", asset.getStorageKey());
        row.put("mimeType", asset.getMimeType());
        row.put("size", asset.getSize());
        row.put("width", asset.getWidth());
        row.put("height", asset.getHeight());
        row.put("checksum", asset.getChecksum());
        row.put("createdAt", asset.getCreatedAt().toString());
        row.put("updatedAt", asset.getUpdatedAt().toString());
        return row;
    }

    private int insertShapes(JsonNode rows) {
        int count = 0;
        for (JsonNode row : rows) {
            jdbcTemplate.update("""
                    insert into shapes (id, board_id, title, shape, level, x, y, width, height, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, uuid(row, "id"), uuid(row, "boardId"), text(row, "title"), text(row, "shape"),
                    row.path("level").asInt(), row.path("x").asDouble(), row.path("y").asDouble(),
                    row.path("width").asDouble(), row.path("height").asDouble(),
                    timestamp(row, "createdAt"), timestamp(row, "updatedAt"));
            count++;
        }
        return count;
    }

    private int insertEdges(JsonNode rows) {
        int count = 0;
        for (JsonNode row : rows) {
            jdbcTemplate.update("""
                    insert into board_edges (id, board_id, from_shape_id, to_shape_id, label, style, color, weight,
                                             created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, uuid(row, "id"), uuid(row, "boardId"), uuid(row, "fromShapeId"), uuid(row, "toShapeId"),
                    text(row, "label"), text(row, "style"), text(row, "color"), row.path("weight").asInt(),
                    timestamp(row, "createdAt"), timestamp(row, "updatedAt"));
            count++;
        }
        return count;
    }

    private int insertAssets(JsonNode rows) {
        int count = 0;
        for (JsonNode row : rows) {
            jdbcTemplate.update("""
                    insert into assets (id, owner_id, board_id, source_task_id, storage_bucket, storage_key,
                                        mime_type, size, width, height, checksum, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, uuid(row, "id"), uuid(row, "ownerId"), uuid(row, "boardId"), uuid(row, "sourceTaskId"),
                    text(row, "storageBucket"), text(row, "storageKey"), text(row, "mimeType"),
                    row.path("size").asLong(), integer(row, "width"), integer(row, "height"),
                    text(row, "checksum"), timestamp(row, "createdAt"), timestamp(row, "updatedAt"));
            count++;
        }
        return count;
    }

    private UUID uuid(JsonNode row, String field) {
        String value = text(row, field);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer integer(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private Timestamp timestamp(JsonNode row, String field) {
        String value = text(row, field);
        return value == null || value.isBlank() ? null : Timestamp.from(Instant.parse(value));
    }
}
