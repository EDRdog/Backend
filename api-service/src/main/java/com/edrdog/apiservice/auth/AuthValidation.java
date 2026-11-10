package com.edrdog.apiservice.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 회원가입/로그인 입력 검증(순수). 패스워드 규칙은 KISA EDR-BF-01.2 표를 그대로 옮긴 것이다(ADR 031).
 */
public final class AuthValidation {

    private static final int MIN_LENGTH = 9;
    /** 같은 문자 반복, 키보드 순차 입력으로 보는 길이. 이 길이 이상이면 거절한다. */
    private static final int RUN = 3;
    /** 키보드 줄(숫자줄, 윗줄, 가운뎃줄, 아랫줄, 시프트 숫자줄)과 그 역방향. 소문자 기준이다. */
    private static final List<String> KEY_ROWS = rowsWithReverse("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "!@#$%^&*()");

    private AuthValidation() {
    }

    // 영문·숫자와 . _ % + - 만 쓰는 단일 주소. 공백, 따옴표, 꺾쇠, 두 번째 @ 는 못 들어온다.
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");

    private static final int MAX_EMAIL_LENGTH = 254;   // users.email 칸(255)을 넘기면 가입이 500 이 된다

    public static boolean validEmail(String email) {
        return email != null && email.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(email).matches();
    }

    /** 패스워드가 규칙을 어기면 이유를, 지키면 null 을 돌려준다. email 은 계정(ID)과 같은지 볼 때 쓴다. */
    public static String passwordError(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "패스워드는 " + MIN_LENGTH + "자 이상이어야 합니다";
        }
        if (password.chars().noneMatch(c -> c >= '0' && c <= '9')
                || password.chars().noneMatch(c -> c >= 'A' && c <= 'Z')
                || password.chars().noneMatch(c -> c >= 'a' && c <= 'z')
                || password.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
            return "패스워드에는 숫자, 영문 대문자, 영문 소문자, 특수문자가 각각 1개 이상 들어가야 합니다";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (email != null && (lower.equals(email.toLowerCase(Locale.ROOT))
                || (email.contains("@") && lower.equals(email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT))))) {
            return "패스워드는 계정(ID)과 같을 수 없습니다";
        }
        for (int i = 0; i + RUN <= password.length(); i++) {
            if (password.charAt(i) == password.charAt(i + 1) && password.charAt(i) == password.charAt(i + 2)) {
                return "같은 문자나 숫자를 " + RUN + "번 이상 연속해서 쓸 수 없습니다";
            }
            String window = lower.substring(i, i + RUN);
            if (KEY_ROWS.stream().anyMatch(row -> row.contains(window))) {
                return "키보드에서 연속된 문자나 숫자를 " + RUN + "개 이상 순서대로 쓸 수 없습니다";
            }
        }
        return null;
    }

    private static List<String> rowsWithReverse(String... rows) {
        return Arrays.stream(rows)
                .flatMap(r -> Stream.of(r, new StringBuilder(r).reverse().toString()))
                .toList();
    }
}
