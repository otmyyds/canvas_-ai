package com.picturecreate.doccanvas.service.ai;

import java.util.List;

public record BoardActionResult(List<BoardAction> actions, String resultJson, int attempts) {

    public BoardActionResult(List<BoardAction> actions, String resultJson) {
        this(actions, resultJson, 1);
    }

    public static BoardActionResult empty(String resultJson) {
        return new BoardActionResult(List.of(), resultJson, 1);
    }
}
