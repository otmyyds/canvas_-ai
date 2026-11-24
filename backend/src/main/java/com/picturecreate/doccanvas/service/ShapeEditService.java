package com.picturecreate.doccanvas.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.picturecreate.doccanvas.domain.BoardEdge;
import com.picturecreate.doccanvas.domain.Shape;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptEdge;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptNode;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.AdoptionResult;
import com.picturecreate.doccanvas.service.ai.BoardActionApplier.ChildOrder;

/**
 * 用户手动编辑画布结构：新建子节点、删除节点（思维导图连带子树）。
 * 图算法与重排复用 BoardActionApplier，这里只做画布归属校验。
 */
@Service
public class ShapeEditService {

    private final BoardService boardService;
    private final BoardActionApplier actionApplier;
    private final ShapeRepository shapeRepository;
    private final BoardEditingGuard editingGuard;

    public ShapeEditService(BoardService boardService, BoardActionApplier actionApplier,
            ShapeRepository shapeRepository, BoardEditingGuard editingGuard) {
        this.boardService = boardService;
        this.actionApplier = actionApplier;
        this.shapeRepository = shapeRepository;
        this.editingGuard = editingGuard;
    }

    /** 新建节点：parentId 为空时创建独立根节点，否则在父节点下新建子节点并重排所属分量 */
    @Transactional
    public Shape createNode(UUID boardId, CurrentUser user, UUID parentId, String title) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        return actionApplier.createNode(boardId, parentId, title);
    }

    /** 删除节点：思维导图删除整棵子树，其他分量只删节点本身，连带清理连线 */
    @Transactional
    public void delete(UUID boardId, CurrentUser user, UUID shapeId) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        actionApplier.deleteForUser(boardId, shapeId);
    }

    /** 修改节点标题：按新文字重算尺寸并只重排所属分量，不重排整张画布 */
    @Transactional
    public Shape updateTitle(UUID boardId, CurrentUser user, UUID shapeId, String title, String visualJson) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        return actionApplier.updateTitleForUser(boardId, shapeId, title, visualJson);
    }

    /** 普通字段编辑：拖动、改色、改尺寸，不触发布局；AI 计划期间拒绝写入 */
    @Transactional
    public Shape updateFields(UUID boardId, CurrentUser user, UUID shapeId, String title,
            double x, double y, double width, double height, String visualJson) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        Shape shape = shapeRepository.findById(shapeId)
                .filter(found -> found.getBoardId().equals(boardId))
                .orElseThrow(() -> new NotFoundException("图形节点不存在"));
        shape.applyUserEdit(title, x, y, width, height);
        if (visualJson != null) {
            shape.applyVisual(visualJson);
        }
        return shapeRepository.save(shape);
    }

    /** 调整同级顺序（逻辑图一级分支同时提交左右侧），重排该分量 */
    @Transactional
    public List<BoardEdge> reorderChildren(UUID boardId, CurrentUser user, UUID parentId,
            List<ChildOrder> children) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        return actionApplier.reorderChildren(boardId, parentId, children);
    }

    /** 折叠/展开节点：折叠后后代节点不参与布局与渲染 */
    @Transactional
    public Shape setCollapsed(UUID boardId, CurrentUser user, UUID shapeId, boolean collapsed) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        return actionApplier.setCollapsed(boardId, shapeId, collapsed);
    }

    /** 纳管画布上手绘的自由图形与连线，保持原坐标尺寸，不触发布局 */
    @Transactional
    public AdoptionResult adopt(UUID boardId, CurrentUser user, List<AdoptNode> nodes, List<AdoptEdge> edges) {
        boardService.getOwned(boardId, user);
        editingGuard.assertEditable(boardId);
        return actionApplier.adopt(boardId, nodes, edges);
    }
}
