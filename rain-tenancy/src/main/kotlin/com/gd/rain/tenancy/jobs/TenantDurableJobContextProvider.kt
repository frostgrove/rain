package com.gd.rain.tenancy.jobs

import com.gd.rain.jobs.context.DurableJobContextBinding
import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextFragment
import com.gd.rain.jobs.context.DurableJobContextPermanentException
import com.gd.rain.jobs.context.DurableJobContextProvider
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextRestoreRequest
import com.gd.rain.jobs.context.DurableJobContextUnavailableException
import com.gd.rain.jobs.context.JobProducerPartition
import com.gd.rain.jobs.context.TenantBindingMode
import com.gd.rain.tenancy.DurableTenantIntent
import com.gd.rain.tenancy.DurableTenantToken
import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantAuthorityUnavailableException
import com.gd.rain.tenancy.TenantContext
import com.gd.rain.tenancy.TenantOperation

/**
 * The tenancy contribution to rain-jobs' neutral envelope.
 *
 * It is one provider, not a jobs-tenancy-i18n-event product: other bounded contexts can contribute
 * their own fragments to the same envelope without learning a tenant reference. The fragment is an
 * authority-signed token and its partition is a stable HMAC-derived digest, never a raw tenant id.
 */
public class TenantDurableJobContextProvider(
    private val authority: TenantAuthority,
) : DurableJobContextProvider {
    override val id: String = "tenant"
    override val providesRequiredBinding: Boolean = true

    override fun capture(request: DurableJobContextRequest): DurableJobContextCapture {
        if (request.bindingMode == TenantBindingMode.CENTRAL) return DurableJobContextCapture.Absent
        val scope =
            try {
                TenantContext.requireScope()
            } catch (_: IllegalStateException) {
                return DurableJobContextCapture.Absent
            }
        return try {
            authority.current(scope, TenantOperation.DURABLE)
            val token = authority.durableSeal(scope, intent(request))
            DurableJobContextCapture.Captured(
                DurableJobContextFragment.of(TOKEN_VERSION, token.copy()),
                JobProducerPartition.of(scope.namespaceSeed()),
            )
        } catch (failure: TenantAuthorityUnavailableException) {
            throw DurableJobContextUnavailableException("tenant authority is unavailable while a job is enqueued", failure)
        } catch (failure: IllegalArgumentException) {
            throw DurableJobContextPermanentException("tenant scope cannot be captured for a durable job", failure)
        } catch (failure: IllegalStateException) {
            throw DurableJobContextPermanentException("tenant scope cannot be captured for a durable job", failure)
        }
    }

    override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding {
        if (request.fragment.version != TOKEN_VERSION) {
            throw DurableJobContextPermanentException("tenant durable job context version ${request.fragment.version} is unsupported")
        }
        return try {
            val scope = authority.durableUnseal(DurableTenantToken(request.fragment.copy()), intent(request.request))
            val binding = TenantContext.bind(scope)
            DurableJobContextBinding(binding::close)
        } catch (failure: TenantAuthorityUnavailableException) {
            throw DurableJobContextUnavailableException("tenant authority is unavailable while a job is restored", failure)
        } catch (failure: IllegalArgumentException) {
            throw DurableJobContextPermanentException("tenant durable job context is invalid", failure)
        } catch (failure: IllegalStateException) {
            throw DurableJobContextPermanentException("tenant durable job context is invalid", failure)
        }
    }

    private fun intent(request: DurableJobContextRequest): DurableTenantIntent =
        DurableTenantIntent(
            request.namespace,
            request.definition,
            request.invocation.toString(),
            request.payloadDigest.copy(),
        )

    private companion object {
        const val TOKEN_VERSION: Int = 1
    }
}
