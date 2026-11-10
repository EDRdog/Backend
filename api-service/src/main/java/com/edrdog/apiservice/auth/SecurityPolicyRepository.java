package com.edrdog.apiservice.auth;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SecurityPolicyRepository extends JpaRepository<SecurityPolicy, Long> {
}
