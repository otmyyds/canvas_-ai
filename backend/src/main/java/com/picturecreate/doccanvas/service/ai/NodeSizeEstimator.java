package com.picturecreate.doccanvas.service.ai;

/**
 * 节点尺寸估算：按标题字符宽度折行，长标题自动加宽加高。
 *
 * 模型从不输出宽高，固定尺寸会让长标题溢出。这里按 tldraw 中号字体的近似字宽估算，
 * 调用方只增不减地应用结果，避免把用户手动放大的节点缩回去。
 */
public final class NodeSizeEstimator {

    /** 中号字体字号 */
    private static final double FONT_PX = 16;
    /** 行高 */
    private static final double LINE_HEIGHT = 22;
    /** 水平/垂直内边距 */
    private static final double H_PADDING = 32;
    private static final double V_PADDING = 20;
    /** 尺寸上下限 */
    private static final double MIN_W = 140;
    private static final double MIN_H = 60;
    private static final double MAX_W = 360;
    private static final double MAX_H = 240;
    /** ASCII 字符相对全角字符的宽度比例 */
    private static final double ASCII_FACTOR = 0.55;

    private NodeSizeEstimator() {
    }

    public record NodeSize(double width, double height) {
    }

    /** 按标题估算最小可容纳尺寸 */
    public static NodeSize estimate(String title) {
        String text = title == null ? "" : title;
        String[] lines = text.isEmpty() ? new String[] { "" } : text.split("\\r?\\n", -1);
        double usableWidth = MAX_W - H_PADDING;
        double maxLineWidth = 0;
        int totalRows = 0;
        for (String line : lines) {
            double lineWidth = visualWidth(line);
            maxLineWidth = Math.max(maxLineWidth, lineWidth);
            // 超长单行按可用宽度折行，行数决定高度
            totalRows += Math.max(1, (int) Math.ceil(lineWidth / usableWidth));
        }
        double width = clamp(maxLineWidth + H_PADDING, MIN_W, MAX_W);
        double height = clamp(totalRows * LINE_HEIGHT + V_PADDING, MIN_H, MAX_H);
        return new NodeSize(width, height);
    }

    /** 视觉宽度：CJK/全角按一个字号，ASCII 按 0.55 个字号 */
    private static double visualWidth(String line) {
        double width = 0;
        for (int index = 0; index < line.length(); index++) {
            width += isWide(line.charAt(index)) ? FONT_PX : FONT_PX * ASCII_FACTOR;
        }
        return width;
    }

    /** 0x2E80 起覆盖 CJK 部首、假名、谚文、全角符号与统一汉字 */
    private static boolean isWide(char value) {
        return value >= 0x2E80;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
