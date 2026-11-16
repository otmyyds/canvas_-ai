package com.picturecreate.doccanvas.config;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.picturecreate.doccanvas.common.JwtService;

/**
 * 安全过滤器链的响应契约：未登录 401 也必须是 {code,message,data}。
 * SecurityConfig 直接写响应体，绕过 @RestControllerAdvice，最容易漏掉统一格式。
 */
@WebMvcTest(controllers = SecurityProbeController.class)
@Import(SecurityConfig.class)
@DisplayName("安全过滤器响应")
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AppProperties properties;

    @Test
    @DisplayName("未登录返回 401 与统一响应体")
    void unauthenticatedReturnsUnifiedBody() throws Exception {
        mvc.perform(get("/api/security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录或登录已过期"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("已认证请求可以正常进入控制器")
    void authenticatedPasses() throws Exception {
        mvc.perform(get("/api/security-probe").with(user("tester")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    @DisplayName("未知接口返回 404，而不是被通用异常兜成 500")
    void unknownPathReturnsNotFound() throws Exception {
        mvc.perform(get("/api/does-not-exist").with(user("tester")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

}
