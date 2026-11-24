package com.picturecreate.doccanvas.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picturecreate.doccanvas.api.dto.AiTaskDtos.AiTaskResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.AuthResponse;
import com.picturecreate.doccanvas.api.dto.AuthDtos.LoginRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.RegisterRequest;
import com.picturecreate.doccanvas.api.dto.AuthDtos.UserInfo;
import com.picturecreate.doccanvas.api.dto.ShareDtos.ShareLinkResponse;
import com.picturecreate.doccanvas.api.dto.ShareDtos.SharedBoardResponse;
import com.picturecreate.doccanvas.api.dto.UserDtos.UserProfileResponse;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.AgentMessage;
import com.picturecreate.doccanvas.domain.AgentMessageRole;
import com.picturecreate.doccanvas.domain.AgentSession;
import com.picturecreate.doccanvas.domain.AiTask;
import com.picturecreate.doccanvas.domain.AiTaskType;
import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.BoardSnapshot;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.domain.SourceFile;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.AiTaskService;
import com.picturecreate.doccanvas.service.AssetService;
import com.picturecreate.doccanvas.service.AuthService;
import com.picturecreate.doccanvas.service.BoardEdgeService;
import com.picturecreate.doccanvas.service.BoardLayoutService;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.BoardSnapshotService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.FileService;
import com.picturecreate.doccanvas.service.QuotaService;
import com.picturecreate.doccanvas.service.QuotaService.UsageSnapshot;
import com.picturecreate.doccanvas.service.ShapeEditService;
import com.picturecreate.doccanvas.service.ShareService;
import com.picturecreate.doccanvas.service.UserProfileService;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptedNode;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptionResult;
import com.picturecreate.doccanvas.service.ai.ScopeGuard;
import com.picturecreate.doccanvas.service.ai.agent.AgentAssistService;
import com.picturecreate.doccanvas.service.ai.agent.AgentAssistService.AssistOutcome;
import com.picturecreate.doccanvas.service.ai.agent.AgentSessionService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

/**
 * 成功响应契约测试：每个控制器至少走一遍 MockMvc，断言 code/message/data 包装。
 * 同时覆盖 204 无响应体和二进制下载两类例外，防止前端解包时拿到 undefined。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("成功响应包装")
class ApiEnvelopeTest {

    private static final CurrentUser USER = new CurrentUser(UUID.randomUUID(), "alice@test.com", "Alice");
    private static final AppProperties PROPERTIES = new AppProperties(null,
            new AppProperties.Ai(null, null, null, null, null, null, new AppProperties.Layout(100)), null);

    @Mock
    private BoardService boardService;
    @Mock
    private BoardLayoutService boardLayoutService;
    @Mock
    private CurrentUserService currentUserService;
    @Mock
    private ShapeRepository shapeRepository;
    @Mock
    private ShapeEditService shapeEditService;
    @Mock
    private BoardEdgeService boardEdgeService;
    @Mock
    private AuthService authService;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private FileService fileService;
    @Mock
    private AssetService assetService;
    @Mock
    private AgentAssistService agentAssistService;
    @Mock
    private ScopeGuard scopeGuard;
    @Mock
    private AiTaskService aiTaskService;
    @Mock
    private AgentSessionService agentSessionService;
    @Mock
    private ShareService shareService;
    @Mock
    private BoardSnapshotService boardSnapshotService;
    @Mock
    private QuotaService quotaService;

    private final UUID boardId = UUID.randomUUID();
    private final UUID shapeId = UUID.randomUUID();
    private final UUID edgeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        given(currentUserService.resolve(any())).willReturn(USER);
    }

    @Test
    @DisplayName("画布接口：列表、创建、详情、重命名、切换布局都包在 data 中")
    void boardEndpoints() throws Exception {
        Board board = new Board(USER.id(), "画布");
        given(boardService.list(any(), anyInt(), anyInt(), nullable(String.class)))
                .willReturn(new PageImpl<>(List.of(board), PageRequest.of(0, 9), 1));
        given(boardService.create(any(), anyString())).willReturn(board);
        given(boardService.getOwned(any(), any())).willReturn(board);
        given(boardService.rename(any(), any(), anyString())).willReturn(board);
        given(boardLayoutService.updateLayoutStyle(any(), any(), anyString())).willReturn(board);

        MockMvc mvc = mvc(new BoardController(boardService, boardLayoutService, currentUserService, PROPERTIES));

        mvc.perform(get("/api/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].title").value("画布"));

        mvc.perform(post("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"新画布\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("画布"));

        mvc.perform(get("/api/boards/{boardId}", boardId))
                .andExpect(jsonPath("$.data.title").value("画布"));

        mvc.perform(patch("/api/boards/{boardId}", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"改名\"}"))
                .andExpect(jsonPath("$.data.title").value("画布"));

        mvc.perform(patch("/api/boards/{boardId}/layout", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layoutStyle\":\"LOGIC\"}"))
                .andExpect(jsonPath("$.data.title").value("画布"));
    }

    @Test
    @DisplayName("画布删除与整理：保持 204，不返回 JSON 包装")
    void boardNoContentEndpoints() throws Exception {
        MockMvc mvc = mvc(new BoardController(boardService, boardLayoutService, currentUserService, PROPERTIES));

        mvc.perform(delete("/api/boards/{boardId}", boardId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mvc.perform(post("/api/boards/{boardId}/layout/apply", boardId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("图形节点接口：创建、列表、更新、删除")
    void shapeEndpoints() throws Exception {
        Shape shape = new Shape(boardId, "节点", "ellipse", 0, 0, 0, 120, 48);
        given(shapeEditService.createNode(any(), any(), any(), any())).willReturn(shape);
        given(shapeEditService.updateFields(any(), any(), any(), any(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), any())).willReturn(shape);
        given(shapeRepository.findByBoardIdOrderByCreatedAtAsc(any())).willReturn(List.of(shape));
        given(shapeRepository.findById(any())).willReturn(Optional.of(shape));
        given(shapeRepository.save(any())).willReturn(shape);

        MockMvc mvc = mvc(new ShapeController(shapeRepository, boardService, shapeEditService,
                currentUserService, new ObjectMapper()));

        mvc.perform(post("/api/boards/{boardId}/shapes", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"title\":\"节点\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("节点"));

        mvc.perform(get("/api/boards/{boardId}/shapes", boardId))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("节点"));

        mvc.perform(patch("/api/shapes/{shapeId}", shapeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"节点\",\"x\":10,\"y\":20,\"width\":120,\"height\":48}"))
                .andExpect(jsonPath("$.data.title").value("节点"));

        mvc.perform(delete("/api/shapes/{shapeId}", shapeId))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("结构接口：排序、纳管、折叠")
    void structureEndpoints() throws Exception {
        BoardEdge edge = new BoardEdge(boardId, UUID.randomUUID(), UUID.randomUUID(), "关系");
        Shape shape = new Shape(boardId, "节点", "ellipse", 1, 0, 0, 100, 40);
        given(shapeEditService.reorderChildren(any(), any(), any(), any())).willReturn(List.of(edge));
        given(shapeEditService.adopt(any(), any(), any(), any()))
                .willReturn(new AdoptionResult(List.of(new AdoptedNode("local-1", shape)), List.of(edge)));
        given(shapeEditService.setCollapsed(any(), any(), any(), any(Boolean.class))).willReturn(shape);

        MockMvc mvc = mvc(new StructureController(shapeEditService, currentUserService));

        mvc.perform(patch("/api/boards/{boardId}/structure/order", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + shapeId + "\",\"children\":[{\"edgeId\":\""
                                + edgeId + "\",\"side\":\"RIGHT\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].label").value("关系"));

        mvc.perform(post("/api/boards/{boardId}/structure/adopt", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodes\":[],\"edges\":[]}"))
                .andExpect(jsonPath("$.data.nodes", hasSize(1)))
                .andExpect(jsonPath("$.data.nodes[0].localId").value("local-1"))
                .andExpect(jsonPath("$.data.edges", hasSize(1)));

        mvc.perform(patch("/api/boards/{boardId}/structure/collapse", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shapeId\":\"" + shapeId + "\",\"collapsed\":true}"))
                .andExpect(jsonPath("$.data.title").value("节点"));
    }

    @Test
    @DisplayName("连线列表包装在 data 中")
    void edgeList() throws Exception {
        BoardEdge edge = new BoardEdge(boardId, UUID.randomUUID(), UUID.randomUUID(), "关系");
        given(boardEdgeService.list(any(), any())).willReturn(List.of(edge));

        mvc(new BoardEdgeController(boardEdgeService, currentUserService))
                .perform(get("/api/boards/{boardId}/edges", boardId))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].label").value("关系"));
    }

    @Test
    @DisplayName("认证接口：注册、登录、刷新都解包 data.token")
    void authEndpoints() throws Exception {
        AuthResponse response = new AuthResponse("access", "refresh", new UserInfo("alice@test.com", "Alice"));
        given(authService.register(any(RegisterRequest.class))).willReturn(response);
        given(authService.login(any(LoginRequest.class))).willReturn(response);
        given(authService.refresh(anyString())).willReturn(response);

        MockMvc mvc = mvc(new AuthController(authService));

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@test.com\",\"displayName\":\"Alice\",\"password\":\"secret123\"}"))
                .andExpect(jsonPath("$.data.token").value("access"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@test.com\",\"password\":\"secret123\"}"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh"));

        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh\"}"))
                .andExpect(jsonPath("$.data.token").value("access"));
    }

    @Test
    @DisplayName("用户接口：资料查询、修改、改密 204")
    void userEndpoints() throws Exception {
        UserProfileResponse profile = new UserProfileResponse("alice@test.com", "Alice",
                Instant.now(), Instant.now(), 3);
        given(userProfileService.profile(any())).willReturn(profile);
        given(userProfileService.updateProfile(any(), any())).willReturn(profile);

        MockMvc mvc = mvc(new UserController(userProfileService, currentUserService));

        mvc.perform(get("/api/users/me"))
                .andExpect(jsonPath("$.data.email").value("alice@test.com"))
                .andExpect(jsonPath("$.data.boardCount").value(3));

        mvc.perform(patch("/api/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\",\"email\":\"alice@test.com\"}"))
                .andExpect(jsonPath("$.data.displayName").value("Alice"));

        mvc.perform(patch("/api/users/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"secret123\",\"newPassword\":\"secret456\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("文件接口：列表、上传、下载、删除")
    void fileEndpoints() throws Exception {
        SourceFile file = new SourceFile(boardId, USER.id(), "files/a.txt", "a.txt", "text/plain", 3);
        given(fileService.list(any(), any())).willReturn(List.of(file));
        given(fileService.upload(any(), any(), any())).willReturn(file);
        given(fileService.readContent(any(), any()))
                .willReturn(new StoredObjectContent("text/plain", "abc".getBytes(StandardCharsets.UTF_8)));

        MockMvc mvc = mvc(new FileController(fileService, currentUserService));

        mvc.perform(get("/api/boards/{boardId}/files", boardId))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].filename").value("a.txt"));

        mvc.perform(multipart("/api/boards/{boardId}/files", boardId)
                        .file(new MockMultipartFile("file", "a.txt", "text/plain",
                                "abc".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.filename").value("a.txt"));

        mvc.perform(get("/api/boards/{boardId}/files/{fileId}/content", boardId, UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/plain"))
                .andExpect(content().string("abc"));

        mvc.perform(delete("/api/boards/{boardId}/files/{fileId}", boardId, UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("资产接口：列表、下载、删除")
    void assetEndpoints() throws Exception {
        Asset asset = new Asset(USER.id(), boardId, null, "bucket", "assets/a.png", "image/png", 3, 10, 20);
        given(assetService.list(any(), any())).willReturn(List.of(asset));
        given(assetService.readContent(any(), any(), any()))
                .willReturn(new StoredObjectContent("image/png", new byte[] { 1, 2, 3 }));

        MockMvc mvc = mvc(new AssetController(assetService, currentUserService));

        mvc.perform(get("/api/boards/{boardId}/assets", boardId))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].mimeType").value("image/png"));

        mvc.perform(get("/api/boards/{boardId}/assets/{assetId}/content", boardId, UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(new byte[] { 1, 2, 3 }));

        mvc.perform(delete("/api/boards/{boardId}/assets/{assetId}", boardId, UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("AI 助手：越界拒绝与正常回复都包在 data 中")
    void assistantEndpoints() throws Exception {
        given(scopeGuard.refuseReason("越界")).willReturn("只能处理画布相关问题");
        given(scopeGuard.refuseReason("帮我画一个分支")).willReturn(null);
        given(agentAssistService.assist(any(), any(), anyString(), any(), any()))
                .willReturn(new AssistOutcome("已生成回答", null, UUID.randomUUID()));

        MockMvc mvc = mvc(new AiAssistantController(agentAssistService, currentUserService, scopeGuard));

        mvc.perform(post("/api/boards/{boardId}/assist", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"越界\"}"))
                .andExpect(jsonPath("$.data.kind").value("reply"))
                .andExpect(jsonPath("$.data.reply").value("只能处理画布相关问题"));

        mvc.perform(post("/api/boards/{boardId}/assist", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"帮我画一个分支\"}"))
                .andExpect(jsonPath("$.data.kind").value("reply"))
                .andExpect(jsonPath("$.data.reply").value("已生成回答"));
    }

    @Test
    @DisplayName("AI 任务：列表与中断")
    void taskEndpoints() throws Exception {
        AiTaskResponse task = AiTaskResponse.from(
                new AiTask(boardId, USER.id(), AiTaskType.GENERATE_MINDMAP, "画思维导图"), List.of());
        given(aiTaskService.list(any(), any())).willReturn(List.of(task));
        given(aiTaskService.abort(any(), any())).willReturn(task);

        MockMvc mvc = mvc(new AiTaskController(aiTaskService, currentUserService));

        mvc.perform(get("/api/boards/{boardId}/ai-tasks", boardId))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].prompt").value("画思维导图"));

        mvc.perform(post("/api/ai-tasks/{taskId}/abort", UUID.randomUUID()))
                .andExpect(jsonPath("$.data.prompt").value("画思维导图"));
    }

    @Test
    @DisplayName("Agent 会话：列表、消息、删除")
    void agentSessionEndpoints() throws Exception {
        AgentSession session = new AgentSession(USER.id(), boardId, "会话");
        AgentMessage message = new AgentMessage(session.getId(), USER.id(), boardId,
                AgentMessageRole.USER, "你好", null, null, null, null);
        given(agentSessionService.list(any(), any())).willReturn(List.of(session));
        given(agentSessionService.messages(any(), any(), any())).willReturn(List.of(message));

        MockMvc mvc = mvc(new AgentSessionController(agentSessionService, currentUserService));

        mvc.perform(get("/api/boards/{boardId}/agent/sessions", boardId))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("会话"));

        mvc.perform(get("/api/boards/{boardId}/agent/sessions/{sessionId}/messages", boardId, UUID.randomUUID()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].content").value("你好"));

        mvc.perform(delete("/api/boards/{boardId}/agent/sessions/{sessionId}", boardId, UUID.randomUUID()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("分享接口：生成链接与公开读取")
    void shareEndpoints() throws Exception {
        given(shareService.createShareLink(any(), any())).willReturn(new ShareLinkResponse("share-token"));
        given(shareService.readSharedBoard(anyString())).willReturn(new SharedBoardResponse(
                boardId, "分享画布", List.of(), List.of(), "{}", "LOGIC"));

        MockMvc mvc = mvc(new ShareController(shareService, currentUserService));

        mvc.perform(post("/api/boards/{boardId}/share", boardId))
                .andExpect(jsonPath("$.data.token").value("share-token"));

        mvc.perform(get("/api/share/{token}", "share-token"))
                .andExpect(jsonPath("$.data.title").value("分享画布"));
    }

    @Test
    @DisplayName("快照接口：无快照时 data=null，保存后返回快照")
    void snapshotEndpoints() throws Exception {
        BoardSnapshot snapshot = new BoardSnapshot(boardId, "{}", 1);
        given(boardSnapshotService.latest(any(), any())).willReturn(Optional.empty());
        given(boardSnapshotService.save(any(), any(), anyString())).willReturn(snapshot);

        MockMvc mvc = mvc(new SnapshotController(boardSnapshotService, currentUserService));

        mvc.perform(get("/api/boards/{boardId}/snapshot", boardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mvc.perform(post("/api/boards/{boardId}/snapshot", boardId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"snapshotJson\":\"{}\"}"))
                .andExpect(jsonPath("$.data.snapshotJson").value("{}"));
    }

    @Test
    @DisplayName("用量接口包装在 data 中")
    void usageEndpoint() throws Exception {
        given(quotaService.snapshot(any())).willReturn(
                new UsageSnapshot(LocalDate.of(2026, 11, 16), 1, 2, 3, 100, 1000));

        mvc(new UsageController(quotaService, currentUserService))
                .perform(get("/api/usage/me"))
                .andExpect(jsonPath("$.data.taskCount").value(1))
                .andExpect(jsonPath("$.data.dailyTaskLimit").value(100));
    }

    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ErrorHandler())
                .build();
    }
}
