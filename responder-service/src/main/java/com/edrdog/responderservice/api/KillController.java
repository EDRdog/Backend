package com.edrdog.responderservice.api;

import com.edrdog.responderservice.response.ExecuteResult;
import com.edrdog.responderservice.response.ResponseExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 반자동 실제 조치 트리거 API. 대시보드의 "실행" 버튼이 호출한다(자동 실행 아님).
 * 방아쇠를 사람이 당기게 두는 자리다. 자동 실행으로 바꾸면 오탐이 그대로 조치가 된다.
 *
 * <p>kill 은 명령만 넣고 202 로 돌아온다. 결과는 GET 으로 조회한다. 요청 스레드가 하트비트를 기다리지 않는다.
 */
@RestController
@RequestMapping("/api/responder/kill")
public class KillController {

    private final ResponseExecutor executor;

    public KillController(ResponseExecutor executor) {
        this.executor = executor;
    }

    /** host 의 target 프로세스 kill 명령을 넣는다. 명령이 나가면 202, 스위치·쿨다운·실패로 끝나면 200. */
    @PostMapping
    public ResponseEntity<ExecuteResult> kill(@RequestBody KillRequest req) {
        if (isBlank(req.host()) || isBlank(req.target())) {
            return ResponseEntity.badRequest().build();
        }
        ExecuteResult result = executor.killProcess(req.host(), req.target());
        HttpStatus status = ResponseExecutor.PENDING.equals(result.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    /** 명령 결과 조회. PENDING 이면 아직 보고 전이다. */
    @GetMapping("/{executionId}")
    public ResponseEntity<ExecuteResult> result(@PathVariable String executionId) {
        return ResponseEntity.of(executor.result(executionId));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** kill 트리거 요청 본문. */
    public record KillRequest(String host, String target) {
    }
}
