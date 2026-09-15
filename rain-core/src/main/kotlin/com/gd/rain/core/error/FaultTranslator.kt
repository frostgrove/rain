package com.gd.rain.core.error

/**
 * Turns a failure a transport did not raise itself into a [Fault], or answers `null` when it has
 * nothing to say about it. Transports ask every translator in order and render the first answer; a
 * failure no translator answers is an internal failure.
 */
public fun interface FaultTranslator {
    public fun translate(failure: Throwable): Fault?
}
