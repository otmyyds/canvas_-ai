package com.picturecreate.doccanvas.service.ai;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.constraints.NotBlank;

/**
 * AI 产出的结构化画布动作。AI 只提案，真正落库由后端统一校验后执行。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BoardAction.CreateCard.class, name = "create_card"),
        @JsonSubTypes.Type(value = BoardAction.CreateShape.class, name = "create_shape"),
        @JsonSubTypes.Type(value = BoardAction.UpdateCard.class, name = "update_card"),
        @JsonSubTypes.Type(value = BoardAction.Connect.class, name = "connect"),
        @JsonSubTypes.Type(value = BoardAction.CreateGroup.class, name = "create_group"),
        @JsonSubTypes.Type(value = BoardAction.DeleteCard.class, name = "delete_card"),
        @JsonSubTypes.Type(value = BoardAction.DeleteShape.class, name = "delete_shape")
})
public sealed interface BoardAction permits BoardAction.CreateCard, BoardAction.CreateShape,
        BoardAction.UpdateCard, BoardAction.Connect, BoardAction.CreateGroup, BoardAction.DeleteCard,
        BoardAction.DeleteShape {

    /** 新建一张卡片；id 可选，供本计划内其他动作（connect）引用新卡 */
    record CreateCard(
            String id,
            @NotBlank(message = "卡片标题不能为空") String title,
            String markdown,
            String cardType,
            double x,
            double y,
            double width,
            double height,
            List<UUID> sourceFileIds) implements BoardAction {
    }

    /**
     * 新建一个图形节点（思维导图/流程图节点）。
     * shape 取值：ellipse/rectangle/oval（对应 tldraw geo）。
     * level 表示层级：0=中心主题，1=一级分支，2=二级叶子。
     */
    record CreateShape(
            String id,
            @NotBlank(message = "节点标题不能为空") String title,
            String shape,
            int level,
            double x,
            double y,
            double width,
            double height) implements BoardAction {
    }

    /** 更新一张已有卡片 */
    record UpdateCard(
            @NotBlank(message = "卡片 id 不能为空") String cardId,
            String title,
            String markdown) implements BoardAction {
    }

    /**
     * 在两个节点之间画一条关系线。
     * style: curve=曲线 / straight=直线；color 十六进制；weight 1-4（粗细）。
     */
    record Connect(
            @NotBlank(message = "起点节点不能为空") String fromCardId,
            @NotBlank(message = "终点节点不能为空") String toCardId,
            String label,
            String style,
            String color,
            Integer weight) implements BoardAction {
    }

    /** 对一组卡片创建分组（当前 MVP 无分组表，识别但不落库） */
    record CreateGroup(
            @NotBlank(message = "分组标题不能为空") String title,
            List<String> cardIds) implements BoardAction {
    }

    /** 删除一张卡片（连带其连线） */
    record DeleteCard(
            @NotBlank(message = "卡片 id 不能为空") String cardId) implements BoardAction {
    }

    /** 删除一个图形节点（思维导图/流程图节点，连带其连线） */
    record DeleteShape(
            @NotBlank(message = "节点 id 不能为空") String shapeId) implements BoardAction {
    }

    /** 模型返回的整体计划，便于 Spring AI 结构化反序列化 */
    record BoardActionPlan(List<BoardAction> actions) {
    }
}
