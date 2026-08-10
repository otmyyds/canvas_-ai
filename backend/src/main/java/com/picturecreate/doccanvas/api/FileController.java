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

import com.picturecreate.doccanvas.api.dto.FileResponse;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;
import com.picturecreate.doccanvas.service.FileService;
import com.picturecreate.doccanvas.storage.ObjectStorageService.StoredObjectContent;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/boards/{boardId}/files")
public class FileController {

    private final FileService fileService;
    private final CurrentUserService currentUserService;

    public FileController(FileService fileService, CurrentUserService currentUserService) {
        this.fileService = fileService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<FileResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return fileService.list(boardId, user).stream().map(FileResponse::from).toList();
    }

    @PostMapping
    public FileResponse upload(@PathVariable UUID boardId, @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        CurrentUser user = currentUserService.resolve(request);
        return FileResponse.from(fileService.upload(boardId, user, file));
    }

    @GetMapping("/{fileId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID boardId, @PathVariable UUID fileId,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        StoredObjectContent content = fileService.readContent(fileId, user);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noCache())
                .body(content.bytes());
    }

    @DeleteMapping("/{fileId}")
    public void delete(@PathVariable UUID boardId, @PathVariable UUID fileId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        fileService.delete(fileId, user);
    }
}
