package com.picturecreate.doccanvas.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.repository.AssetRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;

/**
 * 本地轻量版启动时清理对象存储里的孤儿文件：
 * - 已删除画布的封面、图片和历史对象；
 * - 当前画布下没有被数据库引用的旧封面。
 * 只处理 data/storage 本地目录，不影响开发版 S3/RustFS。
 */
@Component
@Profile("lite")
public class LiteStorageCleanupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LiteStorageCleanupRunner.class);

    private final BoardRepository boardRepository;
    private final AssetRepository assetRepository;
    private final Path storageRoot;

    public LiteStorageCleanupRunner(BoardRepository boardRepository, AssetRepository assetRepository,
            @Value("${app.data-dir:./data}") String dataDir) {
        this.boardRepository = boardRepository;
        this.assetRepository = assetRepository;
        this.storageRoot = Paths.get(dataDir, "storage").toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Files.isDirectory(storageRoot)) {
            return;
        }
        int removed = 0;
        removed += cleanupCovers();
        removed += cleanupImages();
        removed += cleanupLegacyObjects();
        if (removed > 0) {
            log.info("本地存储孤儿文件清理完成：删除 {} 个文件/目录", removed);
        }
    }

    private int cleanupCovers() {
        Path covers = storageRoot.resolve("covers");
        if (!Files.isDirectory(covers)) {
            return 0;
        }
        int removed = 0;
        try (var directories = Files.list(covers)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                UUID boardId = parseUuid(directory.getFileName().toString());
                Board board = boardId == null ? null : boardRepository.findById(boardId).orElse(null);
                if (board == null) {
                    removed += deleteTree(directory);
                    continue;
                }
                Path keep = board.getCoverKey() == null || board.getCoverKey().isBlank()
                        ? null
                        : storageRoot.resolve(board.getCoverKey()).normalize();
                removed += deleteUnreferenced(directory, keep == null ? Set.of() : Set.of(keep));
            }
        } catch (IOException exception) {
            log.warn("清理封面目录失败：{}", exception.getMessage());
        }
        return removed;
    }

    private int cleanupImages() {
        Path images = storageRoot.resolve("images");
        if (!Files.isDirectory(images)) {
            return 0;
        }
        int removed = 0;
        try (var owners = Files.list(images)) {
            for (Path ownerDir : owners.filter(Files::isDirectory).toList()) {
                UUID ownerId = parseUuid(ownerDir.getFileName().toString());
                if (ownerId == null) {
                    removed += deleteTree(ownerDir);
                    continue;
                }
                try (var boards = Files.list(ownerDir)) {
                    for (Path boardDir : boards.filter(Files::isDirectory).toList()) {
                        UUID boardId = parseUuid(boardDir.getFileName().toString());
                        Board board = boardId == null ? null : boardRepository.findById(boardId).orElse(null);
                        if (board == null || !ownerId.equals(board.getOwnerId())) {
                            removed += deleteTree(boardDir);
                            continue;
                        }
                        Set<Path> keep = new HashSet<>();
                        List<Asset> assets = assetRepository
                                .findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, ownerId);
                        for (Asset asset : assets) {
                            keep.add(storageRoot.resolve(asset.getStorageKey()).normalize());
                        }
                        removed += deleteUnreferenced(boardDir, keep);
                    }
                }
            }
        } catch (IOException exception) {
            log.warn("清理图片目录失败：{}", exception.getMessage());
        }
        return removed;
    }

    private int cleanupLegacyObjects() {
        Path users = storageRoot.resolve("users");
        if (!Files.isDirectory(users)) {
            return 0;
        }
        int removed = 0;
        try (var owners = Files.list(users)) {
            for (Path ownerDir : owners.filter(Files::isDirectory).toList()) {
                Path boardsDir = ownerDir.resolve("boards");
                if (!Files.isDirectory(boardsDir)) {
                    continue;
                }
                try (var boards = Files.list(boardsDir)) {
                    for (Path boardDir : boards.filter(Files::isDirectory).toList()) {
                        UUID boardId = parseUuid(boardDir.getFileName().toString());
                        if (boardId == null || boardRepository.findById(boardId).isEmpty()) {
                            removed += deleteTree(boardDir);
                        }
                    }
                }
            }
        } catch (IOException exception) {
            log.warn("清理历史对象目录失败：{}", exception.getMessage());
        }
        return removed;
    }

    /** 删除目录下不在 keep 集合中的文件，然后删掉空目录 */
    private int deleteUnreferenced(Path directory, Set<Path> keep) throws IOException {
        int removed = 0;
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!keep.contains(file.toAbsolutePath().normalize())) {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                }
            }
        }
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
                removed++;
            }
        }
        return removed;
    }

    private int deleteTree(Path path) {
        int removed = 0;
        try (var files = Files.walk(path)) {
            for (Path item : files.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.deleteIfExists(item)) {
                    removed++;
                }
            }
        } catch (IOException exception) {
            log.warn("删除孤儿目录失败：{} error={}", path, exception.getMessage());
        }
        return removed;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
