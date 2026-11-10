package com.edrdog.apiservice.access;

import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.auth.Principal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 조직별 관리 IP 허용 목록(KISA EDR-BF-07). 목록이 비어 있으면 제한이 없고, 있으면 그 IP 에서 온 요청만 통과한다.
 * 접속 IP 는 호출한 쪽이 {@code request.getRemoteAddr()} 로 넘긴다. X-Forwarded-For 는 읽지 않는다(ADR 033).
 */
@Service
public class AdminIpService {

    private final AdminIpRepository repo;
    private final AuditService audit;

    public AdminIpService(AdminIpRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AdminIp> list(Long tenantId) {
        return repo.findByTenantIdOrderByIdAsc(tenantId);
    }

    /** 이 접속 IP 가 조직의 허용 목록에 드는지. 목록이 비어 있으면 누구나 true. */
    @Transactional(readOnly = true)
    public boolean isAllowed(Long tenantId, String remoteAddr) {
        List<AdminIp> ips = repo.findByTenantIdOrderByIdAsc(tenantId);
        if (ips.isEmpty()) {
            return true;
        }
        String ip = AdminIpAddress.normalize(remoteAddr);
        return ip != null && ips.stream().anyMatch(a -> a.getIp().equals(ip));
    }

    /** 허용 밖이면 admin_ip_block 을 남기고 403 으로 거절한다. what 은 무엇을 하려 했는지(감사기록 상세). */
    public void requireAllowed(Principal who, String remoteAddr, String what) {
        if (isAllowed(who.tenantId(), remoteAddr)) {
            return;
        }
        audit.record(AuditEntry.user(who, AuditType.ADMIN_IP_BLOCK, null, AuditResult.FAILURE, what, remoteAddr));
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "허용되지 않은 IP 입니다");
    }

    /** 단일 IP 를 등록한다. 비어 있던 목록의 첫 IP 가 요청자 본인 IP 가 아니면 이 조직이 전부 잠기므로 거절한다. */
    @Transactional
    public AdminIp add(Principal admin, String rawIp, String requesterAddr) {
        String ip = AdminIpAddress.normalize(rawIp);
        if (ip == null) {
            throw AuthException.invalidInput("IP 는 단일 주소(IPv4 또는 IPv6)만 등록할 수 있습니다. 범위, CIDR, 와일드카드, 이름은 안 됩니다");
        }
        List<AdminIp> current = repo.findByTenantIdOrderByIdAsc(admin.tenantId());
        if (current.stream().anyMatch(a -> a.getIp().equals(ip))) {
            throw AuthException.duplicate("이미 등록된 IP 입니다");
        }
        if (current.isEmpty() && !ip.equals(AdminIpAddress.normalize(requesterAddr))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "처음 등록하는 IP 는 지금 접속한 IP 여야 합니다. 아니면 이 조직 계정이 모두 잠깁니다");
        }
        try {
            return repo.save(new AdminIp(admin.tenantId(), ip, Instant.now(), admin.email()));
        } catch (DataIntegrityViolationException e) {
            // 위 확인을 통과한 뒤 동시 요청이 같은 IP 를 먼저 넣은 경우
            throw AuthException.duplicate("이미 등록된 IP 입니다");
        }
    }

    /** IP 를 뺀다. 요청자 본인 IP 를 빼서 다른 IP 만 남기면 본인이 잠기므로 거절한다. 마지막 하나를 빼면 제한이 풀린다. */
    @Transactional
    public AdminIp remove(Principal admin, Long id, String requesterAddr) {
        AdminIp target = repo.findByIdAndTenantId(id, admin.tenantId())
                .orElseThrow(() -> AuthException.notFound("등록된 IP 가 아닙니다"));
        boolean othersRemain = repo.findByTenantIdOrderByIdAsc(admin.tenantId()).size() > 1;
        if (othersRemain && target.getIp().equals(AdminIpAddress.normalize(requesterAddr))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "지금 접속한 IP 는 다른 IP 가 남아 있는 동안 뺄 수 없습니다. 본인이 잠깁니다");
        }
        repo.delete(target);
        return target;
    }
}
