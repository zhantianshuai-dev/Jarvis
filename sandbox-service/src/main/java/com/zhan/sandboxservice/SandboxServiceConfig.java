package com.zhan.sandboxservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sandbox")
public record SandboxServiceConfig(
        String root,
        int timeoutSeconds,
        int maxOutputChars
) {
}
