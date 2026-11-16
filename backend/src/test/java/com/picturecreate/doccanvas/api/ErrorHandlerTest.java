package com.picturecreate.doccanvas.api;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.picturecreate.doccanvas.service.NotFoundException;
import com.picturecreate.doccanvas.service.QuotaExceededException;
import com.picturecreate.doccanvas.service.ai.llm.ModelBusyException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 错误响应契约测试：所有异常都必须返回 {code,message,data} 且保留原 HTTP 状态码。
 */
@DisplayName("统一错误响应")
class ErrorHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new ErrorHandler())
                .build();
    }

    @Test
    @DisplayName("404 资源不存在")
    void notFound() throws Exception {
        mvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("画布不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("400 参数错误")
    void badRequest() throws Exception {
        mvc.perform(get("/probe/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少父节点 id"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("409 操作冲突")
    void conflict() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("画布正在被修改"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("429 配额用尽")
    void quotaExceeded() throws Exception {
        mvc.perform(get("/probe/quota"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("今日 AI 配额已用尽"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("503 模型服务繁忙")
    void modelBusy() throws Exception {
        mvc.perform(get("/probe/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("模型服务繁忙，请稍后重试"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("400 请求体字段校验失败并翻译为中文")
    void validation() throws Exception {
        mvc.perform(post("/probe/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("内容不能为空"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("400 请求体不是合法 JSON")
    void unreadable() throws Exception {
        mvc.perform(post("/probe/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体或参数格式不正确"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("413 上传文件过大")
    void tooLarge() throws Exception {
        mvc.perform(get("/probe/too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.message").value("上传文件过大"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("500 未预期异常")
    void generic() throws Exception {
        mvc.perform(get("/probe/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("数据库连接失败"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/not-found")
        void notFound() {
            throw new NotFoundException("画布不存在");
        }

        @GetMapping("/probe/bad-request")
        void badRequest() {
            throw new IllegalArgumentException("缺少父节点 id");
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw new IllegalStateException("画布正在被修改");
        }

        @GetMapping("/probe/quota")
        void quotaExceeded() {
            throw new QuotaExceededException("今日 AI 配额已用尽");
        }

        @GetMapping("/probe/busy")
        void modelBusy() {
            throw new ModelBusyException("rate limited", null);
        }

        @PostMapping("/probe/validation")
        void validation(@Valid @RequestBody NameRequest body) {
        }

        @GetMapping("/probe/too-large")
        void tooLarge() {
            throw new MaxUploadSizeExceededException(1024);
        }

        @GetMapping("/probe/generic")
        void generic() {
            throw new RuntimeException("数据库连接失败");
        }
    }

    record NameRequest(@NotBlank String name) {
    }
}
