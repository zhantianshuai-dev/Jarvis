package com.zhan.sandboxservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SandboxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxServiceApplication.class, args);
    }
}
