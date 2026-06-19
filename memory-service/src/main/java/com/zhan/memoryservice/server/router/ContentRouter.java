package com.zhan.memoryservice.server.router;

import com.zhan.memoryservice.model.WriteRequest;
import com.zhan.memoryservice.service.ContentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class ContentRouter {

    private final ContentService contentService;

    public ContentRouter(ContentService contentService) {
        this.contentService = contentService;
    }

    @Bean
    public RouterFunction<ServerResponse> contentRoute() {
        return route(POST("/api/v1/content/write"), this::handleWrite);
    }

    private Mono<ServerResponse> handleWrite(ServerRequest req) {
        return req.bodyToMono(WriteRequest.class)
                .flatMap(wr -> {
                    var result = contentService.write(wr);
                    return ServerResponse.ok().bodyValue(result);
                });
    }
}
