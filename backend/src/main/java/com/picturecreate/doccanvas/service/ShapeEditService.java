package com.picturecreate.doccanvas.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier;

/**
 * 用户手动编辑画布结构：新建子节点、删除节点（思维导图连带子树）。
 * 图算法与重排复用 BoardActionApplier，这里只做画布归属校验。
 */
@Service
public class ShapeEditService {

    private final BoardService boardService;
    private final BoardActionApplier actionApplier;

    public ShapeEditService(BoardService boardService, BoardActionApplier actionApplier) {
        this.boardService = boardService;
        this.actionApplier = actionApplier;
    }

    /** 在指定父节点下新建子节点  并重排所属分量 */
    @Transactional
    public Shape createChild(UUID boardId, CurrentUser user, UUID parentId, String title) {
        boardService.getOwned(boardId, user);
        return actionApplier.createChild(boardId, parentId, title);
    }

    /** 删除节点：思维导图删除整棵子树，其他分量只删节点本身，连带清理连线 */
    @Transactional
    public void delete(UUID boardId, CurrentUser user, UUID shapeId) {
        boardService.getOwned(boardId, user);
        actionApplier.deleteForUser(boardId, shapeId);
    }
}
