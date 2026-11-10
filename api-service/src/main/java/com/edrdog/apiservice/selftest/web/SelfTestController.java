package com.edrdog.apiservice.selftest.web;

import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.auth.Principal;
import com.edrdog.apiservice.selftest.SelfTestRun;
import com.edrdog.apiservice.selftest.SelfTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 서버 자체시험·무결성 검증 결과 조회와 요청(KISA EDR-BF-10, BF-11).
 * 서버 전체 기능이라 tenant 가 없다. 세션 Bearer 로 로그인한 admin 만 쓸 수 있다(/api/operations 는 X-API-Key 예외).
 */
@RestController
@RequestMapping("/api/operations/selftest")
@Tag(name = "operations", description = "서버 자체시험·무결성 검증 결과 조회와 즉시 실행")
public class SelfTestController {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_LIMIT = 100;

    private final SelfTestService service;
    private final AuthService auth;

    public SelfTestController(SelfTestService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @Operation(summary = "자체시험·무결성 검증 결과 목록",
            description = "최근 실행을 최신순으로 항목별 결과와 함께 준다. limit 기본 20, 최대 100.")
    @GetMapping
    public List<SelfTestRunResponse> list(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "20") int limit) {
        admin(authorization);
        return service.recent(Math.max(1, Math.min(limit, MAX_LIMIT))).stream().map(SelfTestRunResponse::from).toList();
    }

    @Operation(summary = "자체시험·무결성 검증 즉시 실행", description = "지금 한 번 돌리고 그 결과를 201 로 준다.")
    @PostMapping
    public ResponseEntity<SelfTestRunResponse> run(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        Principal p = admin(authorization);
        SelfTestRun run = service.run(SelfTestRun.Trigger.MANUAL, p.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(SelfTestRunResponse.from(run));
    }

    // 로그인(401) 다음에 admin 인지(403) 본다. 가입 계정은 지금 전부 admin 이다(ADR 029).
    private Principal admin(String authorization) {
        Principal p = auth.resolve(bearerToken(authorization));
        if (!"admin".equals(p.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자만 사용할 수 있습니다");
        }
        return p;
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
