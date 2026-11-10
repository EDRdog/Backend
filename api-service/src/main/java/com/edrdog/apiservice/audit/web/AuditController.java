package com.edrdog.apiservice.audit.web;

import com.edrdog.apiservice.access.RequiresRole;
import com.edrdog.apiservice.access.Role;
import com.edrdog.apiservice.audit.AuditFilter;
import com.edrdog.apiservice.audit.AuditPage;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.auth.Principal;
import com.edrdog.apiservice.query.QueryGuards;
import com.edrdog.apiservice.web.PageHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 감사기록 조회. 같은 tenant 의 admin 역할만 읽는다. 읽기 전용이고 수정·삭제 API 는 없다.
 * 세션 Bearer 로 인증하므로 이 경로는 X-API-Key 예외다(ApiKeyPolicy).
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "audit", description = "감사기록 조회 (admin 전용, 읽기 전용)")
@RequiresRole(Role.ADMIN)
public class AuditController {

    private static final String BEARER_PREFIX = "Bearer ";
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    private final AuditService audit;
    private final AuthService auth;

    public AuditController(AuditService audit, AuthService auth) {
        this.audit = audit;
        this.auth = auth;
    }

    @Operation(summary = "감사기록 조회",
            description = "로그인한 admin 의 tenant 기록만 조회한다(admin 이 아니면 403). "
                    + "필터: from/to(epoch millis, 양끝 포함), type(사건 유형), actor(행위자: 유저 이메일 또는 host), "
                    + "result(success|failure). 정렬: order=asc|desc(시각, 기본 desc). "
                    + "페이지: limit 기본 " + DEFAULT_LIMIT + ", 상한 " + MAX_LIMIT + ", offset 상한 " + QueryGuards.MAX_OFFSET
                    + ". 본문은 배열이고 페이지 정보는 헤더로 나간다(X-Has-More, X-Time-From/X-Time-To, "
                    + "withTotal=true 일 때만 X-Total-Count). 다음 페이지를 부를 때는 X-Time-To 를 to 에 그대로 실어야 "
                    + "그사이 쌓인 새 기록 때문에 offset 이 밀리지 않는다.")
    @GetMapping
    public ResponseEntity<List<AuditResponse>> list(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String result,
            @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false, defaultValue = "false") boolean withTotal) {
        Principal principal = auth.resolve(bearerToken(authorization));
        if (!order.equals("asc") && !order.equals("desc")) {
            throw AuthException.invalidInput("order 는 asc 또는 desc 여야 합니다: " + order);
        }
        AuditResult resultFilter = null;
        if (result != null) {
            resultFilter = AuditResult.parse(result);
            if (resultFilter == null) {
                throw AuthException.invalidInput("result 는 success 또는 failure 여야 합니다: " + result);
            }
        }
        // 상한 밖 offset 은 자르지 않고 거절한다(조용히 자르면 화면이 빈 페이지로 읽는다).
        if (offset != null && (offset < 0 || offset > QueryGuards.MAX_OFFSET)) {
            throw AuthException.invalidInput("offset 은 0.." + QueryGuards.MAX_OFFSET + " 여야 합니다: " + offset);
        }
        // to 를 안 준 첫 페이지를 여기서 고정해야 다음 페이지가 같은 범위를 본다.
        long resolvedTo = to != null ? to : System.currentTimeMillis();
        AuditPage page = audit.search(new AuditFilter(principal.tenantId(),
                from != null ? Instant.ofEpochMilli(from) : null, Instant.ofEpochMilli(resolvedTo),
                QueryGuards.hasText(type) ? type : null, QueryGuards.hasText(actor) ? actor : null, resultFilter,
                order.equals("asc"), QueryGuards.clampLimit(limit, DEFAULT_LIMIT, MAX_LIMIT),
                offset != null ? offset : 0, withTotal));
        return PageHeaders.body(page.items().stream().map(AuditResponse::from).toList(), page.hasMore(),
                page.total(), from != null ? from : 0L, resolvedTo);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
