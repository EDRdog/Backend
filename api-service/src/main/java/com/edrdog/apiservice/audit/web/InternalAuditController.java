package com.edrdog.apiservice.audit.web;

import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.notify.AgentCheckNotifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

/**
 * 에이전트 감사기록을 받는 내부 API. collector 가 에이전트가 올린 것을 node_key 로 푼 tenantId, host 와 함께 넘긴다.
 * X-Internal-Key 로만 인증한다(프론트 X-API-Key 로는 열리지 않는다).
 */
@RestController
@Tag(name = "audit", description = "에이전트 감사기록 수신 (내부, X-Internal-Key)")
public class InternalAuditController {

    /** 한 번에 받는 사건 수 상한. 넘으면 배치 전체를 거절한다. */
    public static final int MAX_EVENTS = 100;

    private final AuditService audit;
    private final AgentCheckNotifier checkNotifier;
    private final byte[] internalKey;

    public InternalAuditController(AuditService audit, AgentCheckNotifier checkNotifier,
                                   @Value("${edrdog.internal.key}") String internalKey) {
        this.audit = audit;
        this.checkNotifier = checkNotifier;
        this.internalKey = internalKey.getBytes();
    }

    /** tenantId 와 host 는 collector 가 node_key 로 푼 값이다. events[].ts 는 epoch millis, result 는 success|failure. */
    public record AgentAuditRequest(Long tenantId, String host, List<Event> events) {
        public record Event(Long ts, String type, String result, String detail) {
        }
    }

    public record AgentAuditResponse(int accepted) {
    }

    @Operation(summary = "에이전트 감사기록 수신",
            description = "collector 가 호출한다(X-Internal-Key). 한 번에 최대 " + MAX_EVENTS + "건. "
                    + "한 건이라도 형식이 틀리면 400 으로 배치 전체를 받지 않는다(일부만 저장하지 않는다). "
                    + "type 은 소문자 snake_case(" + AuditService.MAX_TYPE + "자 이하), host " + AuditService.MAX_ACTOR
                    + "자 이하, detail 은 " + AuditService.MAX_DETAIL + "자를 넘으면 잘라서 저장한다. "
                    + "서버 시각보다 앞선 ts 는 받은 시각으로 맞춘다.")
    @PostMapping("/api/internal/audit")
    public AgentAuditResponse receive(
            @RequestHeader(name = "X-Internal-Key", required = false) String internalKeyHeader,
            @RequestBody AgentAuditRequest req) {
        requireInternalKey(internalKeyHeader);
        if (req.tenantId() == null || req.tenantId() <= 0) {
            throw AuthException.invalidInput("tenantId 가 필요합니다");
        }
        if (req.host() == null || req.host().isBlank() || req.host().length() > AuditService.MAX_ACTOR) {
            throw AuthException.invalidInput("host 는 1~" + AuditService.MAX_ACTOR + "자여야 합니다");
        }
        if (req.events() == null || req.events().isEmpty() || req.events().size() > MAX_EVENTS) {
            throw AuthException.invalidInput("events 는 1~" + MAX_EVENTS + "건이어야 합니다");
        }
        // 서버 시각보다 앞선 ts 는 받은 시각으로 맞춘다. 그대로 두면 조회의 기본 상한(요청 시각)에 걸려 안 보인다.
        long now = System.currentTimeMillis();
        List<AuditEntry> entries = req.events().stream().map(e -> toEntry(req, e, now)).toList();
        audit.recordAll(entries);
        // 자체시험·무결성 실패는 조직 webhook 으로 알린다. 저장 뒤에 부르고, 통보가 실패해도 응답은 그대로다.
        checkNotifier.onRecorded(entries);
        return new AgentAuditResponse(entries.size());
    }

    private static AuditEntry toEntry(AgentAuditRequest req, AgentAuditRequest.Event e, long now) {
        if (e == null || e.ts() == null || e.ts() <= 0) {
            throw AuthException.invalidInput("events[].ts 는 epoch millis 여야 합니다");
        }
        if (e.type() == null || !AuditType.FORMAT.matcher(e.type()).matches()) {
            throw AuthException.invalidInput("events[].type 은 소문자 snake_case " + AuditService.MAX_TYPE + "자 이하여야 합니다");
        }
        AuditResult result = AuditResult.parse(e.result());
        if (result == null) {
            throw AuthException.invalidInput("events[].result 는 success 또는 failure 여야 합니다");
        }
        return AuditEntry.agent(Instant.ofEpochMilli(Math.min(e.ts(), now)), req.tenantId(), req.host(),
                e.type(), result, e.detail());
    }

    // 프론트 X-API-Key 와 분리된 별도 키다. 상수 시간 비교로 키 길이·내용 추측을 어렵게 한다.
    private void requireInternalKey(String header) {
        if (header == null || !MessageDigest.isEqual(internalKey, header.getBytes())) {
            throw AuthException.unauthorized("유효한 X-Internal-Key 가 필요합니다");
        }
    }
}
