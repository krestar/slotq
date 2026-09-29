package com.slotq.auth.web;

import java.util.List;

import com.slotq.config.SlotqCorsProperties;
import com.slotq.web.ProductApiSecurityProblemWriter;
import com.slotq.observability.web.ScrapeCredential;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain telemetrySecurityFilterChain(HttpSecurity http, ScrapeCredential credential)
        throws Exception {
        var endpoints = EndpointRequest.toAnyEndpoint();
        return http
            .securityMatcher(request -> request.getRequestURI().equals(request.getContextPath() + "/actuator")
                || request.getRequestURI().startsWith(request.getContextPath() + "/actuator/")
                || endpoints.matches(request))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/actuator/prometheus")
                    .access((authentication, context) ->
                        new AuthorizationDecision(credential.matches(context.getRequest())))
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> response.setStatus(401))
                .accessDeniedHandler((request, response, exception) -> response.setStatus(403)))
            .build();
    }

    @Bean
    FilterRegistrationBean<BearerCredentialAuthenticationFilter> disableContainerRegistration(
        BearerCredentialAuthenticationFilter filter
    ) {
        FilterRegistrationBean<BearerCredentialAuthenticationFilter> registration =
            new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        BearerCredentialAuthenticationFilter bearerFilter,
        ProductApiSecurityProblemWriter problemWriter
    ) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> { })
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .addFilterBefore(bearerFilter, AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.POST, "/__dev/auth/session").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/venues").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/venues/*/availability").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(problemWriter::authenticationRequired)
                .accessDeniedHandler(problemWriter::accessDenied)
            )
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SlotqCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Location", "X-Request-ID"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
