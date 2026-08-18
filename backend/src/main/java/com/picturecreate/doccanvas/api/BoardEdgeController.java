package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.BoardEdgeDtos.BoardEdgeRequest;
import com.picturecreate.doccanvas.api.dto.BoardEdgeDtos.BoardEdgeResponse;
import com.picturecreate.doccanvas.service.BoardEdgeService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/boards/{boardId}/edges")
public class BoardEdgeController {

    private static final Logger log = LoggerFactory.getLogger(BoardEdgeController.class);

    private final BoardEdgeService edgeService;
    private final CurrentUserService currentUserService;

    public BoardEdgeController(BoardEdgeService edgeService, CurrentUserService currentUserService) {
        this.edgeService = edgeService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<BoardEdgeResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询连线列表：boardId={}", boardId);
        return edgeService.list(boardId, user).stream().map(BoardEdgeResponse::from).toList();
    }

    @PostMapping
    public BoardEdgeResponse create(@PathVariable UUID boardId, @Valid @RequestBody BoardEdgeRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("创建连线：boardId={} from={} to={} label={}", boardId, body.fromCardId(), body.toCardId(),
                body.label());
        return BoardEdgeResponse.from(edgeService.create(boardId, user, body.fromCardId(), body.toCardId(),
                body.label()));
    }
}
