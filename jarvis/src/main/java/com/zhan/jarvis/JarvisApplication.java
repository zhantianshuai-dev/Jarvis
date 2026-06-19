package com.zhan.jarvis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ConfigurationPropertiesScan  // 扫描 @ConfigurationProperties Bean (JarvisConfig)
@ComponentScan("com.zhan")   // 扫描 common 模块的 Bean（WebClientConfig、PromptManager）
public class JarvisApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(JarvisApplication.class);
        app.addInitializers(ctx -> {
            boolean authEnabled = ctx.getEnvironment()
                    .getProperty("jarvis.auth.enabled", Boolean.class, false);
            if (!authEnabled) {
                ctx.getEnvironment().getSystemProperties().put(
                        "spring.autoconfigure.exclude",
                        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
                                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
                );
            }
        });
        app.run(args);
    }

}
