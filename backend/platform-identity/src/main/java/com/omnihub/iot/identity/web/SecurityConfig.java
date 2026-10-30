package com.omnihub.iot.identity.web;

import com.omnihub.iot.common.ApiResponse;
import com.omnihub.iot.common.Jsons;
import com.omnihub.iot.config.CorsProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter,
            MetricsTokenFilter metricsFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable).cors(c -> {
        }).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/login", "/actuator/health", "/actuator/health/**").permitAll()
                        // WebSocket handshake authenticates itself with the token query parameter
                        .requestMatchers("/ws").permitAll()
                        // called by the MQTT broker, protected by a shared secret header inside the controller
                        .requestMatchers("/api/mqtt/auth", "/api/mqtt/acl").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAuthority("METRICS")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                    res.getWriter().write(Jsons.toJson(ApiResponse.error(401, "未登录或登录已过期", null)));
                }).accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
                    res.getWriter().write(Jsons.toJson(ApiResponse.error(403, "无权限执行该操作", null)));
                })).addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(metricsFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(props.getCorsAllowedOrigins().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
