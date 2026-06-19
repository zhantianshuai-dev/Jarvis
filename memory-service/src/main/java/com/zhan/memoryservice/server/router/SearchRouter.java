package com.zhan.memoryservice.server.router;

import com.zhan.memoryservice.model.SearchRequest;
import com.zhan.memoryservice.service.SearchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@Configuration
public class SearchRouter {

    private final SearchService searchService;

    public SearchRouter(SearchService searchService) {
        this.searchService = searchService;
    }

    @Bean
    public RouterFunction<ServerResponse> searchRoutes() {
        return route()
                .POST("/api/v1/find", this::handleFind)
                .POST("/api/v1/search", this::handleSearch)
                .build();
    }

    private Mono<ServerResponse> handleFind(ServerRequest req) {
        return req.bodyToMono(SearchRequest.class)
                .flatMap(sr -> Mono.fromCallable(() -> searchService.find(sr.query(), sr.limit()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(result -> ServerResponse.ok().bodyValue(result)));
    }

    private Mono<ServerResponse> handleSearch(ServerRequest req) {
        return req.bodyToMono(SearchRequest.class)
                .flatMap(sr -> Mono.fromCallable(() -> searchService.search(sr.query(), sr.limit(), sr.sessionId()))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(result -> ServerResponse.ok().bodyValue(result)));
    }
}
