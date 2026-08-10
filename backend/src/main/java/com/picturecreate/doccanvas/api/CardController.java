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

import com.picturecreate.doccanvas.api.dto.CardRequest;
import com.picturecreate.doccanvas.api.dto.CardResponse;
import com.picturecreate.doccanvas.service.CardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CardController {

    private final CardService cardService;
    private final CurrentUserService currentUserService;

    public CardController(CardService cardService, CurrentUserService currentUserService) {
        this.cardService = cardService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/boards/{boardId}/cards")
    public List<CardResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return cardService.list(boardId, user).stream().map(CardResponse::from).toList();
    }

    @PostMapping("/boards/{boardId}/cards")
    public CardResponse create(@PathVariable UUID boardId, @Valid @RequestBody CardRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return CardResponse.from(cardService.create(boardId, user, body));
    }

    @PatchMapping("/cards/{cardId}")
    public CardResponse update(@PathVariable UUID cardId, @Valid @RequestBody CardRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        return CardResponse.from(cardService.update(cardId, user, body));
    }

    @DeleteMapping("/cards/{cardId}")
    public void delete(@PathVariable UUID cardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        cardService.delete(cardId, user);
    }
}
