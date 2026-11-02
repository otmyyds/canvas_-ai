package com.picturecreate.doccanvas.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 用 Apache HttpClient5 构建 RestClient，替换默认的 JDK HttpClient。
 * JDK HttpClient 连 OpenAI 兼容网关时偶发连接失败/卡住（HTTP/2 协商问题）。
 * HttpClients.custom() 默认启用 gzip 解压（ContentCompressionExec）。
 *
 * 显式关闭 HttpClient 的自动重试：它会把 429/IO 错误偷偷再发一次，
 * 真实请求数和任务预算对不上；重试统一由 LlmGateway 控制。
 */
@Configuration
public class HttpClientConfig {

    /** 连接超时（秒） */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    /** 响应超时（秒） */
    private static final int RESPONSE_TIMEOUT_SECONDS = 120;

    @Bean
    RestClient.Builder restClientBuilder() {
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManagerShared(false)
                .disableAutomaticRetries()
                .build();
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectTimeout(CONNECT_TIMEOUT_SECONDS * 1000);
        factory.setReadTimeout(RESPONSE_TIMEOUT_SECONDS * 1000);
        return RestClient.builder()
                .requestFactory(factory);
    }
}
