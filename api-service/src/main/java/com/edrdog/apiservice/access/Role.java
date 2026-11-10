package com.edrdog.apiservice.access;

/**
 * 조직 계정의 역할. 위로 갈수록 권한이 크다(admin 은 viewer 가 하는 일을 다 한다).
 * DB 에는 소문자 문자열로 저장돼 있다(users.role).
 */
public enum Role {
    VIEWER("viewer"),
    ADMIN("admin");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** 저장된 문자열을 역할로 읽는다. 모르는 값이면 null(아무 권한도 없는 것으로 다룬다). */
    public static Role of(String value) {
        for (Role r : values()) {
            if (r.value.equals(value)) {
                return r;
            }
        }
        return null;
    }

    /** 이 역할이 required 이상의 권한인지. */
    public boolean atLeast(Role required) {
        return compareTo(required) >= 0;
    }
}
