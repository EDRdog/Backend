package com.edrdog.responderservice.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /api/responder/** 의 단일 인증 지점(KISA H5). X-Internal-Key 가 설정값과 같을 때만 컨트롤러로 보내고,
 * 아니면 일을 하기 전에 401 로 끊는다. 호출자는 collector-service 와 api-service 뿐이다. 프로브(/actuator)는 건드리지 않는다.
 */
@Component
public class InternalKeyFilter extends OncePerRequestFilter {

    private final byte[] internalKey;

    public InternalKeyFilter(@Value("${edrdog.internal.key}") String internalKey) {
        this.internalKey = internalKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/responder/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("X-Internal-Key");
        // 상수 시간 비교. 값이 길이나 앞부분으로 새지 않게 한다
        if (header == null || !MessageDigest.isEqual(internalKey, header.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "유효한 X-Internal-Key 가 필요합니다");
            return;
        }
        chain.doFilter(request, response);
    }
}
