package com.picturecreate.doccanvas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskStatus;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.AiTaskRepository;
import com.picturecreate.doccanvas.repository.AssetRepository;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.repository.SourceFileRepository;
import com.picturecreate.doccanvas.repository.UserAccountRepository;
import com.picturecreate.doccanvas.service.ai.AiGenerationService;
import com.picturecreate.doccanvas.service.ai.BoardAction;
import com.picturecreate.doccanvas.service.ai.BoardActionResult;
import com.picturecreate.doccanvas.service.ai.ImageGenerationService;
import com.picturecreate.doccanvas.storage.ObjectStorageService;

/**
 * 事务边界集成测试。
 *
 * 只验证单元测试验不了的东西：一次业务动作里的多条写，要么全提交、要么全回滚。
 * 这些都是项目历史上真实踩过的坑——@Transactional 曾经挂在 protected 且只被类内自调用的方法上，
 * 代理压根不介入，注解一次都没生效：任务收尾时图形写了一半而任务已被标成 FAILED、
 * 清空画布删了三张表就中断导致残留元素在前端刷新后复活、先删对象再删记录导致死图。
 *
 * 依赖本机 Docker：起真实 PostgreSQL(pgvector) 与 Redis 容器（镜像与 infra/docker-compose.yml 一致）。
 * RustFS 与两处模型调用用 mock 替换——本测试断言的是数据库原子性，不是对象存储或模型的行为。
 */
@SpringBootTest(properties = "app.ai.provider=mock")
@Testcontainers
@DisplayName("AI 任务事务边界")
class AiTaskTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void pointToContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.redis.redisson.config", () -> """
                singleServerConfig:
                  address: "redis://%s:%d"
                  database: 0
                """.formatted(REDIS.getHost(), REDIS.getMappedPort(6379)));
    }

    /** 对象存储（RustFS）与两处模型调用：外部依赖，本测试不关心其行为 */
    @MockitoBean
    private ObjectStorageService objectStorageService;
    @MockitoBean
    private AiGenerationService aiGenerationService;
    @MockitoBean
    private ImageGenerationService imageGenerationService;

    /**
     * 快照仓库是 clearCanvas 事务里最后一个动作，让它失败即可验证前面对三张表的删除有没有提前落库。
     * finish() 路径不碰快照，所以这个 mock 不影响其它用例。
     */
    @MockitoBean
    private BoardSnapshotRepository snapshotRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private BoardRepository boardRepository;
    @Autowired
    private ShapeRepository shapeRepository;
    @Autowired
    private BoardEdgeRepository edgeRepository;
    @Autowired
    private AssetRepository assetRepository;
    @Autowired
    private SourceFileRepository sourceFileRepository;
    @Autowired
    private AiTaskRepository taskRepository;
    @Autowired
    private AiTaskService aiTaskService;
    @Autowired
    private AssetService assetService;
    @Autowired
    private FileService fileService;

    private UUID ownerId;
    private UUID boardId;

    /** 每个用例一套独立的用户与画布，避免用例之间互相干扰（users.email 唯一，故加随机后缀） */
    @BeforeEach
    void createBoard() {
        UUID userId = userAccountRepository
                .save(new UserAccount(UUID.randomUUID() + "@transaction-test.local", "事务测试"))
                .getId();
        ownerId = userId;
        boardId = boardRepository.save(new Board(userId, "事务测试画布")).getId();
    }

    @Test
    @DisplayName("清空画布：快照删除失败时，图形与连线的删除一起回滚")
    void clearCanvasRollsBackAllThreeTables() {
        UUID shapeA = shapeRepository.save(new Shape(boardId, "节点A", "ellipse", 0, 0, 0, 200, 96)).getId();
        UUID shapeB = shapeRepository.save(new Shape(boardId, "节点B", "ellipse", 1, 300, 0, 200, 96)).getId();
        edgeRepository.save(new BoardEdge(boardId, shapeA, shapeB, "上级"));

        willThrow(new IllegalStateException("模拟快照删除失败"))
                .given(snapshotRepository).deleteByBoardId(boardId);

        UUID taskId = taskRepository.save(new AiTask(boardId, ownerId, AiTaskType.CLEAR_CANVAS, "清空画布")).getId();
        aiTaskService.executeTask(taskId);

        // 三张表必须一起回滚：残留任何一张，元素都会在前端刷新后复活
        assertThat(shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)).hasSize(2);
        assertThat(edgeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)).hasSize(1);
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(AiTaskStatus.FAILED);
    }

    @Test
    @DisplayName("任务收尾：应用动作中途失败时，已写入的图形一起回滚")
    void finishRollsBackPartiallyAppliedActions() {
        given(aiGenerationService.generateBoardActions(any()))
                .willReturn(new BoardActionResult(List.of(
                        new BoardAction.CreateShape(null, "第一个节点", "ellipse", 0, 140, 140, 200, 96),
                        // 标题超过 shapes.title varchar(255)，第二条写入必然被数据库拒绝
                        new BoardAction.CreateShape(null, "超长标题".repeat(100), "ellipse", 0, 340, 140, 200, 96)),
                        "{\"mode\":\"test\"}"));

        UUID taskId = taskRepository.save(
                new AiTask(boardId, ownerId, AiTaskType.GENERATE_MINDMAP, "画一张思维导图")).getId();
        aiTaskService.executeTask(taskId);

        // 第一个节点不能留下来：否则任务显示失败，画布上却多了半套结构且无法回收
        assertThat(shapeRepository.findByBoardIdOrderByCreatedAtAsc(boardId)).isEmpty();
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus()).isEqualTo(AiTaskStatus.FAILED);
    }

    @Test
    @DisplayName("删除资产：记录先落库删掉，对象存储清理失败不回滚也不抛异常")
    void deleteAllKeepsRecordsDeletedWhenStoragePurgeFails() {
        Asset asset = saveAsset();
        willThrow(new IllegalStateException("模拟对象存储删除失败"))
                .given(objectStorageService).delete(asset.getStorageKey());

        assertThatCode(() -> assetService.deleteAll(boardId, ownerId)).doesNotThrowAnyException();

        // 记录已删，不会留下指向不存在对象的死图；对象删不掉只留一个孤儿对象
        assertThat(assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, ownerId)).isEmpty();
    }

    @Test
    @DisplayName("删除资产：记录提交后才去清对象存储")
    void deleteAllPurgesStorageAfterCommit() {
        Asset asset = saveAsset();

        assetService.deleteAll(boardId, ownerId);

        verify(objectStorageService).delete(asset.getStorageKey());
        assertThat(assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, ownerId)).isEmpty();
    }

    private Asset saveAsset() {
        return assetRepository.save(new Asset(ownerId, boardId, null, "doc-canvas",
                "users/%s/boards/%s/assets/%s.png".formatted(ownerId, boardId, UUID.randomUUID()),
                "image/png", 1024L, 1024, 1024));
    }

    @Test
    @DisplayName("删除资料：对象存储清理失败不回滚也不抛异常，记录保持已删除")
    void deleteFileKeepsRecordDeletedWhenStoragePurgeFails() {
        SourceFile file = saveSourceFile();
        willThrow(new IllegalStateException("模拟对象存储删除失败"))
                .given(objectStorageService).delete(file.getStorageKey());

        assertThatCode(() -> fileService.delete(file.getId(), currentUser())).doesNotThrowAnyException();

        // 曾经是先删对象再删记录：记录删除失败回滚后，对象已没了，前端就是一条 404 死链
        assertThat(sourceFileRepository.findById(file.getId())).isEmpty();
    }

    @Test
    @DisplayName("删除资料：记录提交后才去清对象存储")
    void deleteFilePurgesStorageAfterCommit() {
        SourceFile file = saveSourceFile();

        fileService.delete(file.getId(), currentUser());

        verify(objectStorageService).delete(file.getStorageKey());
        assertThat(sourceFileRepository.findById(file.getId())).isEmpty();
    }

    private CurrentUser currentUser() {
        return new CurrentUser(ownerId, "transaction-test@local", "事务测试");
    }

    private SourceFile saveSourceFile() {
        return sourceFileRepository.save(new SourceFile(boardId, ownerId,
                "users/%s/boards/%s/source-files/%s/notes.md".formatted(ownerId, boardId, UUID.randomUUID()),
                "notes.md", "text/markdown", 512L));
    }
}
