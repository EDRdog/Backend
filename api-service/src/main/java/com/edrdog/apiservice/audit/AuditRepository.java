package com.edrdog.apiservice.audit;

import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * 감사기록 저장소. 일부러 {@code JpaRepository} 를 상속하지 않고 저장만 둔다.
 * 수정·삭제 메서드를 물려받지 않아 지우는 코드를 쓸 수 없다. 조회는 {@link AuditService#search}.
 */
public interface AuditRepository extends Repository<AuditRecord, Long> {

    AuditRecord save(AuditRecord record);

    List<AuditRecord> saveAll(Iterable<AuditRecord> records);
}
