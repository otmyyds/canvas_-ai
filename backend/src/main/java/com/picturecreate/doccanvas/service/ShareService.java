package com.picturecreate.doccanvas.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.picturecreate.doccanvas.api.ShapeController.ShapeResponse;
import com.picturecreate.doccanvas.api.dto.BoardEdgeDtos.BoardEdgeResponse;
import com.picturecreate.doccanvas.api.dto.ShareDtos.ShareLinkResponse;
import com.picturecreate.doccanvas.api.dto.ShareDtos.SharedBoardResponse;
import com.picturecreate.doccanvas.domain.Board;
import com.picturecreate.doccanvas.domain.BoardSnapshot;
import com.picturecreate.doccanvas.domain.ShareLink;
import com.picturecreate.doccanvas.repository.BoardEdgeRepository;
import com.picturecreate.doccanvas.repository.BoardRepository;
import com.picturecreate.doccanvas.repository.BoardSnapshotRepository;
import com.picturecreate.doccanvas.repository.ShapeRepository;
import com.picturecreate.doccanvas.repository.ShareLinkRepository;

/**
 * 分享服务：生成只读分享链接 + 凭 token 读取分享画布。
 * 分享内容（标题/图形节点/连线/快照）为只读快照，不暴露写接口。
 */
@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    /** 权限固定只读 */
    private static final String PERMISSION_READONLY = "READONLY";

    private final ShareLinkRepository shareLinkRepository;
    private final BoardRepository boardRepository;
    private final ShapeRepository shapeRepository;
    private final BoardEdgeRepository edgeRepository;
    private final BoardSnapshotRepository snapshotRepository;

    public ShareService(ShareLinkRepository shareLinkRepository, BoardRepository boardRepository,
            ShapeRepository shapeRepository, BoardEdgeRepository edgeRepository,
            BoardSnapshotRepository snapshotRepository) {
        this.shareLinkRepository = shareLinkRepository;
        this.boardRepository = boardRepository;
        this.shapeRepository = shapeRepository;
        this.edgeRepository = edgeRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /** 为画布生成只读分享链接（仅 owner 可生成） */
    @Transactional
    public ShareLinkResponse createShareLink(UUID boardId, CurrentUser user) {
        boardRepository.findByIdAndOwnerId(boardId, user.id())
                .orElseThrow(() -> new NotFoundException("画布不存在"));
        String token = UUID.randomUUID().toString().replace("-", "");
        ShareLink link = shareLinkRepository.save(new ShareLink(boardId, user.id(), token, PERMISSION_READONLY));
        log.info("生成分享链接：boardId={} token={}", boardId, token);
        return new ShareLinkResponse(token);
    }

    /** 凭 token 读取分享画布的只读内容（公开，无需登录） */
    @Transactional(readOnly = true)
    public SharedBoardResponse readSharedBoard(String token) {
        ShareLink link = shareLinkRepository.findByToken(token)
                .orElseThrow(() -> new NotFoundException("分享链接不存在或已失效"));
        if (link.getExpiresAt() != null && link.getExpiresAt().isBefore(Instant.now())) {
            throw new NotFoundException("分享链接已过期");
        }
        Board board = boardRepository.findById(link.getBoardId())
                .orElseThrow(() -> new NotFoundException("画布不存在"));
        List<ShapeResponse> shapes = shapeRepository.findByBoardIdOrderByCreatedAtAsc(link.getBoardId()).stream()
                .map(ShapeResponse::from)
                .toList();
        List<BoardEdgeResponse> edges = edgeRepository.findByBoardIdOrderByCreatedAtAsc(link.getBoardId()).stream()
                .map(BoardEdgeResponse::from)
                .toList();
        String snapshotJson = snapshotRepository.findFirstByBoardIdOrderByVersionDesc(link.getBoardId())
                .map(BoardSnapshot::getSnapshotJson)
                .orElse(null);
        log.info("读取分享画布：token={} 图形数={} 连线数={}", token, shapes.size(), edges.size());
        return new SharedBoardResponse(board.getId(), board.getTitle(), shapes, edges, snapshotJson);
    }
}
