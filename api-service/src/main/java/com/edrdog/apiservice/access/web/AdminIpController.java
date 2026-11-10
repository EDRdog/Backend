package com.edrdog.apiservice.access.web;

import com.edrdog.apiservice.access.AccessInterceptor;
import com.edrdog.apiservice.access.AdminIp;
import com.edrdog.apiservice.access.AdminIpAddress;
import com.edrdog.apiservice.access.AdminIpService;
import com.edrdog.apiservice.access.RequiresRole;
import com.edrdog.apiservice.access.Role;
import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.Principal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 조직의 관리 IP 허용 목록 조회·등록·삭제(KISA EDR-BF-07). admin 만 쓴다. 변경은 감사기록(admin_ip_change)에 남는다.
 * 세션 Bearer 와 프론트 X-API-Key 를 함께 요구한다(/api/tenant/** 는 API 키 예외가 아니다).
 */
@RestController
@RequestMapping("/api/tenant/admin-ips")
@RequiresRole(Role.ADMIN)
@Tag(name = "access", description = "관리 IP 허용 목록 (admin 전용)")
public class AdminIpController {

    private final AdminIpService ips;
    private final AuditService audit;

    public AdminIpController(AdminIpService ips, AuditService audit) {
        this.ips = ips;
        this.audit = audit;
    }

    public record AdminIpRequest(String ip) {
    }

    public record AdminIpResponse(Long id, String ip, long createdAt, String createdBy) {
        static AdminIpResponse from(AdminIp a) {
            return new AdminIpResponse(a.getId(), a.getIp(), a.getCreatedAt().toEpochMilli(), a.getCreatedBy());
        }
    }

    /** yourIp 는 서버가 본 지금 요청의 접속 IP. 화면이 "내 IP 등록" 버튼에 채운다. */
    public record AdminIpListResponse(List<AdminIpResponse> allowedIps, String yourIp) {
    }

    @Operation(summary = "허용 IP 목록",
            description = "조직의 허용 IP 목록과 지금 요청의 접속 IP(yourIp). 목록이 비어 있으면 접속 제한이 없다.")
    @GetMapping
    public AdminIpListResponse list(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal admin,
                                    HttpServletRequest http) {
        String normalized = AdminIpAddress.normalize(http.getRemoteAddr());
        return new AdminIpListResponse(
                ips.list(admin.tenantId()).stream().map(AdminIpResponse::from).toList(),
                normalized != null ? normalized : http.getRemoteAddr());
    }

    @Operation(summary = "허용 IP 등록",
            description = "단일 IPv4/IPv6 주소만 받는다(범위, CIDR, 와일드카드, 이름은 400). 이미 있으면 409. "
                    + "목록이 비어 있을 때 첫 IP 는 지금 접속한 IP 여야 하며 아니면 409(이 조직이 모두 잠기므로). "
                    + "등록하는 순간부터 그 조직 계정의 로그인과 관리 API 는 목록 밖 IP 에서 403 이다.")
    @PostMapping
    public ResponseEntity<AdminIpResponse> add(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal admin,
                                               @RequestBody AdminIpRequest req, HttpServletRequest http) {
        AdminIp added = ips.add(admin, req.ip(), http.getRemoteAddr());
        audit.record(AuditEntry.user(admin, AuditType.ADMIN_IP_CHANGE, added.getIp(), AuditResult.SUCCESS,
                "add", http.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AdminIpResponse.from(added));
    }

    @Operation(summary = "허용 IP 삭제",
            description = "id 로 뺀다(204). 다른 조직의 id 나 없는 id 는 404. 다른 IP 가 남아 있는 동안 지금 접속한 IP 를 빼면 "
                    + "본인이 잠기므로 409. 마지막 하나를 빼면 목록이 비어 제한이 풀린다.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal admin,
                                       @PathVariable Long id, HttpServletRequest http) {
        AdminIp removed = ips.remove(admin, id, http.getRemoteAddr());
        audit.record(AuditEntry.user(admin, AuditType.ADMIN_IP_CHANGE, removed.getIp(), AuditResult.SUCCESS,
                "remove", http.getRemoteAddr()));
        return ResponseEntity.noContent().build();
    }
}
