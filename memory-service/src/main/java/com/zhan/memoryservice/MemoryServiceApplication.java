package com.zhan.memoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan("com.zhan")  // 扫描 common 模块的 Bean（WebClientConfig、PromptManager）
public class MemoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(MemoryServiceApplication.class);
        app.addInitializers(ctx -> {
            boolean postgresEnabled = ctx.getEnvironment()
                    .getProperty("memory-service.postgres.enabled", Boolean.class, false);
            if (!postgresEnabled) {
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
