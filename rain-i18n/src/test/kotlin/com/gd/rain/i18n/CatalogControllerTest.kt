package com.gd.rain.i18n

import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CatalogControllerTest {
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

    @Test
    fun `stale head cannot win an aba activation`() {
        val first = snapshot("a", "one")
        val second = snapshot("b", "two")
        val controller = CatalogController(CatalogControllerSpec(first, clock = clock))
        val a = controller.current().head

        val b = (controller.activate(a, second) as CatalogTransition.Updated).head
        val restored = (controller.rollback(b, first.reference) as CatalogTransition.Updated).head

        assertThat(controller.activate(a, second)).isEqualTo(CatalogTransition.Conflict(restored))
    }

    @Test
    fun `pins retain an exact snapshot until expiry`() {
        val first = snapshot("a", "one")
        val second = snapshot("b", "two")
        val controller = CatalogController(CatalogControllerSpec(first, maxRetained = 1, clock = clock))
        val head = controller.current().head
        controller.activate(head, second)
        val pin = (controller.pin(first.reference, Duration.ofHours(1)) as CatalogPinResult.Pinned).pin

        assertThat(controller.prune()).isZero()
        assertThat(controller.snapshot(first.reference)).isSameAs(first)
        clock.advance(Duration.ofHours(1))
        assertThat(controller.prune()).isEqualTo(1)
        assertThat(controller.snapshot(first.reference)).isNull()
        assertThat(controller.release(pin)).isFalse()
    }

    @Test
    fun `same revision with different content is refused`() {
        val first = snapshot("same", "one")
        val changed = snapshot("same", "two")
        val controller = CatalogController(CatalogControllerSpec(first, clock = clock))

        assertThat(controller.activate(controller.current().head, changed))
            .isInstanceOf(CatalogTransition.Conflict::class.java)
    }

    @Test
    fun `rollback does not retain the new current snapshot twice`() {
        val first = snapshot("a", "one")
        val second = snapshot("b", "two")
        val controller = CatalogController(CatalogControllerSpec(first, maxRetained = 2, clock = clock))

        val afterActivation = (controller.activate(controller.current().head, second) as CatalogTransition.Updated).head
        controller.rollback(afterActivation, first.reference)

        assertThat(controller.prune()).isEqualTo(1)
        assertThat(controller.current().snapshot).isSameAs(first)
        assertThat(controller.snapshot(second.reference)).isNull()
    }

    @Test
    fun `configured runtime identity refuses a candidate before it can enter retention`() {
        val first = snapshot("a", "one")
        val foreign = snapshot("b", "two", "icu4j-79.1")
        val controller =
            CatalogController(
                CatalogControllerSpec(first, runtimeIdentity = CatalogRuntimeIdentity(icuClDrTzdbIdentity = "icu4j-78.3"), clock = clock),
            )

        assertThat(controller.activate(controller.current().head, foreign))
            .isEqualTo(CatalogTransition.Limit(CatalogLimitReason.INCOMPATIBLE_RUNTIME))
        assertThat(controller.snapshot(foreign.reference)).isNull()
    }

    private fun snapshot(
        revision: String,
        text: String,
        dataIdentity: String = "icu4j-78.3",
    ): CatalogSnapshot =
        (
            CatalogCompiler.compile(
                CatalogSpec(
                    identity = CatalogIdentity(revision, icuClDrTzdbIdentity = dataIdentity),
                    sourceLocale = LocaleTag.parse("en"),
                    localePolicy = LocalePolicy(setOf(LocaleTag.parse("en")), LocaleTag.parse("en")),
                    defaultZone = ZoneId.of("UTC"),
                    messages =
                        listOf(
                            MessageSpec(
                                MessageKey("app", "message"),
                                1,
                                text,
                                "Controller fixture",
                            ),
                        ),
                ),
            ) as CatalogCompilation.Compiled
        ).snapshot
}
