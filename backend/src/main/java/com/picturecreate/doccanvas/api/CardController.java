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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.dto.CardRequest;
import com.picturecreate.doccanvas.api.dto.CardResponse;
import com.picturecreate.doccanvas.service.CardService;
import com.picturecreate.doccanvas.service.CurrentUser;
import com.picturecreate.doccanvas.service.CurrentUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * 卡片（Card）接口控制器。
 *
 * 职责：知识卡片的增删改查。卡片是画布的核心内容单元，
 * 由用户手动创建或 AI 任务生成（CreateCard 动作落库到 cards 表）。
 *
 * 接口一览（前缀 /api）：
 * - GET    /boards/{boardId}/cards   → 画布下所有卡片
 * - POST   /boards/{boardId}/cards   → 新建卡片
 * - PATCH  /cards/{cardId}           → 更新卡片（标题/正文/类型/位置/尺寸）
 * - DELETE /cards/{cardId}           → 删除卡片（连带其连线）
 */
@RestController
@RequestMapping("/api")
public class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);

    private final CardService cardService;
    private final CurrentUserService currentUserService;

    public CardController(CardService cardService, CurrentUserService currentUserService) {
        this.cardService = cardService;
        this.currentUserService = currentUserService;
    }

    /** 获取画布下全部卡片（按创建时间升序） */
    @GetMapping("/boards/{boardId}/cards")
    public List<CardResponse> list(@PathVariable UUID boardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("查询卡片列表：boardId={}", boardId);
        return cardService.list(boardId, user).stream().map(CardResponse::from).toList();
    }

    /** 新建卡片（body: title/contentMarkdown/cardType/x/y/width/height） */
    @PostMapping("/boards/{boardId}/cards")
    public CardResponse create(@PathVariable UUID boardId, @Valid @RequestBody CardRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("新建卡片：boardId={} title={} type={}", boardId, body.title(), body.cardType());
        return CardResponse.from(cardService.create(boardId, user, body));
    }

    /** 更新卡片（卡片编辑的保存路径，双击编辑后调用） */
    @PatchMapping("/cards/{cardId}")
    public CardResponse update(@PathVariable UUID cardId, @Valid @RequestBody CardRequest body,
            HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("更新卡片：cardId={} title={}", cardId, body.title());
        return CardResponse.from(cardService.update(cardId, user, body));
    }

    /** 删除卡片（白板删除元素时同步调用，避免刷新复活） */
    @DeleteMapping("/cards/{cardId}")
    public void delete(@PathVariable UUID cardId, HttpServletRequest request) {
        CurrentUser user = currentUserService.resolve(request);
        log.info("删除卡片：cardId={}", cardId);
        cardService.delete(cardId, user);
    }
}
