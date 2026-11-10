package com.edrdog.apiservice.selftest.web;

import com.edrdog.apiservice.access.AccessInterceptor;
import com.edrdog.apiservice.access.RequiresRole;
import com.edrdog.apiservice.access.Role;
import com.edrdog.apiservice.auth.Principal;
import com.edrdog.apiservice.selftest.SelfTestRun;
import com.edrdog.apiservice.selftest.SelfTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 서버 자체시험·무결성 검증 결과 조회와 요청(KISA EDR-BF-10, BF-11).
 * 서버 전체 기능이라 tenant 가 없다. 세션 Bearer 로 로그인한 admin 만 쓸 수 있다(/api/operations 는 X-API-Key 예외).
 */
@RestController
@RequestMapping("/api/operations/selftest")
@Tag(name = "operations", description = "서버 자체시험·무결성 검증 결과 조회와 즉시 실행")
@RequiresRole(Role.ADMIN)
public class SelfTestController {

    private static final int MAX_LIMIT = 100;

    private final SelfTestService service;

    public SelfTestController(SelfTestService service) {
        this.service = service;
    }

    @Operation(summary = "자체시험·무결성 검증 결과 목록",
            description = "최근 실행을 최신순으로 항목별 결과와 함께 준다. limit 기본 20, 최대 100. "
                    + "다른 조직 관리자가 요청한 실행의 requestedBy 는 이메일 대신 고정 문구로 가린다.")
    @GetMapping
    public List<SelfTestRunResponse> list(@RequestParam(defaultValue = "20") int limit,
                                          @RequestAttribute(AccessInterceptor.PRINCIPAL) Principal p) {
        return service.recent(Math.max(1, Math.min(limit, MAX_LIMIT))).stream()
                .map(r -> SelfTestRunResponse.from(r, p.tenantId())).toList();
    }

    @Operation(summary = "자체시험·무결성 검증 즉시 실행", description = "지금 한 번 돌리고 그 결과를 201 로 준다.")
    @PostMapping
    public ResponseEntity<SelfTestRunResponse> run(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal p) {
        SelfTestRun run = service.run(SelfTestRun.Trigger.MANUAL, p);
        return ResponseEntity.status(HttpStatus.CREATED).body(SelfTestRunResponse.from(run, p.tenantId()));
    }
}
