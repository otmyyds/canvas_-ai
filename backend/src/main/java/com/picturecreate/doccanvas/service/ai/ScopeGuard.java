package com.picturecreate.doccanvas.service.ai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 能力范围硬兜底：对明显越界（既非画布内容生成、也非本系统咨询）的请求，
 * 用确定性关键词在调用模型前直接拒绝，不依赖模型判断。
 * 与聊天提示词里的软护栏互补：这里保证最明显的越界请求一定被拦下。
 */
@Component
public class ScopeGuard {

    private static final Logger log = LoggerFactory.getLogger(ScopeGuard.class);

    /** 明显越界的动作关键词，命中视为请求模型做画布之外的事 */
    private static final List<String> OUT_OF_SCOPE = List.of(
            "写代码", "编程", "debug", "写程序", "改bug",
            "翻译",
            "写诗", "写小说", "写作文", "写故事", "写情书", "写文案",
            "数学题", "解方程", "帮我算", "算一下",
            "天气",
            "看病", "开药", "诊断", "挂号",
            "打官司", "法律咨询", "找律师",
            "心理咨询", "情感咨询", "相亲");

    /** 画布内容生成关键词，出现则视为「以该话题生成画布内容」，放行交模型判断，避免误伤 */
    private static final List<String> CANVAS_HINTS = List.of(
            "思维导图", "流程图", "图片", "总结", "摘要", "学习计划", "产品需求",
            "生成", "制作", "画", "整理", "清空", "编辑", "导入", "导出", "上传");

    /** 返回拒绝文案；请求在能力范围内则返回 null */
    public String refuseReason(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        boolean outOfScope = OUT_OF_SCOPE.stream().anyMatch(prompt::contains);
        if (!outOfScope) {
            return null;
        }
        boolean canvasRelated = CANVAS_HINTS.stream().anyMatch(prompt::contains);
        if (canvasRelated) {
            return null;
        }
        log.info("命中能力范围硬拒绝：prompt={}", prompt);
        return "抱歉，我只能处理画布上的内容生成（思维导图、流程图、图片、资料摘要、学习计划、产品需求等）或回答关于本系统的使用问题，无法处理这类请求。";
    }
}
