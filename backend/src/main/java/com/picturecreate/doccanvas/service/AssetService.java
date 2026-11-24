package com.picturecreate.doccanvas.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.domain.Asset;
import com.picturecreate.doccanvas.repository.AssetRepository;
import com.picturecreate.doccanvas.storage.ObjectStorageService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObject;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

/**
 * 资产服务：AI 生成图片等媒体资产的存储（RustFS）与元数据（assets 表）。
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    private final BoardService boardService;
    private final AssetRepository assetRepository;
    private final ObjectStorageService objectStorageService;
    private final BoardEditingGuard editingGuard;

    public AssetService(BoardService boardService, AssetRepository assetRepository,
            ObjectStorageService objectStorageService, BoardEditingGuard editingGuard) {
        this.boardService = boardService;
        this.assetRepository = assetRepository;
        this.objectStorageService = objectStorageService;
        this.editingGuard = editingGuard;
    }

    /**
     * 存储 AI 生成图片：上传 RustFS + 落 assets 表（供 AiTaskService 生成阶段调用）。
     * 不加 @Transactional：上传是外部网络 I/O（数百毫秒级），包进事务只会白占一个数据库连接；
     * 落库仅一次 insert，repository 的 save 自带事务。上传成功但落库失败会留一个孤儿对象，
     * 比"记录指向一个不存在的对象"（前端 404 死图）好，不额外加补偿。
     */
    public Asset createImageAsset(UUID boardId, UUID ownerId, UUID taskId, byte[] imageBytes) {
        String key = "users/%s/boards/%s/assets/%s.png".formatted(ownerId, boardId, UUID.randomUUID());
        StoredObject stored;
        try {
            stored = objectStorageService.put(key, "image/png", imageBytes.length,
                    new ByteArrayInputStream(imageBytes));
        } catch (IOException exception) {
            throw new IllegalStateException("存储图片失败：" + exception.getMessage(), exception);
        }
        Asset asset = new Asset(ownerId, boardId, taskId, stored.bucket(), stored.key(), "image/png",
                stored.size(), 1024, 1024);
        Asset saved = assetRepository.save(asset);
        log.info("图片资产已落库：assetId={} boardId={} size={}", saved.getId(), boardId, saved.getSize());
        return saved;
    }

    /**
     * 用户上传图片：重新解码并编码为 PNG，去掉原始文件里的元数据和潜在恶意内容。
     * 上传对象存储是外部 I/O，不包进长事务；落库失败最多留一个孤儿文件。
     */
    public Asset uploadImage(UUID boardId, CurrentUser user, MultipartFile file) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的图片");
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("图片不能超过 5MB");
        }
        String declaredType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase(Locale.ROOT);
        boolean declaredImage = declaredType.equals("image/png")
                || declaredType.equals("image/jpeg")
                || declaredType.equals("image/jpg");
        if (!declaredImage) {
            throw new IllegalArgumentException("只支持 PNG 或 JPEG 图片");
        }

        byte[] source;
        try {
            source = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取图片失败", exception);
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(source));
        } catch (IOException exception) {
            throw new IllegalArgumentException("图片格式无法识别", exception);
        }
        if (image == null) {
            throw new IllegalArgumentException("图片格式无法识别");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || (long) width * height > 20_000_000L) {
            throw new IllegalArgumentException("图片尺寸过大，最大支持 4096x4096");
        }

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "png", encoded)) {
                throw new IllegalStateException("图片编码失败");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("图片编码失败", exception);
        }
        byte[] bytes = encoded.toByteArray();
        if (bytes.length > 5L * 1024 * 1024) {
            throw new IllegalArgumentException("图片处理后仍然超过 5MB");
        }

        String key = "images/%s/%s/%s.png".formatted(user.id(), boardId, UUID.randomUUID());
        StoredObject stored;
        try {
            stored = objectStorageService.put(key, "image/png", bytes.length,
                    new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            throw new IllegalStateException("存储图片失败：" + exception.getMessage(), exception);
        }
        Asset asset = new Asset(user.id(), boardId, null, stored.bucket(), stored.key(), "image/png",
                stored.size(), width, height);
        Asset saved = assetRepository.save(asset);
        log.info("用户图片已上传：assetId={} boardId={} size={} dimensions={}x{}",
                saved.getId(), boardId, saved.getSize(), width, height);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Asset> list(UUID boardId, CurrentUser user) {
        boardService.getOwned(boardId, user);
        return assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, user.id());
    }

    @Transactional(readOnly = true)
    public StoredObjectContent readContent(UUID boardId, UUID assetId, CurrentUser user) {
        Asset asset = assetRepository.findByIdAndOwnerId(assetId, user.id())
                .orElseThrow(() -> new NotFoundException("资产不存在"));
        if (!asset.getBoardId().equals(boardId)) {
            throw new NotFoundException("资产不存在");
        }
        return objectStorageService.get(asset.getStorageKey());
    }

    /** 删除资产：校验归属后删 assets 表记录，事务提交后再清 RustFS 对象 */
    @Transactional
    public void delete(UUID boardId, UUID assetId, CurrentUser user) {
        editingGuard.assertEditable(boardId);
        Asset asset = assetRepository.findByIdAndOwnerId(assetId, user.id())
                .orElseThrow(() -> new NotFoundException("资产不存在"));
        if (!asset.getBoardId().equals(boardId)) {
            throw new NotFoundException("资产不存在");
        }
        String storageKey = asset.getStorageKey();
        assetRepository.delete(asset);
        purgeObjects(List.of(storageKey));
        log.info("图片资产已删除：assetId={} boardId={} storageKey={}", assetId, boardId, storageKey);
    }

    /** 删除画布下全部资产（清空画布用）：删 assets 表记录，事务提交后再清 RustFS 对象 */
    @Transactional
    public void deleteAll(UUID boardId, UUID ownerId) {
        List<Asset> assets = assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, ownerId);
        assetRepository.deleteAll(assets);
        purgeObjects(assets.stream().map(Asset::getStorageKey).toList());
        log.info("画布图片资产已清空：boardId={} 资产数={}", boardId, assets.size());
    }

    /**
     * 只删 assets 表记录并返回 storageKey，不做任何外部 I/O。
     * 计划清空步骤用：对象是否清理取决于计划最终成功还是回退，必须延后决定。
     */
    @Transactional
    public List<String> deleteAllRecords(UUID boardId, UUID ownerId) {
        List<Asset> assets = assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, ownerId);
        List<String> storageKeys = assets.stream().map(Asset::getStorageKey).toList();
        assetRepository.deleteAll(assets);
        log.info("画布图片资产记录已删除：boardId={} 资产数={}", boardId, assets.size());
        return storageKeys;
    }

    /** 同步清理对象存储；单个失败只记日志，不影响其它对象与任务状态 */
    public void purgeObjectsNow(List<String> storageKeys) {
        for (String storageKey : storageKeys) {
            try {
                objectStorageService.delete(storageKey);
            } catch (Exception exception) {
                log.warn("清理对象存储失败，已留孤儿对象：key={} error={}", storageKey, exception.getMessage());
            }
        }
    }

    /**
     * 事务提交后再清理对象存储里的文件。
     *
     * 顺序不能反：assets 记录删除并提交才是权威状态。对象删不掉只会留一个孤儿对象（浪费点存储），
     * 而"先删对象、记录删除失败回滚"会留下指向不存在对象的记录，前端就是一张 404 死图。
     * 这里也不能再往外抛异常——事务已经提交，抛出去只会让调用方误判成删除失败。
     */
    private void purgeObjects(List<String> storageKeys) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String storageKey : storageKeys) {
                    try {
                        objectStorageService.delete(storageKey);
                    } catch (Exception exception) {
                        log.warn("清理对象存储失败，已留孤儿对象：key={} error={}", storageKey, exception.getMessage());
                    }
                }
            }
        });
    }
}
