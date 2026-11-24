package com.picturecreate.doccanvas.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.picturecreate.doccanvas.service.NotFoundException;

import jakarta.annotation.PostConstruct;

/**
 * 本地文件存储：轻量版用它替代 RustFS/S3。
 * key 由服务端生成，不接受用户文件名；所有路径必须落在 storage 根目录内。
 */
@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalObjectStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalObjectStorageService.class);
    private static final String BUCKET = "local";

    private final Path root;

    public LocalObjectStorageService(@Value("${app.data-dir:./data}") String dataDir) {
        this.root = Paths.get(dataDir, "storage").toAbsolutePath().normalize();
    }

    @PostConstruct
    void createRoot() {
        try {
            Files.createDirectories(root.resolve("images"));
            Files.createDirectories(root.resolve("covers"));
            log.info("本地文件存储目录已就绪：{}", root);
        } catch (IOException exception) {
            throw new IllegalStateException("创建本地文件存储目录失败：" + root, exception);
        }
    }

    @Override
    public StoredObject put(String key, String contentType, long size, InputStream inputStream) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
        try {
            Files.copy(inputStream, temporary, StandardCopyOption.REPLACE_EXISTING);
            long actualSize = Files.size(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredObject(BUCKET, key, contentType, actualSize);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public StoredObjectContent get(String key) {
        Path target = resolve(key);
        try {
            return new StoredObjectContent(contentTypeOf(target), Files.readAllBytes(target));
        } catch (IOException exception) {
            throw new NotFoundException("本地文件不存在：" + key);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            log.warn("删除本地文件失败：key={} error={}", key, exception.getMessage());
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        deletePrefixExcept(prefix, null, null);
    }

    @Override
    public void deletePrefixExcept(String prefix, String keepKey) {
        deletePrefixExcept(prefix, keepKey, null);
    }

    @Override
    public void deletePrefixExcept(String prefix, String keepKey, Instant olderThan) {
        Path target = resolve(prefix);
        if (!Files.exists(target)) {
            return;
        }
        Path keep = keepKey == null || keepKey.isBlank() ? null : resolve(keepKey);
        try (var paths = Files.walk(target)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> keep == null || !path.equals(keep))
                    .filter(path -> olderThan == null || lastModifiedIsBefore(path, olderThan))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            log.warn("删除本地文件失败：path={} error={}", path, exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            log.warn("遍历本地文件前缀失败：prefix={} error={}", prefix, exception.getMessage());
        }
        // 文件删完再自底向上收空目录，避免 covers/{boardId}/ 这类目录在磁盘上越积越多
        removeEmptyDirectories(target);
    }

    private void removeEmptyDirectories(Path target) {
        try (var paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(Files::isDirectory)
                    .forEach(path -> {
                        try (var entries = Files.list(path)) {
                            if (entries.findAny().isEmpty()) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException exception) {
                            log.warn("清理空目录失败：path={} error={}", path, exception.getMessage());
                        }
                    });
        } catch (IOException exception) {
            log.warn("遍历空目录失败：target={} error={}", target, exception.getMessage());
        }
    }

    private boolean lastModifiedIsBefore(Path path, Instant olderThan) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(olderThan);
        } catch (IOException exception) {
            // 拿不到修改时间就保守跳过：宁可多留一个文件，也不误删正在使用的那张
            return false;
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("文件 key 不能为空");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件路径：" + key);
        }
        return target;
    }

    private String contentTypeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        try {
            String probed = Files.probeContentType(file);
            return probed == null ? "application/octet-stream" : probed;
        } catch (IOException exception) {
            return "application/octet-stream";
        }
    }
}
