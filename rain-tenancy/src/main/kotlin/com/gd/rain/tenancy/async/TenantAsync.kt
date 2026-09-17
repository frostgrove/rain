package com.gd.rain.tenancy.async

import com.gd.rain.tenancy.TenantContext
import org.springframework.core.task.TaskDecorator
import org.springframework.core.task.TaskExecutor
import java.util.concurrent.Callable

/**
 * Captures only an already-minted tenant scope at task submission and restores a clean worker
 * baseline after execution. It is for in-process work only; durable jobs use the signed jobs
 * context provider instead.
 */
public class TenantTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val carrier = TenantContext.captureIfBound()
        return Runnable { TenantContext.runAsync(carrier, runnable::run) }
    }

    /** The callable counterpart keeps `submit` paths from silently dropping the captured scope. */
    public fun <T> decorate(callable: Callable<T>): Callable<T> {
        val carrier = TenantContext.captureIfBound()
        return Callable { TenantContext.runAsync(carrier, callable::call) }
    }
}

/**
 * Explicit adapter for one application-selected executor. It deliberately does not register a
 * global post-processor or touch arbitrary executors, schedulers, or foreign dispatchers.
 */
public class TenantTaskExecutor(
    private val delegate: TaskExecutor,
    private val decorator: TenantTaskDecorator = TenantTaskDecorator(),
) : TaskExecutor {
    override fun execute(task: Runnable) {
        delegate.execute(decorator.decorate(task))
    }
}
