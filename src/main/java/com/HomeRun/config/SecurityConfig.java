package com.HomeRun.config;

import com.HomeRun.security.*;
import com.HomeRun.service.CustomOAuth2UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final JwtProvider jwtProvider;
    private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
    private final CustomAccessDeniedHandler customAccessDeniedHandler;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 시큐리티 세팅에 CORS 설정을 적용
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                .csrf(csrf -> csrf.disable())

                // 💡 중요: 세션을 사용하지 않겠다(STATELESS)고 선언합니다. (JWT 방식의 핵심)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/login**",
                                "/api/token-test",
                                "/api/auth/**",
                                "/api-docs/**",
                                "/v3/api-docs/**",     // Swagger 데이터
                                "/swagger-ui/**",      // Swagger UI 화면
                                "/swagger-ui.html",    // Swagger UI 진입점
                                "/h2-console/**"       // H2 Console (enabled only in local profile)
                        ).permitAll() // 토큰 테스트 URL은 통과시켜 줍니다.
                        .anyRequest().authenticated()
                )

                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                        )
                        // 💡 중요: 로그인 성공 시 기본 URL로 가는 대신, 우리가 만든 핸들러가 작동하도록 설정합니다.
                        .successHandler(oAuth2SuccessHandler)
                )

                // 💡 인증 인가 실패 시 처리 핸들러 등록
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(customAuthenticationEntryPoint)
                        .accessDeniedHandler(customAccessDeniedHandler)
                )

                // H2 Console renders its UI in a same-origin frame.
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin()))

                // 💡 중요: 스프링 기본 인증 필터가 작동하기 전에, 우리가 만든 JwtFilter를 먼저 거치도록 설정합니다.
                .addFilterBefore(new JwtFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // 구체적인 CORS 허용 규칙을 정의하는 메서드
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 프론트엔드가 사용하는 주소를 정확히 명시하여 허용합니다. (스웨거 및 프론트 개발 서버 주소)
        configuration.setAllowedOrigins(List.of(
        "http://localhost:8081",
        "http://localhost:8080",
        "https://on-eta.com",
        "https://www.on-eta.com",
        "https://13th-gongmozip-fe.vercel.app"
        ));

        // GET, POST, PUT, DELETE 등 모든 HTTP 메서드를 허용합니다.
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // Authorization(JWT 토큰 헤더), Content-Type 등 모든 헤더를 허용합니다.
        configuration.setAllowedHeaders(List.of("*"));

        // 자격 증명(쿠키, 인증 헤더 등)을 허용합니다.
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // 모든 URL 패턴에 대해 위 규칙을 적용

        return source;
    }

}
