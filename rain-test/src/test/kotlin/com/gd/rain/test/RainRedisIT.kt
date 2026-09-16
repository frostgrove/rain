package com.gd.rain.test

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/** One command in RESP, and the first line of the server's answer — enough to ask a server about its configuration. */
private fun RedisServer.ask(vararg command: String): String =
    Socket().use { socket ->
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.soTimeout = 5_000
        val written =
            buildString {
                append('*').append(command.size).append("\r\n")
                command.forEach {
                    append('$')
                        .append(it.toByteArray().size)
                        .append("\r\n")
                        .append(it)
                        .append("\r\n")
                }
            }
        socket.getOutputStream().write(written.toByteArray())
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        val first = checkNotNull(reader.readLine()) { "the server closed the connection" }
        if (!first.startsWith("*")) return@use first
        // CONFIG GET answers an array of name and value: the value is the fourth line.
        List(4) { checkNotNull(reader.readLine()) }.last()
    }

/** The Redis servers rain-test starts: each policy as the server itself reports it, shared once per JVM or started for one test. */
@Tag("integration")
class RainRedisIT {
    @Test
    fun `each shared server evicts as its policy says, and the silent one will not say`() {
        assertThat(RainRedis.shared(RedisPolicy.RETAINING).ask("CONFIG", "GET", "maxmemory-policy")).isEqualTo("noeviction")
        assertThat(RainRedis.shared(RedisPolicy.EVICTING).ask("CONFIG", "GET", "maxmemory-policy")).isEqualTo("allkeys-lru")
        val silent = RainRedis.shared(RedisPolicy.SILENT)
        assertThat(silent.ask("CONFIG", "GET", "maxmemory-policy")).startsWith("-ERR unknown command")
        assertThat(silent.ask("PING")).isEqualTo("+PONG")
    }

    @Test
    fun `a shared server is one per policy and refuses to be stopped by a test`() {
        val retaining = RainRedis.shared(RedisPolicy.RETAINING)

        assertThat(RainRedis.shared(RedisPolicy.RETAINING)).isSameAs(retaining)
        assertThat(retaining.shared).isTrue()
        assertThat(retaining.springProperties())
            .containsExactly("spring.data.redis.host=${retaining.host}", "spring.data.redis.port=${retaining.port}")
        assertThatThrownBy { retaining.close() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("the shared retaining Redis server lives as long as the test JVM")
        assertThat(retaining.ask("PING")).isEqualTo("+PONG")
    }

    @Test
    fun `a server of a test's own is stopped by that test`() {
        val own = RainRedis.start(RedisPolicy.RETAINING)
        val host = own.host
        val port = own.port

        assertThat(own.shared).isFalse()
        assertThat(own.policy).isEqualTo(RedisPolicy.RETAINING)
        assertThat(own.ask("PING")).isEqualTo("+PONG")
        own.close()

        assertThatThrownBy { Socket().use { it.connect(InetSocketAddress(host, port), 2_000) } }.isInstanceOf(IOException::class.java)
    }
}
