package com.edrdog.apiservice.notify;

import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.Tenant;
import com.edrdog.apiservice.auth.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 에이전트가 올린 자체시험(security_check)과 무결성 검증(integrity_check) 결과가 실패면 조직 Slack webhook 으로 알린다
 * (KISA BF-10.2, BF-11.2). 에이전트가 매 주기 같은 결과를 보내므로 (조직, host, 유형) 별로 상태가 바뀔 때만 보낸다:
 * 성공(또는 처음 보는 상태)에서 실패로 갈 때, 실패에서 성공으로 돌아올 때. 실패 중인 키만 메모리에 둔다.
 * 재기동하면 상태가 비므로 아직 실패 중인 기기는 다음 보고에서 한 번 더 알린다. 근거는 ADR 034.
 */
@Component
public class AgentCheckNotifier {

    private static final Logger log = LoggerFactory.getLogger(AgentCheckNotifier.class);

    // 유형 -> 통보 문구에 쓰는 이름
    private static final Map<String, String> CHECKS = Map.of(
            AuditType.SECURITY_CHECK, "보안 기능 자체시험",
            AuditType.INTEGRITY_CHECK, "무결성 검증");

    private final TenantRepository tenants;
    private final SlackWebhookClient slack;
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    public AgentCheckNotifier(TenantRepository tenants, SlackWebhookClient slack) {
        this.tenants = tenants;
        this.slack = slack;
    }

    /** 저장이 끝난 에이전트 감사기록을 받아 상태가 바뀐 것만 알린다. 어떤 실패도 던지지 않는다(저장은 이미 끝났다). */
    public void onRecorded(List<AuditEntry> entries) {
        for (AuditEntry e : entries) {
            String name = CHECKS.get(e.type());
            if (name == null || e.tenantId() == null) {
                continue;
            }
            String key = e.tenantId() + "|" + e.actor() + "|" + e.type();
            if (e.result() == AuditResult.FAILURE) {
                if (failing.add(key)) {
                    notify(e, "[EDRdog] 에이전트 " + name + " 실패: " + e.actor() + " (" + e.detail() + ")");
                }
            } else if (failing.remove(key)) {
                notify(e, "[EDRdog] 에이전트 " + name + " 회복: " + e.actor());
            }
        }
    }

    // webhook 이 없는 조직은 보내지 않는다. 응답을 늦추지 않게 따로 보내고, 실패해도 로그만 남긴다.
    private void notify(AuditEntry e, String text) {
        try {
            tenants.findById(e.tenantId()).map(Tenant::getSlackWebhookUrl)
                    .filter(url -> url != null && !url.isBlank())
                    .ifPresent(url -> CompletableFuture.runAsync(() -> send(e.tenantId(), url, text)));
        } catch (RuntimeException ex) {
            log.warn("에이전트 점검 통보 실패 tenant={} err={}", e.tenantId(), ex.toString());
        }
    }

    private void send(Long tenantId, String url, String text) {
        try {
            slack.send(url, text);
        } catch (RuntimeException ex) {
            log.warn("에이전트 점검 통보 실패 tenant={} err={}", tenantId, ex.toString());
        }
    }
}
