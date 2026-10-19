package com.lee.psyqa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 同步 HTTP 调用（翻译、改写、摘要、危机判定、rerank、embedding）用的 RestClient.Builder，带超时。
 * <p>
 * Spring Boot 只在非响应式应用里自动配置 RestClient.Builder（≈ IHttpClientFactory 只在某种宿主里注册）。
 * 本项目 CLI 模式是非响应式、Web 模式是 WebFlux：Web 模式下没有这个 bean，
 * Spring AI 会退回 RestClient.builder()（没有读超时），Reranker 直接启动失败（2026-10-19 发现，T5 引入）。
 * 自己定义一个，两种模式都用它；Boot 的自动配置看到已有 bean 会让位。
 * <p>
 * 流式生成走 WebClient，超时由 spring.http.reactiveclient 配置。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    @Scope("prototype")   // Builder 是可变的，每个使用方拿一个新的（和 Boot 自动配置的一致）
    RestClient.Builder restClientBuilder(@Value("${spring.http.client.connect-timeout:10s}") Duration connectTimeout,
                                                @Value("${spring.http.client.read-timeout:60s}") Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
