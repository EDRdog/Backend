package com.edrdog.apiservice.selftest;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SelfTestRunRepository extends JpaRepository<SelfTestRun, Long> {

    List<SelfTestRun> findAllByOrderByIdDesc(Pageable pageable);

    Optional<SelfTestRun> findFirstByOrderByIdDesc();
}
