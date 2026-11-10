package com.edrdog.detectorservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// detector 모듈 진입점. Kafka Streams 는 KafkaStreamsConfig 가 자동 기동. 룰 파일 리로드(DataRules)가 스케줄을 쓴다
@EnableScheduling
@SpringBootApplication
public class DetectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DetectorApplication.class, args);
    }
}
