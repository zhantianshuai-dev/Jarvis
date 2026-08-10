package com.zhan.jarvis.eval;

import com.zhan.jarvis.JarvisApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 独立评测入口。使用非 Web 应用模式，完成评测后主动退出进程。
 */
public final class JarvisEvaluationApplication {

    private JarvisEvaluationApplication() {
    }

    public static void main(String[] args) {
        var application = new SpringApplication(JarvisApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("eval");
        // local 配置可能为日常 Web 服务开启认证或外部集成；评测入口必须始终隔离这些能力。
        application.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("jarvisEvaluationOverrides", Map.of(
                        "jarvis.auth.enabled", "false",
                        "jarvis.auth.registration-enabled", "false",
                        "jarvis.mcp.enabled", "false",
                        "jarvis.channels.feishu.enabled", "false",
                        "jarvis.vision.enabled", "false",
                        "jarvis.heartbeat.enabled", "false",
                        "jarvis.cron.enabled", "false",
                        "jarvis.sandbox.backend", "direct"
                ))
        ));
        try (ConfigurableApplicationContext context = application.run(args)) {
            int exitCode = context.getBean(EvaluationRunner.class).runAll();
            System.exit(SpringApplication.exit(context, () -> exitCode));
        }
    }
}
