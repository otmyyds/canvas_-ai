package com.picturecreate.doccanvas.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL -> SQLite 一次性迁移。
 *
 * 只有设置 app.migrate.pg-url 时才执行；正常 lite 启动不会触发。
 * 迁移基础画布数据：users、boards、shapes、board_edges、board_snapshots。
 * AI/RAG/Agent/分享/反馈表和图片文件不迁移，图片资产需要后续单独处理。
 */
@Component
@Profile("lite")
public class PgToSqliteMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgToSqliteMigrationRunner.class);

    private final DataSource targetDataSource;

    @Value("${app.migrate.pg-url:}")
    private String pgUrl;
    @Value("${app.migrate.pg-user:}")
    private String pgUser;
    @Value("${app.migrate.pg-password:}")
    private String pgPassword;
    @Value("${app.migrate.force:false}")
    private boolean force;

    public PgToSqliteMigrationRunner(DataSource targetDataSource) {
        this.targetDataSource = targetDataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (pgUrl == null || pgUrl.isBlank()) {
            return;
        }
        log.info("开始 PostgreSQL -> SQLite 迁移：source={}", pgUrl);
        try {
            migrate();
            log.info("PostgreSQL -> SQLite 迁移完成");
            System.exit(0);
        } catch (Exception exception) {
            log.error("PostgreSQL -> SQLite 迁移失败", exception);
            System.exit(1);
        }
    }

    private void migrate() throws SQLException {
        try (Connection source = DriverManager.getConnection(pgUrl, pgUser, pgPassword);
                Connection target = targetDataSource.getConnection()) {
            target.setAutoCommit(false);
            try {
                if (count(target, "users") > 0) {
                    if (!force) {
                        throw new IllegalStateException("目标 SQLite 已有数据；如需覆盖请设置 app.migrate.force=true");
                    }
                    clearTarget(target);
                }
                copyUsers(source, target);
                copyBoards(source, target);
                copyShapes(source, target);
                copyEdges(source, target);
                copySnapshots(source, target);
                target.commit();
                verify(source, target);
            } catch (Exception exception) {
                target.rollback();
                throw exception;
            }
        }
    }

    private void copyUsers(Connection source, Connection target) throws SQLException {
        String select = """
                select id, email, display_name, password_hash, created_at, updated_at
                from users
                order by created_at
                """;
        String insert = """
                insert into users (id, email, display_name, password_hash, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement query = source.prepareStatement(select);
                ResultSet rows = query.executeQuery();
                PreparedStatement write = target.prepareStatement(insert)) {
            int count = 0;
            while (rows.next()) {
                write.setString(1, rows.getString("id"));
                write.setString(2, rows.getString("email"));
                write.setString(3, rows.getString("display_name"));
                write.setString(4, rows.getString("password_hash"));
                write.setTimestamp(5, rows.getTimestamp("created_at"));
                write.setTimestamp(6, rows.getTimestamp("updated_at"));
                write.addBatch();
                if (++count % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
            log.info("迁移 users：{} 行", count);
        }
    }

    private void copyBoards(Connection source, Connection target) throws SQLException {
        String select = """
                select id, owner_id, title, visibility, layout_style, radial_layout_json,
                       created_at, updated_at
                from boards
                order by created_at
                """;
        String insert = """
                insert into boards (id, owner_id, title, visibility, layout_style,
                                    radial_layout_json, cover_key, cover_mime, cover_version,
                                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, null, null, 0, ?, ?)
                """;
        try (PreparedStatement query = source.prepareStatement(select);
                ResultSet rows = query.executeQuery();
                PreparedStatement write = target.prepareStatement(insert)) {
            int count = 0;
            while (rows.next()) {
                write.setString(1, rows.getString("id"));
                write.setString(2, rows.getString("owner_id"));
                write.setString(3, rows.getString("title"));
                write.setString(4, rows.getString("visibility"));
                write.setString(5, rows.getString("layout_style"));
                write.setString(6, rows.getString("radial_layout_json"));
                write.setTimestamp(7, rows.getTimestamp("created_at"));
                write.setTimestamp(8, rows.getTimestamp("updated_at"));
                write.addBatch();
                if (++count % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
            log.info("迁移 boards：{} 行；封面文件未迁移，封面字段已置空", count);
        }
    }

    private void copyShapes(Connection source, Connection target) throws SQLException {
        String select = """
                select id, board_id, title, shape, level, x, y, width, height,
                       visual_json, collapsed, created_at, updated_at
                from shapes
                order by created_at
                """;
        String insert = """
                insert into shapes (id, board_id, title, shape, level, x, y, width, height,
                                    visual_json, collapsed, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement query = source.prepareStatement(select);
                ResultSet rows = query.executeQuery();
                PreparedStatement write = target.prepareStatement(insert)) {
            int count = 0;
            while (rows.next()) {
                write.setString(1, rows.getString("id"));
                write.setString(2, rows.getString("board_id"));
                write.setString(3, rows.getString("title"));
                write.setString(4, rows.getString("shape"));
                write.setInt(5, rows.getInt("level"));
                write.setDouble(6, rows.getDouble("x"));
                write.setDouble(7, rows.getDouble("y"));
                write.setDouble(8, rows.getDouble("width"));
                write.setDouble(9, rows.getDouble("height"));
                write.setString(10, rows.getString("visual_json"));
                write.setInt(11, rows.getBoolean("collapsed") ? 1 : 0);
                write.setTimestamp(12, rows.getTimestamp("created_at"));
                write.setTimestamp(13, rows.getTimestamp("updated_at"));
                write.addBatch();
                if (++count % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
            log.info("迁移 shapes：{} 行", count);
        }
    }

    private void copyEdges(Connection source, Connection target) throws SQLException {
        String select = """
                select id, board_id, from_shape_id, to_shape_id, label, style, color,
                       weight, sort_order, side, created_at, updated_at
                from board_edges
                order by created_at
                """;
        String insert = """
                insert into board_edges (id, board_id, from_shape_id, to_shape_id, label, style,
                                         color, weight, sort_order, side, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement query = source.prepareStatement(select);
                ResultSet rows = query.executeQuery();
                PreparedStatement write = target.prepareStatement(insert)) {
            int count = 0;
            while (rows.next()) {
                write.setString(1, rows.getString("id"));
                write.setString(2, rows.getString("board_id"));
                write.setString(3, rows.getString("from_shape_id"));
                write.setString(4, rows.getString("to_shape_id"));
                write.setString(5, rows.getString("label"));
                write.setString(6, rows.getString("style"));
                write.setString(7, rows.getString("color"));
                write.setInt(8, rows.getInt("weight"));
                write.setInt(9, rows.getInt("sort_order"));
                write.setString(10, rows.getString("side"));
                write.setTimestamp(11, rows.getTimestamp("created_at"));
                write.setTimestamp(12, rows.getTimestamp("updated_at"));
                write.addBatch();
                if (++count % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
            log.info("迁移 board_edges：{} 行", count);
        }
    }

    private void copySnapshots(Connection source, Connection target) throws SQLException {
        // 源库可能保留多版本快照，lite 表每个画布只允许一行，取每个画布最新一条
        String select = """
                select distinct on (board_id) id, board_id, snapshot_json, version, created_at, updated_at
                from board_snapshots
                order by board_id, created_at desc, id desc
                """;
        String insert = """
                insert into board_snapshots (id, board_id, snapshot_json, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement query = source.prepareStatement(select);
                ResultSet rows = query.executeQuery();
                PreparedStatement write = target.prepareStatement(insert)) {
            int count = 0;
            while (rows.next()) {
                write.setString(1, rows.getString("id"));
                write.setString(2, rows.getString("board_id"));
                write.setString(3, rows.getString("snapshot_json"));
                write.setInt(4, rows.getInt("version"));
                write.setTimestamp(5, rows.getTimestamp("created_at"));
                write.setTimestamp(6, rows.getTimestamp("updated_at"));
                write.addBatch();
                if (++count % 100 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
            log.info("迁移 board_snapshots：{} 行", count);
        }
    }

    private void verify(Connection source, Connection target) throws SQLException {
        for (String table : new String[] { "users", "boards", "shapes", "board_edges", "board_snapshots" }) {
            long sourceCount = count(source, table);
            long targetCount = count(target, table);
            if (sourceCount != targetCount) {
                throw new IllegalStateException("迁移校验失败：" + table + " source=" + sourceCount
                        + " target=" + targetCount);
            }
            log.info("迁移校验：{}={}", table, targetCount);
        }
        long assetCount = count(source, "assets");
        if (assetCount > 0) {
            log.warn("源库有 {} 条 assets 图片记录，本次未迁移图片文件和 assets 行", assetCount);
        }
    }

    private void clearTarget(Connection target) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.executeUpdate("delete from assets");
            statement.executeUpdate("delete from board_snapshots");
            statement.executeUpdate("delete from board_edges");
            statement.executeUpdate("delete from shapes");
            statement.executeUpdate("delete from boards");
            statement.executeUpdate("delete from users");
        }
        log.warn("目标 SQLite 已有数据，已按 force 设置清空");
    }

    private long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from " + table)) {
            result.next();
            return result.getLong(1);
        }
    }
}
