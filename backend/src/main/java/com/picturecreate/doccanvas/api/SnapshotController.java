package com.picturecreate.doccanvas.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.SnapshotDtos.SnapshotRequest;
import com.picturecreate.doccanvas.api.dto.SnapshotDtos.SnapshotResponse;
import com.picturecreate.doccanvas.service.BoardSnapshotService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/boards/{boardId}/snapshot")
public class SnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SnapshotController.class);

    private final BoardSnapshotService snapshotService;
    private final CurrentUserService currentUserService;

    public SnapshotController(BoardSnapshotService snapshotService, CurrentUserService currentUserService) {
        this.snapshotService = snapshotService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public SnapshotResponse latest(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询最新快照：boardId={}", boardId);
        return snapshotService.latest(boardId, user)
                .map(SnapshotResponse::from)
                .orElse(null);
    }

    @PostMapping
    public SnapshotResponse save(@PathVariable UUID boardId, @RequestBody SnapshotRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("保存快照：boardId={} jsonLength={}", boardId,
                body.snapshotJson() == null ? 0 : body.snapshotJson().length());
        return SnapshotResponse.from(snapshotService.save(boardId, user, body.snapshotJson()));
    }
}
