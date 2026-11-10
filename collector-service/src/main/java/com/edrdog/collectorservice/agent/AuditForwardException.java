package com.edrdog.collectorservice.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 감사기록을 api-service 로 넘기지 못했다. 503 이라 에이전트가 기록을 로그에 남겨 두고 다음 주기에 다시 보낸다. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class AuditForwardException extends RuntimeException {

    public AuditForwardException(Throwable cause) {
        super("감사기록 전달 실패", cause);
    }
}
