package com.edrdog.archiverservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** events·alerts 를 소비해 ClickHouse 에 적재하는 archiver 서비스 (detector 와 독립). */
// 스케줄링은 아카이빙(EventArchiver) 때문에 켠다. 없으면 @Scheduled 가 조용히 안 돈다.
@SpringBootApplication
@EnableScheduling
public class ArchiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchiverApplication.class, args);
    }

    /** ClickHouse 컨테이너 시간대가 UTC 라 내보낼 날 계산도 UTC 로 맞춘다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
