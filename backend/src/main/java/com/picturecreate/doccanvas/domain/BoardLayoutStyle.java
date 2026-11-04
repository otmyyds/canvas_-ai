package com.picturecreate.doccanvas.domain;

/**
 * 思维导图排布风格：
 * - RADIAL 树形图：根在中心，子节点径向辐射；
 * - LOGIC 逻辑图：从左到右分列，适合节点较多的大图。
 */
public enum BoardLayoutStyle {
    RADIAL,
    LOGIC
}
