// The only adapter allowed to know both tenant scopes and event namespaces.
plugins {
    id("rain.spring-module")
}

dependencies {
    api(project(":rain-tenancy"))
    api(project(":rain-event"))

    testImplementation(project(":rain-test"))
}
