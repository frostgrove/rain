package com.gd.rain.core.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Loopback by literal address and by RFC 6761 name, never by lookup or by resemblance. */
class LoopbackTest {
    @Test
    fun `loopback literals and the localhost names are loopback`() {
        listOf("127.0.0.1", "127.9.9.9", "::1", "[::1]", "localhost", "LOCALHOST.", "app.localhost").forEach {
            assertThat(Loopback.isLoopbackHost(it)).describedAs(it).isTrue()
        }
    }

    @Test
    fun `other literals and names are not, however they look`() {
        listOf("10.0.0.1", "[::2]", "0.0.0.0", "example.com", "localhost.example.com", "my-localhost", "127.0.0.1.nip.io", "").forEach {
            assertThat(Loopback.isLoopbackHost(it)).describedAs(it).isFalse()
        }
    }
}
