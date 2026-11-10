package com.edrdog.apiservice.audit;

/** 감사사건의 결과. */
public enum AuditResult {
    SUCCESS,
    FAILURE;

    /** API 문자열("success"|"failure", 대소문자 무관)을 읽는다. 모르는 값이면 null. */
    public static AuditResult parse(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
