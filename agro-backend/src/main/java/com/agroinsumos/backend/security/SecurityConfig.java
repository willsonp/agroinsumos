package com.agroinsumos.backend.security;

import com.agroinsumos.backend.security.oauth2.OAuth2LoginHandlers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import com.agroinsumos.backend.security.jwt.JwtConfig;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLICOS = {
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/oauth2/proveedores",
            "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health/**", "/error"
    };

    /**
     * Cadena 1: flujo OAuth2 (redirecciones del navegador). Necesita sesión HTTP solo durante
     * el intercambio (state/nonce). Si no hay proveedores configurados, estas rutas quedan cerradas.
     */
    @Bean
    @Order(1)
    SecurityFilterChain oauth2LoginChain(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> registros,
                                         OAuth2LoginHandlers handlers) throws Exception {
        http.securityMatcher("/oauth2/**", "/login/oauth2/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        if (registros.getIfAvailable() != null) {
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
                    .oauth2Login(o -> o.successHandler(handlers).failureHandler(handlers));
        } else {
            http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
        }
        return http.build();
    }

    /** Cadena 2: API REST sin estado, autenticada con Bearer JWT. */
    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, JwtConfig.UsuarioVigenteJwtConverter jwtConverter,
                                 AuthenticationEntryPoint entryPoint, AccessDeniedHandler deniedHandler) throws Exception {
        http.csrf(c -> c.disable()) // API stateless con Bearer: sin cookies de sesión, CSRF no aplica
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLICOS).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }

    @Bean
    AuthenticationManager authenticationManager(UsuarioUserDetailsService uds, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        provider.setUserDetailsPasswordService(uds); // migración automática SHA-1 -> BCrypt
        return new ProviderManager(provider);
    }

    /** 401/403 con el mismo formato ProblemDetail que el resto de la API. */
    @Bean
    AuthenticationEntryPoint problemEntryPoint(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (req, res, ex) -> resolver.resolveException(req, res, null, ex);
    }

    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (req, res, ex) -> resolver.resolveException(req, res, null, ex);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.cors().allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
