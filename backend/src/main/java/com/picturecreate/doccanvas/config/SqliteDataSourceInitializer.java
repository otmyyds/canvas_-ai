package com.picturecreate.doccanvas.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

/**
 * SQLite 数据源启动前处理：
 * - 自动创建数据库文件所在目录和备份目录；
 * - 版本变化时先备份原 SQLite 文件，再交给 Flyway 迁移。
 *
 * 参考 zfile 的 DataSourceBeanPostProcessor，但只处理本地 SQLite，不影响 PostgreSQL。
 */
@Component
public class SqliteDataSourceInitializer implements BeanPostProcessor, PriorityOrdered, EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SqliteDataSourceInitializer.class);
    private static final String SQLITE_DRIVER = "org.sqlite.JDBC";
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof HikariDataSource dataSource && "dataSource".equals(beanName)) {
            prepareSqlite(dataSource);
        }
        return bean;
    }

    private void prepareSqlite(HikariDataSource dataSource) {
        if (!SQLITE_DRIVER.equals(dataSource.getDriverClassName())) {
            return;
        }
        String jdbcUrl = dataSource.getJdbcUrl();
        if (jdbcUrl == null || !jdbcUrl.startsWith(SQLITE_PREFIX)) {
            return;
        }
        String rawPath = jdbcUrl.substring(SQLITE_PREFIX.length());
        int queryIndex = rawPath.indexOf('?');
        if (queryIndex >= 0) {
            rawPath = rawPath.substring(0, queryIndex);
        }
        if (rawPath.isBlank() || rawPath.startsWith(":memory:")) {
            return;
        }

        Path databaseFile = Paths.get(rawPath).toAbsolutePath().normalize();
        Path dataDirectory = databaseFile.getParent();
        if (dataDirectory == null) {
            throw new IllegalStateException("SQLite 数据库路径缺少父目录：" + databaseFile);
        }

        try {
            Files.createDirectories(dataDirectory);
            Path backupDirectory = dataDirectory.resolve("backups");
            Files.createDirectories(backupDirectory);
            backupIfNeeded(databaseFile, backupDirectory);
            log.info("SQLite 数据目录已就绪：database={} dataDir={}", databaseFile, dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("创建 SQLite 数据目录失败：" + dataDirectory, exception);
        }
    }

    /** 同一版本只备份一次；升级到新版本时保留升级前的数据库文件 */
    private void backupIfNeeded(Path databaseFile, Path backupDirectory) throws IOException {
        if (!Files.exists(databaseFile)) {
            return;
        }
        String databaseVersion = environment.getProperty("app.db.version", "1.0.0");
        String safeVersion = databaseVersion == null || databaseVersion.isBlank()
                ? "unknown"
                : databaseVersion.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path backupFile = backupDirectory.resolve("doc-canvas-" + safeVersion + "-backup.db");
        if (Files.exists(backupFile)) {
            return;
        }
        try {
            Files.copy(databaseFile, backupFile, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("SQLite 数据库升级备份完成：{} -> {}（版本 {}，时间 {}）",
                    databaseFile, backupFile, safeVersion, Instant.now());
        } catch (IOException exception) {
            // 备份失败不阻止启动，但必须留下明显日志；用户仍可通过手动备份恢复
            log.error("SQLite 数据库升级备份失败：database={} backup={}", databaseFile, backupFile, exception);
        }
    }

    @Override
    public int getOrder() {
        return PriorityOrdered.HIGHEST_PRECEDENCE;
    }
}
