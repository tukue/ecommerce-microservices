package com.grocery.microservices.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;

/**
 * Rate-limiting configuration: owns the {@link KeyResolver} and
 * {@link RedisRateLimiter} beans consumed by {@link GatewayRoutesConfig}.
 *
 * <h3>Key resolution strategy</h3>
 * <ol>
 *   <li>Authenticated JWT subject — separate budgets for BFF customers.</li>
 *   <li>{@code X-Forwarded-For} — nearest untrusted hop, only when the TCP
 *       peer is a configured trusted proxy.</li>
 *   <li>TCP remote address — used when no proxy header is present (e.g., direct
 *       Docker Compose connections in dev).</li>
 *   <li>{@code "unknown"} — fallback so the limiter still functions rather than
 *       throwing when neither source is available.</li>
 * </ol>
 *
 * <h3>Production trust model</h3>
 * Trusting {@code X-Forwarded-For} is safe only when the gateway is unreachable
 * directly from the internet — it must accept traffic only from the load
 * balancer's security group. An attacker reaching the gateway directly could
 * spoof the header to bypass per-IP limits. XFF is only trusted when it comes
 * from a configured trusted proxy IP; otherwise the remote address is used.
 *
 * <h3>Configuration</h3>
 * Set {@code gateway.trusted-proxies} to a comma-separated list of trusted
 * proxy IP addresses (e.g. the load balancer or nginx ingress IP). When
 * {@code trusted-proxies} is empty or not configured, XFF is not trusted and
 * the remote address is always used as a safety default for untrusted deployments.
 *
 * <p>Authenticated JWT subjects have their own bucket, so Vercel BFF customers
 * do not share a token budget merely because requests use a common outbound IP.
 * Anonymous callers fall back to the verified proxy chain or TCP address.</p>
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class RateLimitConfig {

    private final GatewayProperties props;

    public RateLimitConfig(GatewayProperties props) {
        this.props = props;
    }

    /**
     * Resolves the rate-limit bucket key from the inbound request.
     *
     * <p>Marked {@link Primary} so the {@code RequestRateLimiter} filter picks
     * this resolver when no explicit resolver name is configured on the route.</p>
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        List<String> trustedProxies = props.trustedProxies();

        return exchange -> {
            String xForwardedFor = exchange.getRequest()
                    .getHeaders()
                    .getFirst("X-Forwarded-For");

            String remoteAddress = Optional
                    .ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(InetSocketAddress::getAddress)
                    .map(InetAddress::getHostAddress)
                    .orElse("unknown");

            // Trust the TCP peer, never a client-supplied address claiming to be a proxy.
            if (xForwardedFor != null && !xForwardedFor.isBlank()
                    && trustedProxies != null && trustedProxies.contains(remoteAddress)) {
                String[] chain = xForwardedFor.split(",");
                for (int i = chain.length - 1; i >= 0; i--) {
                    String candidate = chain[i].trim();
                    if (!candidate.isBlank() && !trustedProxies.contains(candidate)) {
                        return Mono.just("ip:" + candidate);
                    }
                }
            }

            return Mono.just("ip:" + remoteAddress);
        };
    }

    @Bean
    public KeyResolver customerKeyResolver() {
        KeyResolver ipResolver = ipKeyResolver();
        return exchange -> exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .filter(JwtAuthenticationToken::isAuthenticated)
                .map(principal -> "user:" + principal.getName())
                .switchIfEmpty(Mono.defer(() -> ipResolver.resolve(exchange)));
    }

    /**
     * Redis token-bucket rate limiter shared across all {@code /api/customer/**}
     * routes.
     *
     * <p>Declared as a Spring bean so the underlying reactive Redis connection
     * is managed by the application context and properly shut down on stop.
     * The Lua script implementing the token bucket executes atomically in Redis
     * — rate-limit state is consistent across all gateway replicas.</p>
     */
    @Bean
    public RedisRateLimiter customerRateLimiter() {
        GatewayProperties.RateLimit rl = props.rateLimit();
        return new RedisRateLimiter(
                rl.replenishRate(),
                rl.burstCapacity(),
                rl.requestedTokens()
        );
    }
}
