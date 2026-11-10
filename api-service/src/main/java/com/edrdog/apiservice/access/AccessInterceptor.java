package com.edrdog.apiservice.access;

import com.edrdog.apiservice.audit.AuditEntry;
import com.edrdog.apiservice.audit.AuditResult;
import com.edrdog.apiservice.audit.AuditService;
import com.edrdog.apiservice.audit.AuditType;
import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.auth.Principal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequiresRole} 이 붙은 핸들러 앞에서 로그인(401), 조직의 허용 IP(403), 역할(403)을 한곳에서 본다.
 * 컨트롤러가 각자 확인하던 것을 한 번 더 겹쳐 보는 것이 아니라, 이 관문을 못 지난 요청은 컨트롤러에 닿지 않는다.
 * 통과하면 로그인한 사람을 요청 속성({@link #PRINCIPAL})에 실어 둔다.
 */
@Component
public class AccessInterceptor implements HandlerInterceptor {

    public static final String PRINCIPAL = "edrdog.access.principal";   // 어노테이션 값에 쓰므로 상수여야 한다

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService auth;
    private final AdminIpService adminIps;
    private final AuditService audit;

    public AccessInterceptor(AuthService auth, AdminIpService adminIps, AuditService audit) {
        this.auth = auth;
        this.adminIps = adminIps;
        this.audit = audit;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresRole required = requiredRole(method);
        if (required == null) {
            return true;   // 인증 없는 경로. 의도한 것만 열려 있는지는 UrlAccessTest 가 본다.
        }
        Principal who = auth.resolve(bearerToken(request.getHeader("Authorization")));
        String what = request.getMethod() + " " + request.getRequestURI();
        String ip = request.getRemoteAddr();

        adminIps.requireAllowed(who, ip, what);

        Role role = Role.of(who.role());
        if (role == null || !role.atLeast(required.value())) {
            audit.record(AuditEntry.user(who, AuditType.ACCESS_DENIED, null, AuditResult.FAILURE,
                    "required=" + required.value().value() + " " + what, ip));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다");
        }
        request.setAttribute(PRINCIPAL, who);
        return true;
    }

    /** 메서드에 붙은 표시가 클래스에 붙은 것보다 우선한다. */
    private static RequiresRole requiredRole(HandlerMethod method) {
        RequiresRole onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresRole.class);
        return onMethod != null ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresRole.class);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }
}
