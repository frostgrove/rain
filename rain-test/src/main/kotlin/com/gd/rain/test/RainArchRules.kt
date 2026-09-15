package com.gd.rain.test

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.util.concurrent.TimeUnit

/**
 * The architecture rules rain holds its own code to, as ArchUnit rules an application can check against its code too.
 *
 * A *module* of a class under [ROOT] (or another root) is the package segment right after the root:
 * `com.gd.rain.jobs.internal.JobCatalog` belongs to `jobs`. A package is *internal* when a segment after the module is
 * `internal`, and *auto-configuration* when a segment after the module is `autoconfigure`. Every rule is decided from
 * those names alone.
 */
public object RainArchRules {
    public const val ROOT: String = "com.gd.rain"

    /** No class uses an internal package of another module: a module's internal packages are its implementation. */
    public fun internalPackagesStayInTheirModule(root: String = ROOT): ArchRule =
        classes()
            .that()
            .resideInAPackage("$root..")
            .should(crossModule("use no internal package of another module", root) { isInternal(it, root) })
            .because("a module shares only its public API")

    /** Only auto-configuration uses another module's auto-configuration (to order itself after it, for instance). */
    public fun autoConfigurationStaysAmongAutoConfigurations(root: String = ROOT): ArchRule =
        classes()
            .that()
            .resideInAPackage("$root..")
            .should(
                crossModule("use another module's auto-configuration only from auto-configuration", root, sourceExempt = {
                    isAutoConfiguration(it, root)
                }) {
                    isAutoConfiguration(it, root)
                },
            ).because("auto-configuration classes are wiring, not API")

    /** An application's code uses no internal and no auto-configuration class of the modules under [root]. */
    public fun applicationUsesOnlyPublicApi(root: String = ROOT): ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackage("$root..")
            .should()
            .dependOnClassesThat(
                object : DescribedPredicate<JavaClass>("an internal or auto-configuration class under $root") {
                    override fun test(target: JavaClass): Boolean =
                        isInternal(target.packageName, root) || isAutoConfiguration(target.packageName, root)
                },
            ).because("an application reaches rain only through its public API")

    /** No class depends on a class in [packages] (ArchUnit package identifiers such as `com.example..`). */
    public fun noDependencyOn(vararg packages: String): ArchRule = noClasses().should().dependOnClassesThat().resideInAnyPackage(*packages)

    /** No test waits by sleeping: time is moved with [MutableClock], interleavings are forced with latches. */
    public fun testsDoNotSleep(): ArchRule =
        noClasses()
            .should()
            .callMethod(Thread::class.java, "sleep", java.lang.Long.TYPE)
            .orShould()
            .callMethod(Thread::class.java, "sleep", java.lang.Long.TYPE, Integer.TYPE)
            .orShould()
            .callMethod(Thread::class.java, "sleep", java.time.Duration::class.java)
            .orShould()
            .callMethod(TimeUnit::class.java, "sleep", java.lang.Long.TYPE)
            .because("a test that sleeps is slow when the machine is fast and wrong when it is slow")

    private fun crossModule(
        description: String,
        root: String,
        sourceExempt: (String) -> Boolean = { false },
        forbidden: (String) -> Boolean,
    ): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>(description) {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val own = moduleOf(item.packageName, root) ?: return
                if (sourceExempt(item.packageName)) return
                item.directDependenciesFromSelf.forEach { dependency ->
                    val target = dependency.targetClass.packageName
                    val module = moduleOf(target, root)
                    if (module != null && module != own && forbidden(target)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.description))
                    }
                }
            }
        }

    private fun moduleOf(
        packageName: String,
        root: String,
    ): String? = packageName.takeIf { it.startsWith("$root.") }?.removePrefix("$root.")?.substringBefore('.')

    private fun segmentsAfterModule(
        packageName: String,
        root: String,
    ): List<String> = if (packageName.startsWith("$root.")) packageName.removePrefix("$root.").split('.').drop(1) else emptyList()

    private fun isInternal(
        packageName: String,
        root: String,
    ): Boolean = "internal" in segmentsAfterModule(packageName, root)

    private fun isAutoConfiguration(
        packageName: String,
        root: String,
    ): Boolean = "autoconfigure" in segmentsAfterModule(packageName, root)
}
