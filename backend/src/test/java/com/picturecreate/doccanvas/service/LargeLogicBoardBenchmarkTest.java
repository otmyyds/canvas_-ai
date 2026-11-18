package com.picturecreate.doccanvas.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardLayoutStyle;
import com.picturecreate.doccanvas.domain.UserAccount;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.repository.UserAccountRepository;

/**
 * 数据库万节点逻辑图基准。
 *
 * 直接写本地开发库，生成后可以登录 alice@test.com 在前端打开操作。
 * 普通测试默认跳过，避免污染开发库；运行时显式加 -DlargeBoardBenchmark=true。
 *
 * 图结构是 6 叉 BFS 树：根 1，每个节点最多 6 个孩子，按层序填满到 10000 个节点
 * （1 / 6 / 36 / 216 / 1296 / 7776 = 9331 到第 5 层，余下 669 个挂在第 5 层前若干节点下，
 * 最终深度 6）。相比「99 分支 × 100 叶子」的宽而浅星形，这种深窄树在视觉上是正常的分层
 * 逻辑图，不会把 9999 条线全部堆在一列。
 *
 * 一级分支默认 collapsed = true：后端 visibleNodes 过滤折叠后代，前端 hiddenShapeIds 也过滤，
 * 所以布局只排根 + 6 个一级分支（7 个可见节点），前端也只创建 7 个 tldraw 元素，万节点画布
 * 可以秒开；点开某个一级分支时再按需展开它的子树。
 *
 * 需要测「全量 10000 节点布局」时加 -DlargeBoardBenchmark.forceExpand=true 把 collapsed 全部
 * 写回 false，此时布局与前端渲染都会处理全部节点，耗时会明显上升，属于压力测试用途。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "largeBoardBenchmark", matches = "true")
@DisplayName("数据库万节点逻辑图基准")
class LargeLogicBoardBenchmarkTest {

    private static final String OWNER_EMAIL = "alice@test.com";
    /** 节点数可通过 -DlargeBoardBenchmark.nodes=1000 调整，默认 10000 */
    private static final int TOTAL_NODES = Integer.getInteger("largeBoardBenchmark.nodes", 500);
    /** 画布标题带上数量，不同量级的基准画布可以并存对比 */
    private static final String BOARD_TITLE = TOTAL_NODES + "节点逻辑图性能测试";
    /** 每个节点最多 6 个孩子：1/6/36/216/1296/7776 铺满 5 层，余量落到第 6 层 */
    private static final int FANOUT = 6;
    /** true 时展开全部节点跑全量布局基准；默认折叠，保证画布能正常打开 */
    private static final boolean FORCE_EXPAND = Boolean.getBoolean("largeBoardBenchmark.forceExpand");

    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private BoardRepository boardRepository;
    @Autowired
    private ShapeRepository shapeRepository;
    @Autowired
    private BoardEdgeRepository edgeRepository;
    @Autowired
    private BoardLayoutService boardLayoutService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("生成逻辑图并完成全量布局")
    void generateAndLayoutLargeBoard() {
        long start = System.nanoTime();

        UserAccount user = findOrCreateUser();
        CurrentUser currentUser = new CurrentUser(user.getId(), user.getEmail(), user.getDisplayName());
        UUID boardId = prepareBoard(user.getId());
        long prepared = System.nanoTime();

        int[][] structure = buildStructure();
        int[] parent = structure[0];
        int[] depth = structure[1];
        Timestamp now = Timestamp.from(Instant.now());
        List<UUID> nodeIds = createNodes(boardId, depth, now);
        long nodesWritten = System.nanoTime();

        createEdges(boardId, nodeIds, parent, now);
        long edgesWritten = System.nanoTime();

        boardLayoutService.applyLayout(boardId, currentUser);
        long layoutDone = System.nanoTime();

        assertThat(shapeRepository.countByBoardId(boardId)).isEqualTo(TOTAL_NODES);
        assertThat(edgeRepository.findByBoardIdOrderBySortOrderAscCreatedAtAsc(boardId))
                .hasSize(TOTAL_NODES - 1);

        // 抽查落库后的坐标，确认不是只写了数量、布局结果没有提交
        List<Map<String, Object>> sample = jdbcTemplate.queryForList(
                "select title, level, x, y, collapsed from shapes where board_id = ? order by level, title limit 5",
                boardId);
        assertThat(sample).isNotEmpty();
        sample.forEach(row -> {
            assertThat(((Number) row.get("x")).doubleValue()).isFinite();
            assertThat(((Number) row.get("y")).doubleValue()).isFinite();
        });

        // 可见节点数：折叠时只有根 + 一级分支，展开时是全部节点
        long visibleCount = countVisible(parent, depth);

        // 第二次布局是热态，用于和首次「建图 + 布局」的总耗时做对比
        long secondStart = System.nanoTime();
        boardLayoutService.applyLayout(boardId, currentUser);
        long secondDone = System.nanoTime();

        // 让基准画布排在 alice 工作台最前面
        jdbcTemplate.update("update boards set updated_at = ? where id = ?",
                Timestamp.from(Instant.now()), boardId);

        System.out.println("========== 数据库万节点逻辑图基准 ==========");
        System.out.printf("用户：%s (%s)%n", user.getEmail(), user.getId());
        System.out.printf("画布：%s (%s)%n", BOARD_TITLE, boardId);
        System.out.printf("结构：%d 叉 BFS 树，最大深度 %d，%s%n", FANOUT, maxDepth(depth),
                FORCE_EXPAND ? "全部展开（压力测试）" : "一级分支折叠（可正常打开）");
        System.out.printf("节点：%d，连线：%d，可见节点：%d%n", TOTAL_NODES, TOTAL_NODES - 1, visibleCount);
        System.out.printf("准备画布：%d ms%n", millis(prepared - start));
        System.out.printf("写入节点：%d ms%n", millis(nodesWritten - prepared));
        System.out.printf("写入连线：%d ms%n", millis(edgesWritten - nodesWritten));
        System.out.printf("全量布局并落库：%d ms%n", millis(layoutDone - edgesWritten));
        System.out.printf("从开始创建到布局完成：%d ms%n", millis(layoutDone - start));
        System.out.printf("二次全量重排（热态）：%d ms%n", millis(secondDone - secondStart));
        System.out.println("==========================================");
    }

    /**
     * 构建结构：返回 [parent[], depth[]]，parent[0] = -1。
     * 按层序给每个节点分配最多 FANOUT 个孩子，直到凑满 TOTAL_NODES，
     * 因此天然是 BFS 树：同父的孩子在数组里连续，深度逐层加一。
     */
    private int[][] buildStructure() {
        int[] parent = new int[TOTAL_NODES];
        int[] depth = new int[TOTAL_NODES];
        parent[0] = -1;
        int cursor = 1;
        for (int p = 0; p < TOTAL_NODES && cursor < TOTAL_NODES; p++) {
            for (int child = 0; child < FANOUT && cursor < TOTAL_NODES; child++) {
                parent[cursor] = p;
                depth[cursor] = depth[p] + 1;
                cursor++;
            }
        }
        return new int[][] { parent, depth };
    }

    /** alice 不存在时创建，密码与开发测试账号一致，方便前端直接登录 */
    private UserAccount findOrCreateUser() {
        return userAccountRepository.findByEmail(OWNER_EMAIL).orElseGet(() -> {
            UserAccount user = new UserAccount(OWNER_EMAIL, "爱丽丝");
            user.setPasswordHash(new BCryptPasswordEncoder().encode("secret123"));
            return userAccountRepository.save(user);
        });
    }

    /** 删除上一次基准画布后新建，保证每次都是干净的 boardId，布局缓存不会命中旧结构 */
    private UUID prepareBoard(UUID ownerId) {
        List<Board> oldBoards = boardRepository.findAll().stream()
                .filter(board -> ownerId.equals(board.getOwnerId()) && BOARD_TITLE.equals(board.getTitle()))
                .toList();
        boardRepository.deleteAll(oldBoards);

        Board board = new Board(ownerId, BOARD_TITLE);
        board.changeLayoutStyle(BoardLayoutStyle.LOGIC);
        return boardRepository.save(board).getId();
    }

    /**
     * 直接批量写 shapes，避免 10000 次单条 JPA save 把创建耗时淹没在 ORM 开销里。
     * 一级分支（depth == 1）默认折叠，让布局和前端渲染都只处理根 + 6 个分支。
     */
    private List<UUID> createNodes(UUID boardId, int[] depth, Timestamp now) {
        String sql = """
                insert into shapes
                    (id, board_id, title, shape, level, x, y, width, height, visual_json, collapsed, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?, ?)
                """;
        List<UUID> nodeIds = new ArrayList<>(TOTAL_NODES);
        List<Object[]> batch = new ArrayList<>(TOTAL_NODES);
        for (int number = 1; number <= TOTAL_NODES; number++) {
            UUID id = UUID.randomUUID();
            nodeIds.add(id);
            boolean collapsed = !FORCE_EXPAND && depth[number - 1] == 1;
            batch.add(new Object[] { id, boardId, Integer.toString(number), "ellipse", depth[number - 1],
                    0.0, 0.0, 160.0, 60.0, collapsed, now, now });
        }
        jdbcTemplate.batchUpdate(sql, batch);
        return nodeIds;
    }

    /**
     * 连线按 sort_order 固定顺序，布局生成树和前端兄弟顺序都依赖它。
     * parent 数组本身是 BFS 顺序，同父的孩子连续，所以从 1 到 N 递增写 sort_order 即可。
     */
    private void createEdges(UUID boardId, List<UUID> nodeIds, int[] parent, Timestamp now) {
        String sql = """
                insert into board_edges
                    (id, board_id, from_shape_id, to_shape_id, label, style, color, weight, sort_order, side, created_at, updated_at)
                values (?, ?, ?, ?, null, null, null, 2, ?, null, ?, ?)
                """;
        List<Object[]> batch = new ArrayList<>(TOTAL_NODES - 1);
        int sortOrder = 1;
        for (int i = 1; i < TOTAL_NODES; i++) {
            batch.add(edge(boardId, nodeIds.get(parent[i]), nodeIds.get(i), sortOrder++, now));
        }
        jdbcTemplate.batchUpdate(sql, batch);
    }

    private Object[] edge(UUID boardId, UUID from, UUID to, int sortOrder, Timestamp now) {
        return new Object[] { UUID.randomUUID(), boardId, from, to, sortOrder, now, now };
    }

    private static int maxDepth(int[] depth) {
        int max = 0;
        for (int value : depth) {
            max = Math.max(max, value);
        }
        return max;
    }

    /**
     * 与后端 visibleNodes、前端 hiddenShapeIds 同口径：折叠节点的全部后代不可见。
     * BFS 生成保证 parent[i] < i，所以一遍扫描就能把隐藏状态沿父链传播下去。
     */
    /**
     * 与后端 visibleNodes、前端 hiddenShapeIds 同口径：折叠节点自身仍然可见，只隐藏它的全部后代。
     * BFS 生成保证 parent[i] < i，所以一遍扫描就能把隐藏状态沿父链传播下去。
     */
    private static long countVisible(int[] parent, int[] depth) {
        boolean[] hidden = new boolean[TOTAL_NODES];
        for (int i = 0; i < TOTAL_NODES; i++) {
            int p = parent[i];
            boolean parentCollapsed = !FORCE_EXPAND && p >= 0 && depth[p] == 1;
            if (parentCollapsed || (p >= 0 && hidden[p])) {
                hidden[i] = true;
            }
        }
        long visible = 0;
        for (boolean value : hidden) {
            if (!value) {
                visible++;
            }
        }
        return visible;
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }
}
