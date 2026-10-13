package com.banking.gateway.security;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.List;

// Determines which routes bypass JWT validation.
@Component
public class RouteValidator {

    // Requests to these paths are forwarded without a JWT check.
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/users/register",
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs",
            "/user-service/v3/api-docs",
            "/account-service/v3/api-docs",
            "/transaction-service/v3/api-docs",
            "/notification-service/v3/api-docs"
    );

    public boolean isPublic(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

//

}
