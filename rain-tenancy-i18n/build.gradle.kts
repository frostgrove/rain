// The only adapter allowed to combine tenant scopes, tenant presentation policy and i18n overlays.
plugins {
    id("rain.jooq-schema")
}

rainSchema {
    module.set("tenancy-i18n")
}

dependencies {
    api(project(":rain-i18n"))
    api(project(":rain-tenancy"))
    api(project(":rain-persistence"))

    testImplementation(project(":rain-test"))
}
