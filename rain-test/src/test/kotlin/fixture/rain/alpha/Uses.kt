package fixture.rain.alpha

import fixture.rain.alpha.internal.OwnHidden
import fixture.rain.beta.Public
import fixture.rain.beta.autoconfigure.BetaAutoConfiguration
import fixture.rain.beta.internal.Hidden

class UsesBetaInternal(
    val hidden: Hidden,
)

class UsesBetaPublicAndOwnInternal(
    val public: Public,
    val own: OwnHidden,
)

class UsesBetaAutoConfiguration(
    val configuration: BetaAutoConfiguration,
)

class Sleeps {
    fun pause() {
        Thread.sleep(1)
    }
}
