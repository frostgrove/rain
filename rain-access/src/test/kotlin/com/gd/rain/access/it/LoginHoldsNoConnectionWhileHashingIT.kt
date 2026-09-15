package com.gd.rain.access.it

import com.gd.rain.access.support.AccessDatabase
import com.gd.rain.access.support.DatabaseKit
import com.gd.rain.access.support.FakeHasher
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Gap 19: a password is verified outside any transaction, so a sign-in waiting on the hash holds no connection. */
@Tag("integration")
class LoginHoldsNoConnectionWhileHashingIT {
    @Test
    fun `while a sign-in verifies a password, the only connection of the pool serves somebody else`() {
        lateinit var pool: HikariDataSource
        val db =
            AccessDatabase.fresh("access_hashing_pool") { database ->
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = database.url
                        username = database.username
                        password = database.password
                        maximumPoolSize = 1
                        connectionTimeout = 2_000
                    },
                ).also { pool = it }
            }
        val verifying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hasher =
            FakeHasher(beforeVerify = {
                verifying.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "the verification was never released" }
            })
        val kit = DatabaseKit(db, hasher = hasher)
        val subject = kit.enrol(kit.agents, "ada@example.test")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val signIn = executor.submit(Callable { kit.signIn(subject, "ada@example.test") })
            assertThat(verifying.await(30, TimeUnit.SECONDS)).describedAs("the sign-in reached the hash").isTrue()

            assertThat(pool.hikariPoolMXBean.activeConnections).describedAs("connections held while hashing").isZero()
            assertThat(db.count("SELECT 1")).isEqualTo(1)

            release.countDown()
            val issued = signIn.get(30, TimeUnit.SECONDS)
            assertThat(db.sessions.findById(issued.session)).isNotNull()
        } finally {
            release.countDown()
            executor.shutdownNow()
            pool.close()
        }
    }
}
