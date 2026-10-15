package com.grocery.microservices.cart.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
@Profile("!test")
public class SecurityConfig {

    private final SecurityExceptionHandler securityExceptionHandler;
    private final JwtProperties jwtProperties;
    private final List<String> allowedOrigins;

    public SecurityConfig(SecurityExceptionHandler securityExceptionHandler,
                          JwtProperties jwtProperties,
                          @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.securityExceptionHandler = securityExceptionHandler;
        this.jwtProperties = jwtProperties;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toList();
        if (this.allowedOrigins.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origins must configure at least one origin");
        }
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        if (!jwtProperties.hasIssuerUri()) {
            throw new IllegalStateException(
                    "Required configuration property 'security.jwt.issuer-uri' must be set when JWT validation is active");
        }
        return new LazyJwtDecoder(() -> createDecoder());
    }

    private NimbusJwtDecoder createDecoder() {
        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(jwtProperties.issuerUri());
        decoder.setJwtValidator(jwtTokenValidator());
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> jwtTokenValidator() {
        String audience = jwtProperties.audience();
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "Required configuration property 'security.jwt.audience' must be set when JWT validation is active");
        }
        return new DelegatingOAuth2TokenValidator<>(
                new SubjectClaimValidator(),
                new JwtTimestampValidator(),
                new JwtIssuerValidator(jwtProperties.issuerUri()),
                new AudienceValidator(audience),
                new AllowedAlgorithmValidator(jwtProperties.algorithm())
        );
    }

    @Bean
    public CustomerJwtAuthenticationConverter jwtAuthenticationConverter() {
        return new CustomerJwtAuthenticationConverter();
    }

    private String[] demoAuthPaths() {
        return jwtProperties.demoEnabled() ? new String[] {"/auth/**", "/.well-known/**"} : new String[0];
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF is disabled because this service is a stateless OAuth2-resource-server API:
            // clients authenticate via the Authorization Bearer header (no cookie/session-based
            // two-step requests), so the CSRF token pattern does not apply.
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/swagger-resources/**",
                    "/webjars/**",
                    "/actuator/health",
                    "/actuator/info"
                ).permitAll()
                .requestMatchers(demoAuthPaths())
                .permitAll()
                .requestMatchers("/api/customer/internal/**")
                .permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
                .authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler)
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler)
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("Location"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}