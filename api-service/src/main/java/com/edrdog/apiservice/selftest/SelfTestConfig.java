package com.edrdog.apiservice.selftest;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 주기 자체시험(@Scheduled)을 켠다. api-service 에서 스케줄링을 쓰는 곳은 여기뿐이다. */
@Configuration
@EnableScheduling
public class SelfTestConfig {
}
