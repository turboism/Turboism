# Cubism Compatibility Contracts

This directory contains the public, reproducible contracts required to build and verify Turboism's exact-version Cubism adapters. It contains no Cubism installation, binary, method body, private member, proprietary resource, host log, screenshot, or machine-specific path.

## Structure

- `verification/` — hash-pinned static records packaged into the agent under `META-INF/turboism/verification/`.
- `mapping-packs/draft/` — selector catalogues bound to the reviewed records and exact-version profiles.
- `profiles/draft/` — exact-version mapping-pack catalogues for 5.2.03, 5.3.02, and 5.3.03.
- `core-api/observed/` — exact-artifact public class declarations, JVM descriptors, and public constants governed by `turboism.cubism-core.public-api` v1.
- `core-api/policy/` — complete-surface classification and selector policies with SHA-256 roster bindings.

## Status and authorization

A `DRAFT` mapping pack or profile does not authorize runtime binding. Runtime admission requires the matching `VERIFIED_STATIC` record, reviewed host-artifact identity, pinned record digest, exact Cubism version, capability set, and selector aliases. Unknown or mismatched inputs fail closed.

## Local evidence boundary

Separately licensed Cubism installations and binary evidence belong under the ignored local `cubism-ref/` boundary or an external evidence repository. Exact-host logs and results belong under ignored `host-evidence/` or build output. Only the minimal machine-readable product contracts required by normal builds are tracked here.
