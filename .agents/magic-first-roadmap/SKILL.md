---
name: magic-first-roadmap
description: Create an evidence-backed roadmap for a framework capability with magic-first DX, explicit extension points, and a full low-level SDK. Use when planning a module or bounded context and selecting features from comparable implementations.
---

# Magic First Roadmap

Create a roadmap that chooses the right capabilities and gives each one a concrete home. The desired experience is **magic first, never magic only**: ordinary applications get a safe declarative path; advanced applications can inspect, override, replace, or bypass that path with an equally supported low-level SDK.

Use this skill for product/architecture roadmaps. Do not use it merely to implement an already approved plan or to write a generic project schedule.

## Outcome

Produce one roadmap document in the repository's requested planning location. It must let an implementer answer all of these without rediscovery:

- What behaviour does the bounded context own, and what remains outside it?
- Which comparable implementations were studied, at what version/source, and what was adopted, adapted, or rejected?
- What does the simplest application write? How does it change one policy? How does it invoke the complete SDK directly?
- Which Kotlin/Spring mechanisms implement the chosen behaviour without copying another ecosystem's incidental mechanics?
- How are compatibility, failure, concurrency, operational limits, testing, and rollout verified?

The roadmap is a decision record, not a feature wish list. More features are not automatically better.

## Working method

1. Read the repository guidance and map the current module graph, public contracts, configuration, runtime model, tests, and existing user changes. Identify the requested bounded-context boundary before proposing modules.

2. Turn the request into a capability inventory and invariants. Preserve explicit requirements such as full feature parity, security, multi-tenancy, durability, or compatibility. If the user asks for all functionality, port every semantic capability; adapt only its implementation form to the local platform.

3. Research at least two relevant, production-used comparable implementations, plus the platform's official documentation when it affects the design. Choose comparators by domain, not brand recognition: for example, frameworks plus a focused library, or a sibling framework plus a lower-level engine. Prefer official documentation, source, specifications, and release notes. Record the version/date and direct source links. Do not copy a feature because it is fashionable.

4. For every meaningful capability found in the product or comparators, record one decision:

   | Capability | Evidence/source | Decision | Local Kotlin/Spring shape | Why |
   |---|---|---|---|---|
   | ... | ... | Adopt / Adapt / Reject | API, module, bean, configuration, persistence contract, etc. | value, trade-off, compatibility |

   `Reject` is valid only with a concrete reason. Do not silently omit a comparator's useful feature. `Adapt` must state the local form, not just say “adapt for Kotlin”.

5. Design the dual surface for every automatic behaviour. Include a compact parity matrix:

   | Concern | Magic-first path | Targeted override | Full SDK | Explain/diagnostics |
   |---|---|---|---|---|

   The magic path may use Spring Boot auto-configuration, typed configuration properties, scoped beans, filters, annotations, declarative registration, or conventional adapters. It must resolve to named policies/providers with deterministic precedence. Targeted overrides replace one policy without reimplementing the subsystem. The SDK exposes the same semantic contracts directly; it must not be a weaker, undocumented escape hatch.

6. Translate semantics rather than mechanics. Map each borrowed design to the idioms and constraints already present in the repository. In Kotlin/Spring this normally means immutable Kotlin values, generated APIs where compile-time contracts help, explicit Spring beans/auto-configuration, request/job scopes, `TransactionTemplate`/jOOQ for durable transitions, `Clock`, Micrometer adapters, and bounded executor propagation. Do not carry over Go `context.Context`, goroutines/channels, pointer identity, runtime reflection or struct tags; Laravel facades/static containers; or Node lifecycle/global-state patterns merely because the upstream needs them. Keep a borrowed mechanism only when its *semantic invariant* is necessary, and name the native replacement.

7. Make operations and failure modes first-class. State configuration presence rules, defaults, override precedence, ownership, bounds, health/metrics/audit, migration/rollback, versioning, and safe degradation. If state can change at runtime, specify concurrency and multi-node semantics. If an external artifact or service is involved, specify integrity and provenance separately.

8. Define verification before phases: use cases, invariants, contract tests for extension points, compatibility tests, concurrency/transaction tests where applicable, and an executable final gate. Then split implementation into dependency-ordered phases with observable completion criteria.

## Required roadmap content

Use headings appropriate to the repository, but cover:

1. scope, non-goals, bounded-context/module graph, and dependencies;
2. researched comparators and the adopt/adapt/reject table;
3. invariants and public contracts;
4. magic/override/full-SDK parity matrix with at least one minimal API sketch where ambiguity remains;
5. lifecycle, configuration, security, failure and observability contracts relevant to the feature;
6. data/concurrency/multi-node contracts when state is durable or shared;
7. tests, compatibility and operational acceptance criteria;
8. ordered phases and a definition of done.

Keep explicit separators between application convenience APIs, extension SPI, and kernel/SDK. Show dependency direction so auto-configuration/adapters never become requirements of the kernel.

## Quality gate

Before handing off the roadmap, verify that:

- every claimed “magic” decision has a named owner, precedence rule, and explicit bypass or replacement path;
- the low-level SDK can achieve the same supported outcomes without depending on hidden request/global state;
- each selected upstream feature has a local contract, and each rejected feature has a reason;
- platform adaptations preserve semantics, rather than importing language workarounds;
- the plan does not promise undefined “later” integrations where a current SPI, contract, or deliberate rejection is required;
- external factual claims have direct citations, and local claims point to actual files when useful;
- the plan itself passes the repository's applicable formatting/checking command.
