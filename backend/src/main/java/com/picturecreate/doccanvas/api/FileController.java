package com.picturecreate.doccanvas.api;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.FileResponse;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.FileService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 文件（SourceFile）接口控制器。
 *
 * 职责：画布资料文件的上传、列表、读取、删除。
 * 文件上传后存到 RustFS（S3 兼容对象存储），DB 记录元数据；
 * 文本类文件（TXT/MD/PDF/DOCX）上传时自动解析，作为 AI 上下文。
 *
 * 接口一览（前缀 /api/boards/{boardId}/files）：
 * - GET    /                → 画布下所有资料
 * - POST   /                → 上传文件（multipart）
 * - GET    /{fileId}/content → 读取文件原始内容（供图片 <img> 直链）
 * - DELETE /{fileId}         → 删除资料（连带向量 chunk）
 */
@RestController
@RequestMapping("/api/boards/{boardId}/files")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileService fileService;
    private final CurrentUserService currentUserService;

    public FileController(FileService fileService, CurrentUserService currentUserService) {
        this.fileService = fileService;
        this.currentUserService = currentUserService;
    }

    /** 画布下所有资料（按创建时间倒序） */
    @GetMapping
    public List<FileResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询资料列表：boardId={}", boardId);
        return fileService.list(boardId, user).stream().map(FileResponse::from).toList();
    }

    /** 上传文件：存 RustFS + 落库元数据 + 触发文本解析（TXT/MD/PDF/DOCX） */
    @PostMapping
    public FileResponse upload(@PathVariable UUID boardId, @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        CurrentUser user = currentUserService.resolve(request);
        log.info("上传资料：boardId={} filename={} size={}", boardId, file.getOriginalFilename(), file.getSize());
        return FileResponse.from(fileService.upload(boardId, user, file));
    }

    /**
     * 读取文件原始内容。
     * 供图片 <img> 直链使用——前端在 URL 后追加 ?token=<jwt> 通过鉴权。
     */
    @GetMapping("/{fileId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID boardId, @PathVariable UUID fileId,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("读取资料内容：boardId={} fileId={}", boardId, fileId);
        StoredObjectContent content = fileService.readContent(fileId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(content.bytes());
    }

    /** 删除资料：删除 RustFS 对象 + DB 记录 + 向量 chunk */
    @DeleteMapping("/{fileId}")
    public void delete(@PathVariable UUID boardId, @PathVariable UUID fileId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除资料：boardId={} fileId={}", boardId, fileId);
        fileService.delete(fileId, user);
    }
}
