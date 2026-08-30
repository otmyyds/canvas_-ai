package com.picturecreate.doccanvas.api.dto;

import java.util.List;
import java.util.UUID;

import com.picturecreate.doccanvas.api.ShapeController.ShapeResponse;

public final class ShareDtos {

    private ShareDtos() {
    }

    /** 生成分享链接的响应（前端拼 /share/{token} 得到完整地址） */
    public record ShareLinkResponse(String token) {
    }

    /** 公开只读读取分享画布的响应（标题 + 图形节点 + 连线 + 快照） */
    public record SharedBoardResponse(
            UUID boardId,
            String title,
            List<ShapeResponse> shapes,
            List<BoardEdgeDtos.BoardEdgeResponse> edges,
            String snapshotJson) {
    }
}
