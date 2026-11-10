package com.edrdog.apiservice.auth.web;

import com.edrdog.apiservice.audit.AuditActor;
import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.auth.AuthResult;
import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.auth.Principal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이메일+비밀번호 기반 회원가입/로그인/로그아웃/내정보. 자체 세션 토큰(Bearer)을 사용한다.
 * 이 경로는 X-API-Key 예외(ApiKeyPolicy)라 토큰만으로 접근한다.
 * 가입·로그인·로그아웃은 감사기록을 남긴다(본 작업이 끝난 뒤에 남기므로 AuthService 의 트랜잭션과 섞이지 않는다).
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth", description = "회원가입/로그인/로그아웃 (세션 토큰)")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService auth;
    private final AuditService audit;

    public AuthController(AuthService auth, AuditService audit) {
        this.auth = auth;
        this.audit = audit;
    }

    @Operation(summary = "회원가입", description = "이메일/비밀번호(+선택 조직명)로 가입하고 세션 토큰을 발급한다.")
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody SignupRequest req, HttpServletRequest http) {
        AuthResult r = auth.signup(req.email(), req.password(), req.orgName());
        audit.record(new AuditEntry(null, r.tenantId(), AuditActor.USER, r.userId(), r.email(),
                AuditType.SIGNUP, r.email(), AuditResult.SUCCESS, null, http.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthResponse.from(r));
    }

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인하고 세션 토큰을 발급한다.")
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginRequest req, HttpServletRequest http) {
        AuthResult r;
        try {
            r = auth.login(req.email(), req.password());
        } catch (AuthException e) {
            auditLoginFailure(req.email(), http.getRemoteAddr());
            throw e;
        }
        audit.record(new AuditEntry(null, r.tenantId(), AuditActor.USER, r.userId(), r.email(),
                AuditType.LOGIN_SUCCESS, null, AuditResult.SUCCESS, null, http.getRemoteAddr()));
        return AuthResponse.from(r);
    }

    @Operation(summary = "로그아웃", description = "현재 토큰의 세션을 삭제한다(멱등).")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(name = "Authorization", required = false) String authorization,
                                       HttpServletRequest http) {
        String token = bearerToken(authorization);
        // 지우기 전에 누구 세션인지 읽어 둔다. 이미 무효한 토큰이면 남길 행위자가 없어 기록하지 않는다.
        Principal who = null;
        try {
            who = auth.resolve(token);
        } catch (AuthException ignored) {
            // 멱등 로그아웃: 무효 토큰도 204
        }
        auth.logout(token);
        if (who != null) {
            audit.record(AuditEntry.user(who, AuditType.LOGOUT, null, AuditResult.SUCCESS, null, http.getRemoteAddr()));
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "내 정보", description = "Bearer 토큰으로 현재 유저 정보를 조회한다.")
    @GetMapping("/me")
    public MeResponse me(@RequestHeader(name = "Authorization", required = false) String authorization) {
        Principal p = auth.resolve(bearerToken(authorization));
        return new MeResponse(p.userId(), p.tenantId(), p.email(), p.role());
    }

    /**
     * 가입된 이메일이면 그 tenant 의 기록으로, 아니면 tenant 없이 남긴다(tenant 없는 기록은 조회 API 에 안 나온다).
     * 가입 안 된 이메일은 입력값을 남기지 않는다. 비밀번호를 이메일 칸에 잘못 붙여 넣는 일이 있어서다.
     */
    private void auditLoginFailure(String email, String ip) {
        var known = auth.principalOfEmail(email);
        if (known.isPresent()) {
            audit.record(AuditEntry.user(known.get(), AuditType.LOGIN_FAILURE, null, AuditResult.FAILURE, null, ip));
            return;
        }
        audit.record(new AuditEntry(null, null, AuditActor.USER, null, "unknown",
                AuditType.LOGIN_FAILURE, null, AuditResult.FAILURE, null, ip));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
