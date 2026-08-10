package com.picturecreate.doccanvas.api.dto;

import com.picturecreate.doccanvas.domain.CardType;

import jakarta.validation.constraints.NotBlank;

public record CardRequest(
        @NotBlank(message = "卡片标题不能为空") String title,
        String contentMarkdown,
        CardType cardType,
        Double x,
        Double y,
        Double width,
        Double height) {
}
