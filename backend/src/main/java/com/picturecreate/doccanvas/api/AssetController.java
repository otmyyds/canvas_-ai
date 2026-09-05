package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AssetDtos.AssetResponse;
import com.picturecreate.doccanvas.service.AssetService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 资产（AI 生成图片）接口。
 * - GET /api/boards/{boardId}/assets                  → 画布下所有资产
 * - GET /api/boards/{boardId}/assets/{assetId}/content → 读取图片本体（供 <img> 直链）
 */
@RestController
@RequestMapping("/api/boards/{boardId}/assets")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private final AssetService assetService;
    private final CurrentUserService currentUserService;

    public AssetController(AssetService assetService, CurrentUserService currentUserService) {
        this.assetService = assetService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<AssetResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return assetService.list(boardId, user).stream().map(AssetResponse::from).toList();
    }

    @GetMapping("/{assetId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID boardId, @PathVariable UUID assetId,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        StoredObjectContent content = assetService.readContent(boardId, assetId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(content.bytes());
    }

    /** 删除资产：删除 RustFS 对象 + DB 记录 */
    @DeleteMapping("/{assetId}")
    public void delete(@PathVariable UUID boardId, @PathVariable UUID assetId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除图片资产：boardId={} assetId={}", boardId, assetId);
        assetService.delete(boardId, assetId, user);
    }
}
