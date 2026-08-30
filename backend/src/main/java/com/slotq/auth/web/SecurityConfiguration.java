package com.slotq.auth.web;

import java.util.List;

import com.slotq.config.SlotqCorsProperties;
import com.slotq.web.ProductApiSecurityProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfiguration {

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
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
