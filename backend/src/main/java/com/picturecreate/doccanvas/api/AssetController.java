package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.AssetDtos.AssetResponse;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.service.AssetService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 资产（AI 生成图片 / 用户上传图片）接口。
 * - GET  /api/boards/{boardId}/assets                  → 画布下所有资产
 * - POST /api/boards/{boardId}/assets                  → 上传图片（multipart）
 * - GET  /api/boards/{boardId}/assets/{assetId}/content → 读取图片本体（供 <img> 直链）
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
    public Result<List<AssetResponse>> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return Result.success(assetService.list(boardId, user).stream().map(AssetResponse::from).toList());
    }

    /** 上传画布图片：服务端重新编码为 PNG 后保存到本地/S3 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AssetResponse> upload(@PathVariable UUID boardId, @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("上传画布图片：boardId={} filename={} size={}", boardId,
                file.getOriginalFilename(), file.getSize());
        return Result.success(AssetResponse.from(assetService.uploadImage(boardId, user, file)));
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

    /**
     * 删除资产：删除 RustFS 对象 + DB 记录。
     *
     * 无返回体必须显式声明 204：Spring 对无返回值且无 @ResponseStatus 的处理器默认回 200 + 空 body，
     * 前端 request() 只在 204 时跳过解析，200 会拿空 body 去调 response.json() 抛 SyntaxError。
     */
    @DeleteMapping("/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID boardId, @PathVariable UUID assetId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除图片资产：boardId={} assetId={}", boardId, assetId);
        assetService.delete(boardId, assetId, user);
    }
}
