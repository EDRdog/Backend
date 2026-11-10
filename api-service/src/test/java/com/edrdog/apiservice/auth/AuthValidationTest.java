package com.edrdog.apiservice.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이메일/비밀번호 검증 규칙(순수).
 */
class AuthValidationTest {

    @Test
    void 이메일은_at_과_점을_모두_포함해야_유효() {
        assertTrue(AuthValidation.validEmail("a@b.com"));
        assertFalse(AuthValidation.validEmail("ab.com"));
        assertFalse(AuthValidation.validEmail("a@bcom"));
        assertFalse(AuthValidation.validEmail(null));
    }

    private static void assertAccepted(String password) {
        assertNull(AuthValidation.passwordError(password, "alice@edrdog.com"), password);
    }

    private static void assertRejected(String password) {
        assertNotNull(AuthValidation.passwordError(password, "alice@edrdog.com"), password);
    }

    @Test
    void emailMustBeASingleAsciiAddressOfBoundedLength() {
        assertTrue(AuthValidation.validEmail("first.last+tag@sub.edrdog.com"));
        assertFalse(AuthValidation.validEmail("a b@edrdog.com"));
        assertFalse(AuthValidation.validEmail("<x>@edrdog.com"));
        assertFalse(AuthValidation.validEmail("a'--@edrdog.com"));
        assertFalse(AuthValidation.validEmail("a@b@edrdog.com"));
        assertFalse(AuthValidation.validEmail("a@edrdog.com\n"));
        assertFalse(AuthValidation.validEmail("x".repeat(250) + "@edrdog.com"));
    }

    @Test
    void strongPasswordIsAccepted() {
        assertAccepted("Tr1cky#Pwd");
        assertAccepted("N3w#Valid-Pw");
        assertAccepted("Kd#77xPwQ9");   // 같은 문자 두 번은 허용
        assertAccepted("Hqw#9zLkP");    // 키보드 연속 두 글자는 허용
    }

    @Test
    void passwordNeedsAtLeastNineCharacters() {
        assertRejected("Ab1!xyzP");   // 8자
        assertRejected(null);
        assertRejected("");
    }

    @Test
    void passwordNeedsDigitUpperLowerAndSpecial() {
        assertRejected("Abcdefgh!x");   // 숫자 없음
        assertRejected("abcdefg1!x");   // 대문자 없음
        assertRejected("ABCDEFG1!X");   // 소문자 없음
        assertRejected("Abcdefgh1x");   // 특수문자 없음
    }

    @Test
    void passwordSameAsAccountIdIsRejected() {
        assertNotNull(AuthValidation.passwordError("Alice#Pw9x", "Alice#Pw9x@edrdog.com"));   // 로컬파트
        assertNotNull(AuthValidation.passwordError("Al1ce#Pw9x@edrdog.com", "al1ce#pw9x@edrdog.com"));   // 이메일 전체, 대소문자 무관
    }

    @Test
    void threeOrMoreIdenticalCharactersInARowAreRejected() {
        assertRejected("Kd#777xPwQ");
        assertRejected("Kd#7xPwQQQ");
        assertRejected("aaaKd#7xPw");
    }

    @Test
    void threeOrMoreKeyboardSequentialCharactersAreRejected() {
        assertRejected("Hqwe#9zLkP");   // 윗줄 정방향
        assertRejected("Hewq#9zLkP");    // 윗줄 역방향
        assertRejected("HASD#9zLkP");    // 가운뎃줄, 대소문자 무관
        assertRejected("Pm#9zxcLkT");    // 아랫줄
        assertRejected("Pm#123kLxZ");    // 숫자줄 정방향
        assertRejected("Pm#9873kLxZ");   // 숫자줄 역방향
        assertRejected("Pm#!@#kLxZ9");   // 시프트 숫자줄
    }
}
