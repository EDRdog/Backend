package com.edrdog.collectorservice.agent.repository;

import com.edrdog.collectorservice.agent.domain.AgentNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentNodeRepository extends JpaRepository<AgentNode, String> {

    /**
     * 같은 tenant 의 같은 host 재-enroll 시 노드를 재사용(무한 증식 방지).
     * 단건이 아니라 목록으로 받는다. 유니크 제약이 붙기 전에 생긴 중복 행이 있어도 조회부터 터지면
     * 재-enroll 로 정리할 기회조차 없어 그 호스트는 영영 등록하지 못한다.
     */
    List<AgentNode> findAllByTenantIdAndHostIdentifier(Long tenantId, String hostIdentifier);

    /** tenant 의 등록 노드 전체(api-service 호스트 목록 화면이 events 와 병합하는 데 쓴다). */
    List<AgentNode> findByTenantId(Long tenantId);
}
