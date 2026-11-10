package com.edrdog.apiservice.access;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 접근통제 관문과 입력 검사를 컨트롤러 앞에 건다. 접근통제가 먼저라 로그인 안 한 요청은 입력 검사 전에 401 을 받는다. */
@Configuration
public class AccessConfig implements WebMvcConfigurer {

    private final AccessInterceptor access;
    private final InputGuardInterceptor inputGuard;

    public AccessConfig(AccessInterceptor access, InputGuardInterceptor inputGuard) {
        this.access = access;
        this.inputGuard = inputGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(access).addPathPatterns("/api/**");
        // /i/ 는 설치 토큰이 경로 변수로 오는 열린 경로라 값 길이만 거른다
        registry.addInterceptor(inputGuard).addPathPatterns("/api/**", "/i/**");
    }
}
