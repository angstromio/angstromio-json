# ångströmio-json

[![Project Status: WIP – Initial development is in progress, but there has not yet been a stable, usable release suitable for the public.](https://www.repostatus.org/badges/latest/wip.svg)](https://www.repostatus.org/#wip)
[![Java CI with Gradle](https://github.com/angstromio/angstromio-json/actions/workflows/gradle.yml/badge.svg)](https://github.com/angstromio/angstromio-json/actions/workflows/gradle.yml)
[![codecov](https://codecov.io/gh/angstromio/angstromio-json/graph/badge.svg?token=GJUZZVFVFY)](https://codecov.io/gh/angstromio/angstromio-json)

A "fail-slow" JSON deserializer for Kotlin data classes.

## TODOs
- More tests
- Value class deserialization
  - https://github.com/FasterXML/jackson-module-kotlin/issues/199#issuecomment-1013810769
  - https://github.com/FasterXML/jackson-module-kotlin/issues/413
  - https://github.com/FasterXML/jackson-module-kotlin/issues/650
- Some type 'WrappedValue' support (depends on https://github.com/Kotlin/KEEP/blob/master/notes/value-classes.md#value-interfaces) assuming the above is solved.

