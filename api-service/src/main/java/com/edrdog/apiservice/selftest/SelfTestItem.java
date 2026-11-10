package com.edrdog.apiservice.selftest;

/**
 * 자체시험·무결성 검증 항목 하나의 결과. category 는 selftest 또는 integrity.
 * detail 은 관리자 화면에 그대로 나가므로 비밀값(키, 비밀번호, 예외 메시지)을 싣지 않는다.
 */
public record SelfTestItem(String category, String name, Status status, String detail) {

    public enum Status { PASS, FAIL, SKIP }

    public static final String SELFTEST = "selftest";
    public static final String INTEGRITY = "integrity";

    public static SelfTestItem pass(String category, String name, String detail) {
        return new SelfTestItem(category, name, Status.PASS, detail);
    }

    public static SelfTestItem fail(String category, String name, String detail) {
        return new SelfTestItem(category, name, Status.FAIL, detail);
    }

    public static SelfTestItem skip(String category, String name, String detail) {
        return new SelfTestItem(category, name, Status.SKIP, detail);
    }
}
