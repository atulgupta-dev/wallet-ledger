package com.fintech.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.wallet.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${security.rate-limit.enabled:${app.rate-limit.enabled:false}}")
    private boolean enabled;

    @Value("${security.rate-limit.limit:${app.rate-limit.limit:1000}}")
    private int limit;

    @Value("${security.rate-limit.window-seconds:${app.rate-limit.window-seconds:60}}")
    private int windowSeconds;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (!enabled || !request.getRequestURI().startsWith("/api/v1/wallets")) {
            filterChain.doFilter(request, response);
            return;
        }

        String rateLimitKey = resolveRateLimitKey(request);
        Long currentCount = redisTemplate.opsForValue().increment(rateLimitKey);

        if (currentCount != null && currentCount == 1L) {
            redisTemplate.expire(rateLimitKey, Duration.ofSeconds(windowSeconds));
        }

        if (currentCount != null && currentCount > limit) {
            Long ttl = redisTemplate.getExpire(rateLimitKey, TimeUnit.SECONDS);
            long retryAfter = (ttl != null && ttl > 0) ? ttl : windowSeconds;

            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);

            ErrorResponse errorResponse = new ErrorResponse(
                    Instant.now(),
                    429,
                    "Too Many Requests",
                    "Rate limit exceeded. Maximum " + limit + " requests per " + windowSeconds + " seconds allowed.",
                    request.getRequestURI()
            );

            objectMapper.writeValue(response.getOutputStream(), errorResponse);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveRateLimitKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            return "rate:wallet:user:" + principal.getId();
        }

        String clientIp = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(clientIp) || "unknown".equalsIgnoreCase(clientIp)) {
            clientIp = request.getRemoteAddr();
        } else if (clientIp.contains(",")) {
            clientIp = clientIp.split(",")[0].trim();
        }
        return "rate:wallet:ip:" + clientIp;
    }
}
