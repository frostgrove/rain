package com.gd.rain.i18n.web.autoconfigure

import com.gd.rain.i18n.CatalogSnapshotProvider
import com.gd.rain.i18n.web.ArtifactCatalogSnapshotProvider
import com.gd.rain.i18n.web.I18nArtifactProperties
import com.gd.rain.i18n.web.I18nArtifactSettings
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ResourceLoader

/** Local canonical-artifact bootstrap, ordered before the servlet bridge that consumes its provider. */
@AutoConfiguration(before = [RainI18nWebAutoConfiguration::class])
@ConditionalOnBooleanProperty(I18nArtifactProperties.ENABLED)
@EnableConfigurationProperties(I18nArtifactProperties::class)
public class RainI18nArtifactAutoConfiguration {
    @Bean
    public fun i18nArtifactSettings(properties: I18nArtifactProperties): I18nArtifactSettings = properties.settings()

    @Bean
    @ConditionalOnMissingBean(CatalogSnapshotProvider::class)
    public fun catalogSnapshotProvider(
        settings: I18nArtifactSettings,
        resources: ResourceLoader,
    ): CatalogSnapshotProvider = ArtifactCatalogSnapshotProvider(settings, resources)
}
