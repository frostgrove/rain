package fixture.app

import fixture.rain.beta.Public
import fixture.rain.beta.internal.Hidden

class UsesRainInternal(
    val hidden: Hidden,
)

class UsesRainPublic(
    val public: Public,
)
