package com.edrdog.apiservice.selftest;

import com.edrdog.apiservice.notify.SlackWebhookClient;
import com.edrdog.apiservice.operations.DependencyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 자체시험(BF-10)과 무결성 검증(BF-11)을 한 번에 돌려 결과를 저장한다. 기동 시, 주기, 관리자 요청 시 같은 경로를 탄다.
 * 실패하면 경고 로그, 운영 상태 화면의 selftest 항목, (설정했다면) Slack 통보로 알린다. 근거는 ADR 029.
 */
@Service
public class SelfTestService {

    private static final Logger log = LoggerFactory.getLogger(SelfTestService.class);

    private final SelfTestRunRepository runs;
    private final SelfTestChecks checks;
    private final IntegrityVerifier integrity;
    private final SlackWebhookClient slack;
    private final String webhookUrl;

    public SelfTestService(SelfTestRunRepository runs,
                           SelfTestChecks checks,
                           IntegrityVerifier integrity,
                           SlackWebhookClient slack,
                           @Value("${edrdog.selftest.notify-webhook-url:}") String webhookUrl) {
        this.runs = runs;
        this.checks = checks;
        this.integrity = integrity;
        this.slack = slack;
        this.webhookUrl = webhookUrl;
    }

    public SelfTestRun run(SelfTestRun.Trigger trigger, String requestedBy) {
        Instant startedAt = Instant.now();
        List<SelfTestItem> items = new ArrayList<>(checks.run());
        items.addAll(integrity.verify());

        // 저장 전에 직전 결과를 읽어 둬야 "새로 실패했는지" 비교할 수 있다.
        List<String> previouslyFailed = runs.findFirstByOrderByIdDesc()
                .map(r -> SelfTestRun.failedNames(r.getItems())).orElse(List.of());
        SelfTestRun run = runs.save(SelfTestRun.of(trigger, startedAt, requestedBy, items));

        List<String> failed = SelfTestRun.failedNames(items);
        if (!failed.isEmpty()) {
            log.warn("[SELFTEST-FAIL] trigger={} failed={}", trigger, failed);
            if (!failed.equals(previouslyFailed)) {
                notifyFailure(trigger, failed);
            }
        }
        return run;
    }

    @Scheduled(fixedDelayString = "${edrdog.selftest.interval:PT1H}",
            initialDelayString = "${edrdog.selftest.interval:PT1H}")
    void runScheduled() {
        runQuietly(SelfTestRun.Trigger.SCHEDULED);
    }

    @EventListener(ApplicationReadyEvent.class)
    void runOnStartup() {
        runQuietly(SelfTestRun.Trigger.STARTUP);
    }

    public List<SelfTestRun> recent(int limit) {
        return runs.findAllByOrderByIdDesc(PageRequest.of(0, limit));
    }

    /** 운영 상태 화면(operations/health)에 얹는 한 줄. 가장 최근 실행이 실패면 down. */
    public DependencyResult dependency() {
        try {
            return runs.findFirstByOrderByIdDesc()
                    .map(r -> r.getStatus() == SelfTestItem.Status.PASS
                            ? DependencyResult.up("selftest")
                            : DependencyResult.down("selftest",
                                    "자체시험 실패: " + String.join(", ", SelfTestRun.failedNames(r.getItems()))))
                    .orElseGet(() -> DependencyResult.unknown("selftest", "아직 실행된 적이 없다"));
        } catch (RuntimeException e) {
            return DependencyResult.unknown("selftest", "결과를 읽지 못했다");
        }
    }

    // 기동과 주기 실행이 예외로 죽으면 앱이 안 뜨거나 스케줄러가 멈춘다. 시험이 못 돈 것도 로그로 남긴다.
    private void runQuietly(SelfTestRun.Trigger trigger) {
        try {
            run(trigger, null);
        } catch (RuntimeException e) {
            log.error("[SELFTEST-ERROR] trigger={} {}", trigger, e.getClass().getSimpleName());
        }
    }

    private void notifyFailure(SelfTestRun.Trigger trigger, List<String> failed) {
        if (webhookUrl.isBlank()) {
            return;
        }
        try {
            slack.send(webhookUrl, "[EDRDOG] 서버 자체시험 실패 (" + trigger + "): " + String.join(", ", failed));
        } catch (RuntimeException e) {
            log.warn("[SELFTEST-NOTIFY-FAIL] {}", e.getClass().getSimpleName());
        }
    }
}
