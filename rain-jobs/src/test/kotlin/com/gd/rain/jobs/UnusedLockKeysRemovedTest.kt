package com.gd.rain.jobs

import com.gd.rain.core.lock.LockKey
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Gap 9: rain-jobs derives no advisory lock keys of its own. The fence locks the invocation row; the guards a fenced
 * effect takes are the application's. A lock key minted inside the module is either unused or a lock nobody outside
 * the module can reason about.
 */
class UnusedLockKeysRemovedTest {
    @Test
    fun `no rain-jobs class mints a lock key`() {
        classes().should(mintNoLockKey()).check(
            ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.gd.rain.jobs"),
        )
    }

    @Test
    fun `the rule fails a class that mints one`() {
        assertThatThrownBy {
            classes().should(mintNoLockKey()).check(ClassFileImporter().importClasses(Holder::class.java))
        }.isInstanceOf(AssertionError::class.java).hasMessageContaining("keyOf")
    }

    /** `LockKey` is a value class: minting one is a call to `keyOf` or to its `constructor-impl`/`box-impl`. */
    private fun mintNoLockKey(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("mint no lock key") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                item.methodCallsFromSelf
                    .filter { call ->
                        (call.target.owner.name == LOCK_KEY_FILE && call.target.name == "keyOf") ||
                            (call.target.owner.name == LockKey::class.java.name && call.target.name in MINTING)
                    }.forEach { call -> events.add(SimpleConditionEvent(item, false, call.description)) }
            }
        }

    @Suppress("unused")
    internal class Holder {
        fun key(): LockKey =
            com.gd.rain.core.lock
                .keyOf("jobs", "fence")
    }

    private companion object {
        const val LOCK_KEY_FILE = "com.gd.rain.core.lock.LockKeyKt"
        val MINTING = setOf("constructor-impl", "box-impl")
    }
}
