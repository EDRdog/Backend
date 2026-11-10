package com.edrdog.apiservice.access;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 관리 IP 주소 정규화(순수). 단일 주소만 되고 범위 표기는 전부 null. */
class AdminIpAddressTest {

    @Test
    void singleIpv4StaysAsIs() {
        assertEquals("10.0.0.5", AdminIpAddress.normalize("10.0.0.5"));
        assertEquals("10.0.0.5", AdminIpAddress.normalize(" 10.0.0.5 "));
    }

    @Test
    void ipv6IsWrittenInTheCompressedLowercaseForm() {
        assertEquals("2001:db8::1", AdminIpAddress.normalize("2001:DB8:0:0:0:0:0:1"));
        assertEquals("::1", AdminIpAddress.normalize("0:0:0:0:0:0:0:1"));
    }

    @Test
    void ipv4MappedIpv6IsTheSameAddressAsTheIpv4() {
        assertEquals("1.2.3.4", AdminIpAddress.normalize("::ffff:1.2.3.4"));
    }

    @Test
    void rangesWildcardsNamesAndGarbageAreNull() {
        for (String bad : Arrays.asList("10.0.0.0/24", "::/0", "10.0.0.*", "10.0.0.1-10.0.0.9", "10.0.0.1,10.0.0.2",
                "10.0.0.1 10.0.0.2", "example.com", "localhost", "999.1.1.1", "01.2.3.4", "1.2.3", "fe80::1%eth0",
                "", "   ", null)) {
            assertNull(AdminIpAddress.normalize(bad), String.valueOf(bad));
        }
    }
}
