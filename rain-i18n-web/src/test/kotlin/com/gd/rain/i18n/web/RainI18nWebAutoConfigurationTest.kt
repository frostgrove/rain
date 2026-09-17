package com.gd.rain.i18n.web

import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.web.autoconfigure.RainI18nArtifactAutoConfiguration
import com.gd.rain.i18n.web.autoconfigure.RainI18nWebAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.task.TaskDecorator
import org.springframework.web.servlet.LocaleContextResolver

class RainI18nWebAutoConfigurationTest {
    @Test
    fun `the optional bridge activates only when an application supplies catalog authority`() {
        val runner = WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(RainI18nWebAutoConfiguration::class.java))

        runner.run { context ->
            assertThat(context)
                .doesNotHaveBean(FilterRegistrationBean::class.java)
                .doesNotHaveBean(I18nViewFactory::class.java)
                .doesNotHaveBean(LocaleContextResolver::class.java)
        }

        runner
            .withBean(CatalogSnapshotProvider::class.java, { CatalogSnapshotProvider { error("not read while the context starts") } })
            .run { context ->
                assertThat(context)
                    .hasSingleBean(I18nViewFactory::class.java)
                    .hasSingleBean(AcceptLanguageServletLocaleSource::class.java)
                    .hasSingleBean(LocaleContextResolver::class.java)
                val registration = context.getBeansOfType(FilterRegistrationBean::class.java).values.single()
                assertThat(registration.filter).isInstanceOf(I18nRequestFilter::class.java)
            }
    }

    @Test
    fun `the auto configuration imports name the artifact bootstrap before the web bridge`() {
        val imports =
            checkNotNull(
                javaClass.classLoader.getResource("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"),
            ).readText()
                .lines()
                .filter(String::isNotBlank)

        assertThat(imports).containsExactly(
            RainI18nArtifactAutoConfiguration::class.java.name,
            RainI18nWebAutoConfiguration::class.java.name,
        )
        assertThat(RainI18nArtifactAutoConfiguration::class.java.isAnnotationPresent(AutoConfiguration::class.java)).isTrue()
        assertThat(RainI18nWebAutoConfiguration::class.java.isAnnotationPresent(AutoConfiguration::class.java)).isTrue()
    }

    @Test
    fun `explicit web policy adds only the selected locale sources and named executor bridge`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainI18nWebAutoConfiguration::class.java))
            .withBean(CatalogSnapshotProvider::class.java, { CatalogSnapshotProvider { error("not read while the context starts") } })
            .withPropertyValues(
                "rain.i18n.web.query-source=lang",
                "rain.i18n.web.cookie-source=locale",
                "rain.i18n.web.propagate-executors=true",
            ).run { context ->
                assertThat(context.getBeansOfType(ServletLocaleSource::class.java).values)
                    .anyMatch { source -> source is ParameterServletLocaleSource && source.id == "parameter_lang" }
                    .anyMatch { source -> source is CookieServletLocaleSource && source.id == "cookie_locale" }
                assertThat(context).hasBean("rainI18nLocaleContextTaskDecorator")
                assertThat(context.getBean("rainI18nLocaleContextTaskDecorator", TaskDecorator::class.java))
                    .isInstanceOf(I18nRequestLocaleContextTaskDecorator::class.java)
            }
    }

    @Test
    fun `disabled servlet policy leaves a supplied catalog provider without web bridge beans`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RainI18nWebAutoConfiguration::class.java))
            .withBean(CatalogSnapshotProvider::class.java, { CatalogSnapshotProvider { error("not read while the context starts") } })
            .withPropertyValues("rain.i18n.web.enabled=false")
            .run { context ->
                assertThat(context)
                    .doesNotHaveBean(I18nViewFactory::class.java)
                    .doesNotHaveBean(LocaleContextResolver::class.java)
            }
    }
}
