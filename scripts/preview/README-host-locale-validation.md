# Locale and host validation

Turboism supports `en`, `ja`, `ko`, `zh_Hans`, and `zh_Hant`. The base catalog
(`messages.properties`) remains the final fallback for each plugin.

## Runtime selection

At startup the runtime resolves one effective locale, in this order:

1. valid `-Dturboism.locale=<id>`;
2. valid `locale` in `<turboism.home>/config.json`;
3. the Cubism host locale — the language Cubism applied from
   `File → Environment Settings → General → Language` (its persisted
   `Locale.Editor` choice) to the process default locale, with the launcher's
   `-Duser.language`/`-Duser.country` as the fallback when the process default
   carries no language;
4. the JVM display locale.

Unsupported operator/config values are rejected, diagnosed, and skipped. The
selected locale is fixed for the process; changing `config.json` requires a
restart. Plugin catalogs fall back through the selected locale, `en`, and the
base catalog as available. Metadata/catalog failures are reported through the
runtime plugin-management diagnostic sink.

Every official plugin descriptor declares the complete matrix `base`, `en`,
`ja`, `ko`, `zh_Hans`, and `zh_Hant`, and the `checkOfficialPluginI18nCompleteness`
gate verifies the descriptor, catalog, key, and message-format contracts.

## Verification levels

The focused build gates are build-only evidence. They do not establish Cubism
readiness. Run `:tests:officialPluginI18nCompletenessTest` or
`checkOfficialPluginI18nCompleteness` for catalog checks, and use
`launch-cubism-host-locale-validation.sh` (or the `-52`/`-53` exact-version
wrappers) only after the exact host fixture, identity, readiness, result, and
launches the official `CubismEditor5.bat`; `--dry-run` validates arguments only
and never launches Cubism.

## Environment Settings language validation

The launcher passes `-Duser.language`, which only selects the language *version*
of the build. The language the editor actually shows is the one chosen in
`File → Environment Settings → General → Language`; Cubism persists it as the
`Locale.Editor` property and applies it to the process default locale at
startup. Evidence from a build whose language version matches the environment
setting cannot tell the two sources apart, so this lane has an explicit
precondition:

```bash
scripts/preview/run-host-locale-host-validation.sh 5302 env-ja \
  --locale system --environment-language ja
```

`--environment-language en|ja|ko|zh` (later `zh-Hans`/`zh-Hant` map to the host's
single `zh`) installs the pre-launch hook
`scripts/preview/host-locale-environment-language-hook.sh`, which seeds the
cloned prefix before launch and fails closed when the option, the language, the
version or the editor data directory is unusable. The hook writes
`environment-language.properties` into the evidence directory with the seeded
value, the launcher language parsed from the cloned BAT, `discriminating=YES|NO`,
the serialized bytes and their SHA-256, and the value it replaced. The adapter
also registers every other host language as a `Using startup locale <tag>`
failure marker, so the runtime's own locale selection line must name the seeded
language for the run to pass.

Use `--locale system` for these runs: an explicit `-Dturboism.locale` outranks
the host and would defeat the check.
