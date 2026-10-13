package com.banking.gateway.filter;

import com.banking.gateway.security.JwtUtil;
import com.banking.gateway.security.RouteValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// GlobalFilter that runs on every request through the gateway.
// Validates JWT and forwards user context as headers to downstream services.
// Downstream services trust these headers — no Spring Security / DB lookup needed there.
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;
    private final RouteValidator routeValidator;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Public routes skip JWT validation entirely
        if (routeValidator.isPublic(request)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or malformed Authorization header: {}", request.getURI().getPath());
            return onError(exchange, HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);

        try {
            if (jwtUtil.isTokenExpired(token)) {
                log.warn("Expired JWT token for path: {}", request.getURI().getPath());
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            String email  = jwtUtil.extractEmail(token);
            String role   = jwtUtil.extractRole(token);    // null for monolith-issued tokens
            String userId = jwtUtil.extractUserId(token);  // null for monolith-issued tokens

            // Mutate the request to add user context headers.
            // Downstream services read X-User-Email, X-User-Role, X-User-Id instead of
            // repeating JWT validation. Empty string is sent when the claim is absent.
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-User-Email", email)
                    .header("X-User-Role",  role   != null ? role   : "")
                    .header("X-User-Id",    userId != null ? userId : "")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            log.warn("JWT validation failed for path {}: {}", request.getURI().getPath(), e.getMessage());
            return onError(exchange, HttpStatus.UNAUTHORIZED);
        }
    }

    // Runs before all other filters (order -1)
    @Override
    public int getOrder() {
        return -1;
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
