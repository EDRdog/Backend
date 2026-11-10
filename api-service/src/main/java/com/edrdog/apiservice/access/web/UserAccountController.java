package com.edrdog.apiservice.access.web;

import com.edrdog.apiservice.access.AccessInterceptor;
import com.edrdog.apiservice.access.InputRules;
import com.edrdog.apiservice.access.RequiresRole;
import com.edrdog.apiservice.access.Role;
import com.edrdog.apiservice.access.UserAccountService;
import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.Principal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 같은 조직에 viewer(읽기 전용) 계정을 만든다. admin 만 쓴다. 역할은 요청으로 못 정하고 항상 viewer 다. */
@RestController
@Tag(name = "access", description = "viewer 계정 생성 (admin 전용)")
public class UserAccountController {

    private final UserAccountService accounts;
    private final AuditService audit;

    public UserAccountController(UserAccountService accounts, AuditService audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    public record CreateUserRequest(String email, String password) {
    }

    public record UserResponse(Long id, String email, String role) {
    }

    @Operation(summary = "viewer 계정 생성",
            description = "로그인한 admin 의 조직에 viewer 계정을 만든다(201). 이메일 형식이나 비밀번호 규칙이 틀리면 400, "
                    + "이미 가입된 이메일이면 409. 본문에 role 을 실어도 무시하고 viewer 로 만든다.")
    @RequiresRole(Role.ADMIN)
    @PostMapping("/api/tenant/users")
    public ResponseEntity<UserResponse> createViewer(@RequestAttribute(AccessInterceptor.PRINCIPAL) Principal admin,
                                                     @RequestBody CreateUserRequest req, HttpServletRequest http) {
        InputRules.requireText("email", req.email(), 254);
        InputRules.requirePassword(req.password());
        AppUser created = accounts.createViewer(admin.tenantId(), req.email(), req.password());
        audit.record(AuditEntry.user(admin, AuditType.ACCOUNT_CREATE, created.getEmail(), AuditResult.SUCCESS,
                "role=" + created.getRole(), http.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new UserResponse(created.getId(), created.getEmail(), created.getRole()));
    }
}
