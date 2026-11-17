package com.gkk.itip.common.auth;

import com.gkk.itip.common.config.DemoUserProvider;
import com.gkk.itip.common.exception.BusinessException;
import com.gkk.itip.common.exception.ErrorCode;
import com.gkk.itip.common.response.ApiResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

// Imported by the public API and Chat apps. The Worker does not serve user requests.
public class SupabaseAuthConfiguration {
    private final boolean demo;
    private final String issuer;

    public SupabaseAuthConfiguration(@Value("${itip.auth.mode:supabase}") String mode,
                                    @Value("${itip.auth.supabase-url:}") String projectUrl) {
        demo = switch (mode) {
            case "demo" -> true;
            case "supabase" -> false;
            default -> throw new IllegalArgumentException("ITIP_AUTH_MODE must be 'supabase' or 'demo'");
        };
        issuer = demo ? null : issuerFor(projectUrl);
    }

    @Bean
    CurrentUser currentUser(@Value("${itip.demo.owner-id:demo-user}") String demoOwner) {
        if (demo) return new DemoUserProvider(demoOwner);
        return () -> {
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authenticated
                    && authenticated.isAuthenticated()) {
                return authenticated.getToken().getSubject();
            }
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        };
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JsonMapper mapper) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (demo) {
            http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        } else {
            AuthenticationEntryPoint unauthorized = (request, response, failure) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                mapper.writeValue(response.getOutputStream(), ApiResponse.fail(ErrorCode.AUTHENTICATION_REQUIRED));
            };
            http.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized))
                    .authorizeHttpRequests(requests -> requests
                            .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(resource -> resource
                            .jwt(jwt -> jwt.decoder(decoder()))
                            .authenticationEntryPoint(unauthorized));
        }
        return http.build();
    }

    private JwtDecoder decoder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        var decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.ES256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .restOperations(new RestTemplate(factory))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), SupabaseAuthConfiguration::validateUser));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateUser(Jwt jwt) {
        var audience = jwt.getAudience();
        if (jwt.getExpiresAt() != null && audience != null && audience.contains("authenticated")
                && "authenticated".equals(jwt.getClaims().get("role"))
                && !Boolean.TRUE.equals(jwt.getClaims().get("is_anonymous")) && isUserId(jwt.getSubject())) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static boolean isUserId(String subject) {
        if (subject == null || subject.length() != 36) return false;
        try {
            return UUID.fromString(subject).toString().equals(subject);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String issuerFor(String projectUrl) {
        URI url = URI.create(projectUrl.strip());
        boolean local = "http".equals(url.getScheme()) && ("127.0.0.1".equals(url.getHost()) || "localhost".equals(url.getHost()));
        if ((!local && !"https".equals(url.getScheme())) || url.getHost() == null || url.getUserInfo() != null
                || url.getQuery() != null || url.getFragment() != null || !url.getRawPath().matches("/?")) {
            throw new IllegalArgumentException("SUPABASE_URL must be the project base URL");
        }
        return url.resolve("/auth/v1").toString();
    }
}
