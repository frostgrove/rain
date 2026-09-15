package com.gd.rain.jobs

import com.gd.rain.jobs.internal.execution.DefinitionGate
import com.gd.rain.jobs.internal.execution.JobExecution
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.Semaphore

/**
 * The db-scheduler execution handler runs on a pooled thread of a fixed-size pool: it must never park that thread
 * without a deadline. The gate answers without waiting, and the only wait is the bounded one for the attempt body.
 */
class NoParkingInTheHandlerTest {
    @Test
    fun `the execution handler and the gate park on nothing`() {
        val imported = ClassFileImporter().importClasses(JobExecution::class.java, DefinitionGate::class.java)

        classes()
            .that()
            .haveFullyQualifiedName(JobExecution::class.java.name)
            .or()
            .haveFullyQualifiedName(DefinitionGate::class.java.name)
            .should(parkOnNothing())
            .check(imported)
    }

    @Test
    fun `the rule fails a class that parks`() {
        assertThatThrownBy {
            classes().should(parkOnNothing()).check(ClassFileImporter().importClasses(ParkingFixture::class.java))
        }.isInstanceOf(AssertionError::class.java).hasMessageContaining("Semaphore.acquire")
    }

    private fun parkOnNothing(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("park on nothing without a deadline") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                item.methodCallsFromSelf
                    .filter { call -> "${call.target.owner.name}.${call.target.name}/${call.target.rawParameterTypes.size}" in PARKING }
                    .forEach { call -> events.add(SimpleConditionEvent(item, false, call.description)) }
            }
        }

    @Suppress("unused")
    internal class ParkingFixture {
        private val permits = Semaphore(1)

        fun run() {
            permits.acquire()
        }
    }

    private companion object {
        val PARKING =
            setOf(
                "java.util.concurrent.Semaphore.acquire/0",
                "java.util.concurrent.Semaphore.acquire/1",
                "java.util.concurrent.Semaphore.acquireUninterruptibly/0",
                "java.util.concurrent.locks.Lock.lock/0",
                "java.util.concurrent.locks.Lock.lockInterruptibly/0",
                "java.util.concurrent.locks.ReentrantLock.lock/0",
                "java.util.concurrent.locks.ReentrantLock.lockInterruptibly/0",
                "java.util.concurrent.CountDownLatch.await/0",
                "java.util.concurrent.Future.get/0",
                "java.util.concurrent.CompletableFuture.get/0",
                "java.util.concurrent.CompletableFuture.join/0",
                "java.lang.Thread.join/0",
                "java.lang.Object.wait/0",
                "java.lang.Thread.sleep/1",
            )
    }
}
