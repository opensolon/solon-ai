package org.noear.solon.ai.sandbox.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class HostUtilsTest {

    // === isIPAddress ===

    @Test
    public void testIsIPAddress_ValidIPv4_127() {
        assertTrue(HostUtils.isIPAddress("127.0.0.1"));
    }

    @Test
    public void testIsIPAddress_ValidIPv4_10() {
        assertTrue(HostUtils.isIPAddress("10.0.0.1"));
    }

    @Test
    public void testIsIPAddress_InvalidIPv4_outOfRange() {
        assertFalse(HostUtils.isIPAddress("256.0.0.1"));
    }

    @Test
    public void testIsIPAddress_NotAnIP() {
        assertFalse(HostUtils.isIPAddress("not.an.ip"));
    }

    @Test
    public void testIsIPAddress_ShorthandThreeParts() {
        assertFalse(HostUtils.isIPAddress("1.2.3"));
    }

    @Test
    public void testIsIPAddress_ValidIPv6_loopback() {
        assertTrue(HostUtils.isIPAddress("::1"));
    }

    @Test
    public void testIsIPAddress_ValidIPv6_linkLocal() {
        assertTrue(HostUtils.isIPAddress("fe80::1"));
    }

    @Test
    public void testIsIPAddress_NotIPv6() {
        assertFalse(HostUtils.isIPAddress("not-ipv6"));
    }

    @Test
    public void testIsIPAddress_Null() {
        assertFalse(HostUtils.isIPAddress(null));
    }

    @Test
    public void testIsIPAddress_OctalRejected() {
        assertFalse(HostUtils.isIPAddress("010.0.0.1"));
    }

    @Test
    public void testIsIPAddress_LeadingZeroRejected() {
        assertFalse(HostUtils.isIPAddress("01.0.0.1"));
    }

    // === stripBrackets ===

    @Test
    public void testStripBrackets_IPv6() {
        assertEquals("::1", HostUtils.stripBrackets("[::1]"));
    }

    @Test
    public void testStripBrackets_NoBrackets() {
        assertEquals("example.com", HostUtils.stripBrackets("example.com"));
    }

    @Test
    public void testStripBrackets_Null() {
        assertNull(HostUtils.stripBrackets(null));
    }

    @Test
    public void testStripBrackets_OnlyOpenBracket() {
        assertEquals("[example.com", HostUtils.stripBrackets("[example.com"));
    }

    @Test
    public void testStripBrackets_Empty() {
        assertEquals("", HostUtils.stripBrackets(""));
    }

    // === canonicalizeHost ===

    @Test
    public void testCanonicalizeHost_NormalDomain() {
        assertEquals("example.com", HostUtils.canonicalizeHost("example.com"));
    }

    @Test
    public void testCanonicalizeHost_Null() {
        assertNull(HostUtils.canonicalizeHost(null));
    }

    @Test
    public void testCanonicalizeHost_Empty() {
        assertNull(HostUtils.canonicalizeHost(""));
    }

    @Test
    public void testCanonicalizeHost_ValidIPv4() {
        assertEquals("127.0.0.1", HostUtils.canonicalizeHost("127.0.0.1"));
    }

    @Test
    public void testCanonicalizeHost_ShorthandRejected() {
        assertNull(HostUtils.canonicalizeHost("127.1"));
    }

    @Test
    public void testCanonicalizeHost_DecimalShorthandReturnsHost() {
        // Pure numeric without dots/colons goes through URI parser
        // URI parser typically returns the numeric string as host
        String result = HostUtils.canonicalizeHost("2852039166");
        assertNotNull(result);
    }

    @Test
    public void testCanonicalizeHost_OctalRejected() {
        assertNull(HostUtils.canonicalizeHost("010.0.0.1"));
    }

    @Test
    public void testCanonicalizeHost_ControlCharRejected() {
        assertNull(HostUtils.canonicalizeHost("exam\u0001ple.com"));
    }

    @Test
    public void testCanonicalizeHost_PercentRejected() {
        assertNull(HostUtils.canonicalizeHost("example%2f.com"));
    }

    @Test
    public void testCanonicalizeHost_IPv6Brackets() {
        assertEquals("::1", HostUtils.canonicalizeHost("[::1]"));
    }

    @Test
    public void testCanonicalizeHost_IPv6Bare() {
        assertEquals("::1", HostUtils.canonicalizeHost("::1"));
    }

    @Test
    public void testCanonicalizeHost_IPv6Full() {
        assertEquals("fe80::1", HostUtils.canonicalizeHost("fe80::1"));
    }

    // === isValidHost ===

    @Test
    public void testIsValidHost_NormalDomain() {
        assertTrue(HostUtils.isValidHost("example.com"));
    }

    @Test
    public void testIsValidHost_Localhost() {
        assertTrue(HostUtils.isValidHost("localhost"));
    }

    @Test
    public void testIsValidHost_Null() {
        assertFalse(HostUtils.isValidHost(null));
    }

    @Test
    public void testIsValidHost_Empty() {
        assertFalse(HostUtils.isValidHost(""));
    }

    @Test
    public void testIsValidHost_TooLong() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) {
            sb.append('a');
        }
        assertFalse(HostUtils.isValidHost(sb.toString()));
    }

    @Test
    public void testIsValidHost_WithinMaxLength() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 255; i++) {
            sb.append('a');
        }
        assertTrue(HostUtils.isValidHost(sb.toString()));
    }

    @Test
    public void testIsValidHost_ControlCharRejected() {
        assertFalse(HostUtils.isValidHost("exam\u0001ple.com"));
    }

    @Test
    public void testIsValidHost_PercentRejected() {
        assertFalse(HostUtils.isValidHost("example%.com"));
    }

    @Test
    public void testIsValidHost_ValidIPv4() {
        assertTrue(HostUtils.isValidHost("10.0.0.1"));
    }

    @Test
    public void testIsValidHost_IPv6InBrackets() {
        assertTrue(HostUtils.isValidHost("[::1]"));
    }

    @Test
    public void testIsValidHost_IPv6Bare() {
        assertTrue(HostUtils.isValidHost("::1"));
    }

    @Test
    public void testIsValidHost_DomainWithDash() {
        assertTrue(HostUtils.isValidHost("my-host.example.com"));
    }

    @Test
    public void testIsValidHost_DomainWithUnderscore() {
        assertTrue(HostUtils.isValidHost("my_host.example.com"));
    }

    @Test
    public void testIsValidHost_DomainWithSpaceRejected() {
        assertFalse(HostUtils.isValidHost("exam ple.com"));
    }
}
