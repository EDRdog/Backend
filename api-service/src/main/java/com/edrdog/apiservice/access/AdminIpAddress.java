package com.edrdog.apiservice.access;

import com.google.common.net.InetAddresses;

/**
 * 관리 IP 목록에 오르는 주소(순수). 단일 IPv4/IPv6 주소만 인정하고 한 가지 표기로 맞춘다.
 * 범위, CIDR, 와일드카드는 형식이 안 맞아 자연히 거절된다. Guava 는 글자 그대로의 주소만 읽고 이름 조회(DNS)를 하지 않는다.
 */
public final class AdminIpAddress {

    private AdminIpAddress() {
    }

    /** 단일 IP 면 정규화한 문자열(IPv6 는 소문자 압축형, IPv4 매핑 주소는 IPv4), 범위·이름·그 밖은 null. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        // 범위 id(fe80::1%eth0)는 같은 기기를 다른 글자로 쓰게 하므로 받지 않는다
        if (s.indexOf('%') >= 0 || !InetAddresses.isInetAddress(s)) {
            return null;
        }
        return InetAddresses.toAddrString(InetAddresses.forString(s));
    }
}
