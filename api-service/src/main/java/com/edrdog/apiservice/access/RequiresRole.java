package com.edrdog.apiservice.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 세션 Bearer 로 인증하는 관리 API 가 요구하는 최소 역할. 컨트롤러 클래스나 메서드에 붙인다(메서드가 우선).
 * {@link AccessInterceptor} 가 핸들러보다 먼저 로그인(401), 허용 IP(403), 역할(403)을 본다.
 * 이 표시가 없는 핸들러는 인증이 없는 경로로 취급되므로 {@code UrlAccessTest} 의 열린 경로 목록에 올라야 한다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresRole {

    Role value();

    /** true 면 패스워드 변경이 필요한 계정(KISA BF-08)도 통과시킨다. 내 정보와 패스워드 변경만 켠다. */
    boolean allowPasswordChange() default false;

    /** true 면 공개 데모 계정(`demo_account`)의 요청을 403 으로 거절한다. 비밀번호가 공개돼 있어 남이 상태를 바꾸면 안 되는 쓰기에 켠다. */
    boolean denyDemo() default false;
}
