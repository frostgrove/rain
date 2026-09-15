package fixture.rain.alpha.autoconfigure

import fixture.rain.beta.autoconfigure.BetaAutoConfiguration

class AlphaAutoConfiguration(
    val after: BetaAutoConfiguration,
)
