package com.gd.rain.tenancy.event

import com.gd.rain.event.EventBytes
import com.gd.rain.event.EventNamespace
import com.gd.rain.tenancy.TenantScope
import java.nio.ByteBuffer

/**
 * The sole tenancy-to-event namespace derivation. It includes the epoch, so restore/reprovision
 * never revives prior streams merely because the application reused a tenant reference.
 */
public object TenantEventNamespace {
    public fun of(scope: TenantScope): EventNamespace =
        EventNamespace.derive(
            EventBytes.utf8("rain.tenancy-event.namespace.v1"),
            EventBytes.of(scope.namespaceSeed()),
            EventBytes.of(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(scope.epoch.value).array()),
        )
}
