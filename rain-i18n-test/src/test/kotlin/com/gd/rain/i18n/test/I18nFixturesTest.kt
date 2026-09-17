package com.gd.rain.i18n.test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class I18nFixturesTest {
    @Test
    fun `fixture view and binding use the real catalog pipeline deterministically`() {
        val snapshot =
            I18nFixtures.snapshot(
                messages =
                    listOf(
                        FixtureMessage(
                            com.gd.rain.i18n
                                .MessageKey("fixture", "ready"),
                            "Ready",
                        ),
                    ),
            )

        assertThat(
            I18nFixtures
                .view(snapshot)
                .render(
                    I18nFixtures.message(
                        snapshot,
                        com.gd.rain.i18n
                            .MessageKey("fixture", "ready"),
                    ),
                ).text,
        ).isEqualTo("Ready")
    }
}
