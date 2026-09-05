package com.picturecreate.doccanvas.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public AssetService(BoardService boardService, AssetRepository assetRepository,
            ObjectStorageService objectStorageService) {
        this.boardService = boardService;
        this.assetRepository = assetRepository;
        this.objectStorageService = objectStorageService;
    }

    /** 存储 AI 生成图片：上传 RustFS + 落 assets 表（供 AiTaskService 生成阶段调用） */
    @Transactional
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

    /** 删除资产：校验归属后删除 RustFS 对象 + assets 表记录 */
    @Transactional
    public void delete(UUID boardId, UUID assetId, CurrentUser user) {
        Asset asset = assetRepository.findByIdAndOwnerId(assetId, user.id())
                .orElseThrow(() -> new NotFoundException("资产不存在"));
        if (!asset.getBoardId().equals(boardId)) {
            throw new NotFoundException("资产不存在");
        }
        objectStorageService.delete(asset.getStorageKey());
        assetRepository.delete(asset);
        log.info("图片资产已删除：assetId={} boardId={} storageKey={}", assetId, boardId, asset.getStorageKey());
    }

    /** 删除画布下全部资产（清空画布用）：删除 RustFS 对象 + assets 表记录 */
    @Transactional
    public void deleteAll(UUID boardId, UUID ownerId) {
        List<Asset> assets = assetRepository.findByBoardIdAndOwnerIdOrderByCreatedAtAsc(boardId, ownerId);
        for (Asset asset : assets) {
            objectStorageService.delete(asset.getStorageKey());
        }
        assetRepository.deleteAll(assets);
        log.info("画布图片资产已清空：boardId={} 资产数={}", boardId, assets.size());
    }
}
