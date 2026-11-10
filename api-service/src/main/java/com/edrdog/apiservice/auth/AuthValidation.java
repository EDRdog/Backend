package com.edrdog.apiservice.auth;

import java.util.regex.Pattern;

/**
 * 회원가입/로그인 입력 검증(순수). 해커톤 수준의 최소 규칙만 둔다.
 */
public final class AuthValidation {

    private AuthValidation() {
    }

    // 영문·숫자와 . _ % + - 만 쓰는 단일 주소. 공백, 따옴표, 꺾쇠, 두 번째 @ 는 못 들어온다.
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    private static final int MAX_EMAIL_LENGTH = 254;   // users.email 칸(255)을 넘기면 가입이 500 이 된다

    public static boolean validEmail(String email) {
        return email != null && email.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(email).matches();
    }

    public static boolean validPassword(String password) {
        return password != null && password.length() >= 8;
    }
}
