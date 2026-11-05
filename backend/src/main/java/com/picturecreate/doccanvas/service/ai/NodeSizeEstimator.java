package com.picturecreate.doccanvas.service.ai;

/**
 * 节点尺寸估算：按标题字符宽度折行，长标题自动加宽加高。
 *
 * 模型从不输出宽高，固定尺寸会让长标题溢出。这里对齐 tldraw 3.15.6 的 geo 标签参数：
 * size=m 的标签字号 22px（LABEL_FONT_SIZES.m），行高 1.35（29.7px），
 * 标签上下左右各有 16px 内边距（LABEL_PADDING），控件宽度固定后文字按
 * overflow-wrap: break-word 折行。宽度必须不小于实际渲染宽度，否则 tldraw 会多折一行
 * 并通过 growY 撑高节点，导致排版时按旧高度算出的矩形互相重叠。
 *
 * 宽度来源：CJK 等非 ASCII 字符按 1 个字号（22px）计，与浏览器实测一致；
 * ASCII 字符使用 draw 字体（Shantell Sans Informal）在 22px 下的逐字符 advance 实测值。
 * 不同字母宽度差异很大（i 为 6.75px，M 为 22.04px），用统一比例会低估宽字母较多的标题。
 * 折行按浏览器规则模拟：ASCII 连续段视为整词，非 ASCII 字符逐个可断行，单词超宽时按字符拆开。
 */
public final class NodeSizeEstimator {

    /** 字号：geo 标签与逻辑图 fork 节点都使用 22px */
    private static final double FONT_PX = 22;
    /** 行高：字号 22 × 1.35 */
    private static final double LINE_HEIGHT = 29.7;
    /** 普通 geo 节点：LABEL_PADDING 16，左右、上下各一份 */
    private static final double GEO_H_PADDING = 32;
    private static final double GEO_V_PADDING = 32;
    private static final double GEO_MIN_W = 140;
    private static final double GEO_MIN_H = 60;
    private static final double GEO_MAX_W = 360;
    /** 逻辑图 fork 节点：无边框，四周各 8px 内边距，尺寸随标题收缩 */
    private static final double FORK_H_PADDING = 16;
    private static final double FORK_V_PADDING = 16;
    private static final double FORK_MIN_W = 40;
    private static final double FORK_MIN_H = 30;
    private static final double FORK_MAX_W = 360;
    /** 浏览器排版会把字形宽度取整到亚像素，估算留 0.5px 余量，保证不小于实际渲染宽度 */
    private static final double WIDTH_TOLERANCE = 0.5;

    /**
     * draw 字体在 22px 下的 ASCII advance 宽度（px），下标为码位减 32，覆盖 32..126。
     * 用浏览器 canvas measureText 实测得到，与 tldraw 的标签测量使用同一字体文件。
     */
    private static final double[] ASCII_ADVANCES = {
            7.55, 6.42, 10.98, 21.65, 15.40, 21.14, 14.74, 6.01, 10.43, 10.08, 11.07, 15.40, 6.36, 11.18, 6.60, 8.98,
            15.11, 9.28, 15.40, 15.40, 16.10, 13.66, 15.40, 15.36, 15.40, 15.40, 6.62, 6.60, 15.40, 15.40, 15.40, 11.18,
            19.69, 16.61, 13.62, 13.49, 14.89, 14.61, 12.83, 17.64, 15.93, 12.85, 13.77, 14.78, 11.81, 22.04, 17.36, 15.29,
            13.09, 16.72, 15.91, 12.36, 13.95, 14.67, 15.14, 21.14, 14.23, 13.62, 14.06, 11.20, 8.98, 10.98, 13.42, 15.40,
            7.79, 14.67, 13.13, 11.04, 14.87, 13.38, 9.79, 14.52, 14.67, 6.75, 6.38, 13.88, 8.03, 20.22, 14.39, 13.40,
            13.13, 13.68, 11.33, 11.46, 10.76, 13.51, 13.09, 20.66, 12.34, 13.20, 13.66, 11.84, 7.04, 11.84, 15.40
    };

    private NodeSizeEstimator() {
    }

    public record NodeSize(double width, double height) {
    }

    /** 按标题估算 geo 节点的最小可容纳尺寸 */
    public static NodeSize estimate(String title) {
        return estimate(title, GEO_H_PADDING, GEO_V_PADDING, GEO_MIN_W, GEO_MIN_H, GEO_MAX_W);
    }

    /** 按标题估算逻辑图 fork 节点的尺寸：无边框、小内边距，宽度随文字收缩 */
    public static NodeSize estimateFork(String title) {
        return estimate(title, FORK_H_PADDING, FORK_V_PADDING, FORK_MIN_W, FORK_MIN_H, FORK_MAX_W);
    }

    /** 计算出这个节点title占满一整行需要的宽度和高度 **/
    private static NodeSize estimate(String title, double hPadding, double vPadding, double minW,
            double minH, double maxW) {
        String text = title == null ? "" : title;
        String[] lines = text.isEmpty() ? new String[] { "" } : text.split("\\r?\\n", -1);
        double maxLineWidth = 0;
        int totalRows = 0;
        double usableWidth = maxW - hPadding;
        for (String line : lines) {
            maxLineWidth = Math.max(maxLineWidth, lineWidth(line));
            // 超过控件可用宽度时折行，行数决定高度
            totalRows += wrapRows(line, usableWidth);
        }
        double width = clamp(maxLineWidth + WIDTH_TOLERANCE + hPadding, minW, maxW);
        double height = Math.max(minH, totalRows * LINE_HEIGHT + vPadding);
        return new NodeSize(width, height);
    }

    /** 一行文字的总宽度：ASCII 查实测表，其余字符按一个字号计 */
    private static double lineWidth(String line) {
        double width = 0;
        for (int index = 0; index < line.length(); index++) {
            width += charWidth(line.charAt(index));
        }
        return width;
    }

    private static double charWidth(char value) {
        if (value >= 32 && value <= 126) {
            return ASCII_ADVANCES[value - 32];
        }
        return FONT_PX;
    }

    /**
     * 模拟浏览器的 break-word 折行并返回行数。
     * 断行规则：ASCII 连续段带一个尾随空格组成整词，非 ASCII 字符各自成块；
     * 放不下就换行，整词本身超过行宽时再按字符拆开。
     */
    private static int wrapRows(String line, double usableWidth) {
        int rows = 1;
        double current = 0;
        int index = 0;
        while (index < line.length()) {
            int end = tokenEnd(line, index);
            double tokenWidth = 0;
            for (int position = index; position < end; position++) {
                tokenWidth += charWidth(line.charAt(position));
            }
            if (tokenWidth > usableWidth) {
                // 单词比整行还宽，按字符逐个填行
                if (current > 0) {
                    rows++;
                    current = 0;
                }
                for (int position = index; position < end; position++) {
                    double width = charWidth(line.charAt(position));
                    if (current > 0 && current + width > usableWidth - WIDTH_TOLERANCE) {
                        rows++;
                        current = 0;
                    }
                    current += width;
                }
            } else if (current > 0 && current + tokenWidth > usableWidth - WIDTH_TOLERANCE) {
                rows++;
                current = tokenWidth;
            } else {
                current += tokenWidth;
            }
            index = end;
        }
        return rows;
    }

    /** 返回从 start 开始的一个断行块：非 ASCII 字符单字符成块，ASCII 连续词连同其后的一个空格成块 */
    private static int tokenEnd(String line, int start) {
        char first = line.charAt(start);
        if (!isAsciiPrintable(first)) {
            return start + 1;
        }
        int end = start + 1;
        while (end < line.length() && isAsciiPrintable(line.charAt(end)) && line.charAt(end) != ' ') {
            end++;
        }
        if (end < line.length() && line.charAt(end) == ' ') {
            end++;
        }
        return end;
    }

    private static boolean isAsciiPrintable(char value) {
        return value >= 32 && value <= 126;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
