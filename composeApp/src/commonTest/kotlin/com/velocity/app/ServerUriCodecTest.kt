package com.velocity.app

import com.velocity.app.domain.ServerProfile
import com.velocity.app.domain.ServerUriCodec
import kotlin.test.*

class ServerUriCodecTest {

    @Test
    fun testParseStandardUri() {
        val uri = "velocity://a1b2c3d4e5f67890123456789abcdef0@useast.relay.velocity-multipath.net:51820?name=US%20East%20Relay&region=US%20East&mtu=1380"
        val result = ServerUriCodec.parse(uri)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("a1b2c3d4e5f67890123456789abcdef0", profile.pskHex)
        assertEquals("useast.relay.velocity-multipath.net", profile.host)
        assertEquals(51820, profile.port)
        assertEquals("US East Relay", profile.name)
        assertEquals("US East", profile.region)
        assertEquals(1380, profile.mtu)
        assertTrue(profile.isSelfHosted)
    }

    @Test
    fun testParseMinimalUriWithDefaultPortAndMtu() {
        val uri = "velocity://fedcba9876543210fedcba9876543210@192.168.1.100"
        val result = ServerUriCodec.parse(uri)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("fedcba9876543210fedcba9876543210", profile.pskHex)
        assertEquals("192.168.1.100", profile.host)
        assertEquals(51820, profile.port)
        assertEquals(1380, profile.mtu)
        assertEquals("192.168.1.100", profile.name)
    }

    @Test
    fun testParseIpv6Uri() {
        val uri = "velocity://11223344556677889900aabbccddeeff@[2001:db8::1]:51820?name=IPv6%20Relay"
        val result = ServerUriCodec.parse(uri)
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals("2001:db8::1", profile.host)
        assertEquals(51820, profile.port)
        assertEquals("IPv6 Relay", profile.name)
    }

    @Test
    fun testParseInvalidSchemeFails() {
        val uri = "wireguard://psk@host:51820"
        val result = ServerUriCodec.parse(uri)
        assertTrue(result.isFailure)
    }

    @Test
    fun testParseMissingPskFails() {
        val uri = "velocity://host:51820"
        val result = ServerUriCodec.parse(uri)
        assertTrue(result.isFailure)
    }

    @Test
    fun testParseShortPskFails() {
        val uri = "velocity://short@host:51820"
        val result = ServerUriCodec.parse(uri)
        assertTrue(result.isFailure)
    }

    @Test
    fun testRoundtripSerialization() {
        val original = ServerProfile(
            id = "test-relay",
            name = "Singapore Hub",
            host = "sg.velocity.io",
            port = 51822,
            pskHex = "0123456789abcdef0123456789abcdef",
            region = "Asia Pacific",
            mtu = 1400,
        )
        val serialized = ServerUriCodec.serialize(original)
        assertTrue(serialized.startsWith("velocity://0123456789abcdef0123456789abcdef@sg.velocity.io:51822?"))

        val parsed = ServerUriCodec.parse(serialized).getOrThrow()
        assertEquals(original.host, parsed.host)
        assertEquals(original.port, parsed.port)
        assertEquals(original.pskHex, parsed.pskHex)
        assertEquals(original.name, parsed.name)
        assertEquals(original.region, parsed.region)
        assertEquals(original.mtu, parsed.mtu)
    }

    @Test
    fun testDockerSnippetGeneration() {
        val profile = ServerProfile(
            id = "test-docker",
            name = "Test Docker",
            host = "10.0.0.1",
            port = 51820,
            pskHex = "abcdef0123456789abcdef0123456789",
            mtu = 1380,
        )
        val snippet = ServerUriCodec.dockerSnippet(profile)
        assertTrue(snippet.contains("--cap-add=NET_ADMIN"))
        assertTrue(snippet.contains("-p 51820:51820/udp"))
        assertTrue(snippet.contains("PSK=abcdef0123456789abcdef0123456789"))
        assertTrue(snippet.contains("MONTHLY_LIMIT_GB=10240"))
        assertTrue(snippet.contains("velocity-relay:latest"))

        val oci = ServerUriCodec.oracleCloudSnippet(profile)
        assertTrue(oci.contains("MONTHLY_LIMIT_GB=10240"))
        assertTrue(oci.contains("install-oci.sh"))
    }

    @Test
    fun testLinuxCliSnippetGeneration() {
        val profile = ServerProfile(
            id = "test-linux",
            name = "Test Linux",
            host = "10.0.0.1",
            port = 51820,
            pskHex = "abcdef0123456789abcdef0123456789",
            mtu = 1380,
        )
        val snippet = ServerUriCodec.linuxCliSnippet(profile)
        assertTrue(snippet.contains("net.ipv4.ip_forward=1"))
        assertTrue(snippet.contains("./velocity-relay"))
        assertTrue(snippet.contains("--listen 0.0.0.0:51820"))
        assertTrue(snippet.contains("--mtu 1380"))
    }
}
