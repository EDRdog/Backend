package com.edrdog.apiservice.access;

import com.edrdog.apiservice.auth.AuthException;

import java.nio.charset.StandardCharsets;

/**
 * 관리 API 가 받는 문자열 입력의 길이와 허용 문자(KISA EDR-BF-19.1). 어기면 400 으로 거절한다.
 * 쿼리·경로 값은 {@link InputGuardInterceptor} 가 한꺼번에 보고, 요청 본문 값은 컨트롤러·서비스가 여기를 부른다.
 */
public final class InputRules {

    /** 쿼리 파라미터·경로 변수 값 한 개의 최대 길이. 가장 긴 정상 값(도메인 253자, 검색어 200자)이 들어간다. */
    public static final int MAX_PARAM_LENGTH = 256;

    private InputRules() {
    }

    /** 줄바꿈, NUL 같은 제어 문자가 있는지. 로그 위조, 헤더 분할, 문자열 끊김에 쓰이는 문자들이다. */
    public static boolean hasControlChars(String s) {
        return s.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    /** null 은 통과시킨다(필수 여부는 호출한 쪽 규칙이 본다). */
    public static void requireText(String field, String value, int maxLength) {
        if (value != null && (value.length() > maxLength || hasControlChars(value))) {
            throw AuthException.invalidInput(field + " 값이 너무 길거나 허용되지 않는 문자가 있습니다");
        }
    }

    /** 비밀번호처럼 BCrypt 가 앞 72바이트만 보는 값. 넘는 값은 조용히 잘리지 않게 거절한다. */
    public static void requirePassword(String value) {
        if (value != null && (value.getBytes(StandardCharsets.UTF_8).length > 72 || hasControlChars(value))) {
            throw AuthException.invalidInput("password 값이 너무 길거나 허용되지 않는 문자가 있습니다");
        }
    }
}
