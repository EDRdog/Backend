package com.edrdog.apiservice.audit;

import java.util.regex.Pattern;

/**
 * 감사 사건 유형 상수. 사건 유형은 소문자 snake_case 문자열이고, 서버와 에이전트가 같은 칸을 쓴다.
 * 새 유형은 여기에 상수로 더한다. 에이전트가 보내는 유형은 에이전트 쪽이 정하고 서버는 형식({@link #FORMAT})만 본다.
 */
public final class AuditType {

    /** 허용하는 사건 유형의 모양. 길이 상한은 {@link AuditService#MAX_TYPE}. */
    public static final Pattern FORMAT = Pattern.compile("[a-z0-9_]{1,64}");

    // 이 묶음이 바로 남기는 사건
    public static final String LOGIN_SUCCESS = "login_success";
    public static final String LOGIN_FAILURE = "login_failure";
    public static final String LOGOUT = "logout";
    public static final String SIGNUP = "signup";
    public static final String KILL_REQUEST = "kill_request";
    public static final String AGENT_ENROLL = "agent_enroll";

    // 다른 묶음이 쓸 유형(상수만 둔다. 남기는 코드는 그 묶음이 넣는다)
    public static final String PASSWORD_CHANGE = "password_change";
    public static final String AUTH_LOCKOUT = "auth_lockout";
    public static final String SECURITY_POLICY_CHANGE = "security_policy_change";
    public static final String ADMIN_IP_BLOCK = "admin_ip_block";
    public static final String SESSION_LOCK = "session_lock";
    public static final String SESSION_TERMINATE = "session_terminate";
    public static final String SELF_TEST_RESULT = "self_test_result";
    public static final String INTEGRITY_CHECK_RESULT = "integrity_check_result";

    private AuditType() {
    }
}
