package com.edrdog.responderservice.command;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AgentCommandRepository extends JpaRepository<AgentCommand, String> {

    List<AgentCommand> findByHostAndStatusAndCreatedAtAfterOrderByCreatedAt(String host, String status, Instant cutoff);

    boolean existsByHostAndCreatedAtAfter(String host, Instant since);

    /**
     * 상태가 from 일 때만 to 로 바꾼다. 바뀐 행 수(0 또는 1)를 돌려준다.
     * 조건부 갱신이라 여러 인스턴스가 같은 명령을 동시에 가져가도 한 곳만 1 을 받는다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentCommand c set c.status = :to, c.message = coalesce(:message, c.message) "
            + "where c.id = :id and c.status = :from")
    int transition(@Param("id") String id, @Param("from") String from,
                   @Param("to") String to, @Param("message") String message);
}
