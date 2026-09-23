package com.OnETA.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        // HTTP 통신을 설정하는 팩토리 객체 생성
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // 밀리초(ms) 단위로 타임아웃 설정
        factory.setConnectTimeout(3000); // 연결 시도 시간 3초 제한
        factory.setReadTimeout(5000);    // 응답 대기 시간 5초 제한

        // 팩토리 설정을 적용하여 RestTemplate 반환
        return new RestTemplate(factory);
    }
}