# rain-sample-minimal

The smallest rain application: rain-web over rain-boot and rain-observability, and nothing that stores data. It shows
what every rain HTTP application states and proves that the persistence modules are optional. The complete reference
application, with every module and a Docker stand, is [`samples/rain-sample`](../rain-sample/README.md).

## What it contains

- `MinimalApplication` started with `runRain`, one route (`POST /v1/greetings`), its own configuration section
  `sample.greetings` declared through a `ConfigurationContributor`, and its own error catalog (`name_reserved`).
- `application.yml` with what every deployment shares; the stage and the roles or command are stated per process.

## Run it

```
./gradlew :samples:rain-sample-minimal:bootJar
RAIN_DEPLOYMENT_STAGE=dev RAIN_RUNTIME_ROLES=api java -jar samples/rain-sample-minimal/build/libs/rain-sample-minimal-0.1.0-SNAPSHOT.jar
curl -s -X POST localhost:8080/v1/greetings -H 'Content-Type: application/json' -d '{"name":"Ada"}'
curl -s localhost:8080/ready
RAIN_DEPLOYMENT_STAGE=prod RAIN_RUNTIME_COMMAND=config-check java -jar samples/rain-sample-minimal/build/libs/rain-sample-minimal-0.1.0-SNAPSHOT.jar
```

## Tests

`./gradlew :samples:rain-sample-minimal:check` starts the application on a random port and checks problem responses,
the body limit, readiness, the refusal of a deployment that states nothing, and the `config-check` command.
