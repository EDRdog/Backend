package com.edrdog.collectorservice.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 브로커 ack 를 못 받았다. 503 이라 에이전트가 배치를 버퍼로 되돌리고 다음 주기에 다시 보낸다. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class PublishException extends RuntimeException {

    public PublishException(Throwable cause) {
        super("events 발행 실패", cause);
    }
}
