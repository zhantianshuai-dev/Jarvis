package com.zhan.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;

@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    @Bean
    public WebClient.Builder webClientBuilder() {
        var httpBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL);

        var proxyAddr = parseProxyEnv("HTTPS_PROXY");
        if (proxyAddr != null) {
            httpBuilder.proxy(ProxySelector.of(proxyAddr));
            log.info("HTTP 代理已配置: {}:{}", proxyAddr.getHostString(), proxyAddr.getPort());
        } else {
            log.warn("未检测到 HTTPS_PROXY 环境变量，直连");
        }

        return WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(httpBuilder.build()));
    }

    static InetSocketAddress parseProxyEnv(String envName) {
        var url = System.getenv(envName);
        if (url == null || url.isBlank()) return null;
        try {
            var uri = URI.create(url);
            return new InetSocketAddress(uri.getHost(), uri.getPort());
        } catch (Exception e) {
            log.warn("解析代理环境变量失败 {}: {}", envName, url);
            return null;
        }
    }
}