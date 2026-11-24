package com.picturecreate.doccanvas.api;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardResponse;
import com.picturecreate.doccanvas.common.Result;
import com.picturecreate.doccanvas.config.AppProperties;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.service.BoardCoverService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 画布封面接口：
 * - PUT    /api/boards/{boardId}/cover → 上传缩略图
 * - GET    /api/boards/{boardId}/cover → 读取封面
 * - DELETE /api/boards/{boardId}/cover → 删除封面
 */
@RestController
@RequestMapping("/api/boards/{boardId}/cover")
public class BoardCoverController {

    private final BoardCoverService coverService;
    private final CurrentUserService currentUserService;
    private final AppProperties properties;

    public BoardCoverController(BoardCoverService coverService, CurrentUserService currentUserService,
            AppProperties properties) {
        this.coverService = coverService;
        this.currentUserService = currentUserService;
        this.properties = properties;
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<BoardResponse> upload(@PathVariable UUID boardId, @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        Board board = coverService.updateCover(boardId, user, file);
        return Result.success(BoardResponse.from(board, properties.ai().layout().radialMaxNodes()));
    }

    @GetMapping
    public ResponseEntity<byte[]> content(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        StoredObjectContent content = coverService.readCover(boardId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(content.bytes());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        coverService.deleteCover(boardId, user);
    }
}
