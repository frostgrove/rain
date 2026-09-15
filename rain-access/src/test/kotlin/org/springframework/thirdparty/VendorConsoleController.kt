package org.springframework.thirdparty

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A controller a library brings, living in a package whose name once exempted it from the surface verification. */
@RestController
class VendorConsoleController {
    @GetMapping("/vendor/console")
    fun console(): String = "console"
}
