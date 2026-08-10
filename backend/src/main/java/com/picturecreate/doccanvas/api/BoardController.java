package com.picturecreate.doccanvas.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardRequest;
import com.picturecreate.doccanvas.api.dto.BoardDtos.BoardResponse;
import com.picturecreate.doccanvas.service.BoardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/boards")
public class BoardController {

    private final BoardService boardService;
    private final CurrentUserService currentUserService;

    public BoardController(BoardService boardService, CurrentUserService currentUserService) {
        this.boardService = boardService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<BoardResponse> list(HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return boardService.list(user).stream().map(BoardResponse::from).toList();
    }

    @PostMapping
    public BoardResponse create(@RequestBody BoardRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return BoardResponse.from(boardService.create(user, body.title()));
    }

    @GetMapping("/{boardId}")
    public BoardResponse get(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return BoardResponse.from(boardService.getOwned(boardId, user));
    }

    @PatchMapping("/{boardId}")
    public BoardResponse rename(@PathVariable UUID boardId, @RequestBody BoardRequest body, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return BoardResponse.from(boardService.rename(boardId, user, body.title()));
    }

    @DeleteMapping("/{boardId}")
    public void delete(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        boardService.delete(boardId, user);
    }
}
