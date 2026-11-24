package com.picturecreate.doccanvas.service.ai.llm;

import org.springframework.context.annotation.Profile;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.picturecreate.doccanvas.config.AppProperties;

/**
 * 按操作类型选择模型。
 * operations 里没配的操作使用 default-model；模型名通过请求级 OpenAiChatOptions 传入。
 */
@Profile("!lite")
@Component
public class LlmModelRouter {

    private final AppProperties.Llm llm;

    public LlmModelRouter(AppProperties properties) {
        this.llm = properties.ai().llm();
    }

    public String modelFor(LlmOperation operation) {
        Map<String, String> operations = llm.operations();
        String key = operation.name().toLowerCase(Locale.ROOT);
        String model = operations == null ? null : operations.get(key);
        return model == null || model.isBlank() ? llm.defaultModel() : model;
    }

    /** 降级模型；为空表示不做降级 */
    public String fallbackFor(LlmOperation operation) {
        return llm.fallbackModel();
    }
}
