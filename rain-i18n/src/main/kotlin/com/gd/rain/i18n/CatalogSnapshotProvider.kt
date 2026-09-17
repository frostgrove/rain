package com.gd.rain.i18n

/** Supplies the one immutable catalog snapshot a caller intends to use at this boundary. */
public fun interface CatalogSnapshotProvider {
    /** Never returns a partially compiled or untrusted candidate snapshot. */
    public fun current(): CatalogSnapshot
}
