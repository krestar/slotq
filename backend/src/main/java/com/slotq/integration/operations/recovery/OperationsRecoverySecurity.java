package com.slotq.integration.operations.recovery;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OperationsRecoverySecurity {
    @Bean
    @Order(0)
    SecurityFilterChain humanOperationsChain(HttpSecurity http, OperatorCredentials credentials,
        @Value("${slotq.operations.recovery.enabled:false}") boolean enabled) throws Exception {
        return http.securityMatcher("/internal/operations", "/internal/operations/**")
            .csrf(csrf -> csrf.disable()).cors(cors -> cors.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                    response.setHeader("Cache-Control", "no-store");
                    // No Product CORS, credentials, monitoring secret or localhost trust in this chain.
                    if (!enabled) { write(response,404,"TARGET_NOT_FOUND"); return; }
                    if (!request.isSecure() || request.getHeader("Origin") != null) {
                        write(response,403,"PRIVATE_OPERATIONS_REQUIRED"); return;
                    }
                    String header = request.getHeader("Authorization");
                    if (header != null && header.startsWith("Bearer ")) {
                        try {
                            credentials.authenticate(header.substring(7)).ifPresent(actor ->
                                SecurityContextHolder.getContext().setAuthentication(
                                    UsernamePasswordAuthenticationToken.authenticated(actor, null,
                                        List.of(new SimpleGrantedAuthority("HUMAN_OPERATOR")))));
                        } catch (RuntimeException unavailable) {
                            write(response,503,"OPERATIONS_UNAVAILABLE"); return;
                        }
                    }
                    chain.doFilter(request,response);
                }
            }, AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().access((authentication, context) ->
                new AuthorizationDecision(authentication.get().getPrincipal() instanceof HumanOperator)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,error)->write(response,401,"OPERATOR_AUTHENTICATION_REQUIRED"))
                .accessDeniedHandler((request,response,error)->write(response,403,"OPERATOR_ACCESS_DENIED")))
            .build();
    }

    private static void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
