package com.picturecreate.doccanvas.service.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.constraints.NotBlank;

/**
 * AI 产出的结构化画布动作。AI 只输出文档，动作由程序解析，真正落库由后端统一校验后执行。
 * 画布只有一种节点：图形节点（shape），故动作收敛为建节点 / 改节点 / 删节点 / 连线四类。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BoardAction.CreateShape.class, name = "create_shape"),
        @JsonSubTypes.Type(value = BoardAction.UpdateShape.class, name = "update_shape"),
        @JsonSubTypes.Type(value = BoardAction.Connect.class, name = "connect"),
        @JsonSubTypes.Type(value = BoardAction.DeleteShape.class, name = "delete_shape")
})
public sealed interface BoardAction permits BoardAction.CreateShape, BoardAction.UpdateShape, BoardAction.Connect,
        BoardAction.DeleteShape {

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

    /**
     * 在两个图形节点之间画一条关系线。
     * style: curve=曲线 / straight=直线；color 十六进制；weight 1-4（粗细）。
     */
    record Connect(
            @NotBlank(message = "起点节点不能为空") String fromShapeId,
            @NotBlank(message = "终点节点不能为空") String toShapeId,
            String label,
            String style,
            String color,
            Integer weight) implements BoardAction {
    }

    /**
     * 更新已有图形节点的语义字段。shapeId 必须是真实 id；
     * 坐标与尺寸由用户或布局服务负责，这里不接收。
     */
    record UpdateShape(
            @NotBlank(message = "节点 id 不能为空") String shapeId,
            String title,
            String shape,
            Integer level) implements BoardAction {
    }

    /** 删除一个图形节点（思维导图/流程图节点，连带其连线） */
    record DeleteShape(
            @NotBlank(message = "节点 id 不能为空") String shapeId) implements BoardAction {
    }

    /** 模型返回的整体计划，便于 Spring AI 结构化反序列化 */
    record BoardActionPlan(List<BoardAction> actions) {
    }
}
