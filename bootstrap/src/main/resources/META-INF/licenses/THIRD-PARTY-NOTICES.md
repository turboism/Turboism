# Third-party licenses and notices

This directory is the component license/notice matrix bundled into the
Turboism bootstrap fat JAR (`turboism-agent.jar`) under the stable
`META-INF/licenses/` prefix.

Each subdirectory carries a `LICENSE` file with the authoritative license text
for that component and a `NOTICE` file with attribution. License identifiers
follow [SPDX](https://spdx.org/licenses/).

| Component | Embedded coordinates | Version | SPDX license | License URL | Embedded in `turboism-agent.jar` |
| --- | --- | --- | --- | --- | --- |
| Turboism | `dev.turboism:bootstrap`, `:runtime`, `:sdk` | 0.43.1 | MIT | https://spdx.org/licenses/MIT.html | yes |
| Jackson | `com.fasterxml.jackson.core:jackson-{annotations,core,databind}` | 2.18.9 | Apache-2.0 | https://spdx.org/licenses/Apache-2.0.html | yes |
| ASM | `org.ow2.asm:asm` | 9.7.1 | BSD-3-Clause | https://spdx.org/licenses/BSD-3-Clause.html | yes |
| SLF4J | `org.slf4j:slf4j-api` | 1.7.30 | MIT | https://spdx.org/licenses/MIT.html | yes |
| Resilience4J | `io.github.resilience4j:resilience4j-{core,bulkhead,timelimiter,circuitbreaker}` | 2.1.0 | Apache-2.0 | https://spdx.org/licenses/Apache-2.0.html | yes |
| Vavr | `io.vavr:vavr` | (not embedded) | Apache-2.0 | https://spdx.org/licenses/Apache-2.0.html | no |

The Vavr entry is retained because it is part of the Resilience4J dependency
family; it is not currently embedded in the bootstrap fat JAR.

This matrix covers only the Turboism bootstrap fat JAR. It does not cover the
Java installer which embeds a JRE, any Live2D/Cubism licensing, or separately
installed components.
