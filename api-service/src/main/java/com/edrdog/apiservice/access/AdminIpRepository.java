package com.edrdog.apiservice.access;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminIpRepository extends JpaRepository<AdminIp, Long> {

    List<AdminIp> findByTenantIdOrderByIdAsc(Long tenantId);

    Optional<AdminIp> findByIdAndTenantId(Long id, Long tenantId);
}
