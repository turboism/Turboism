# Locale and host validation

Turboism supports `en`, `ja`, `ko`, `zh_Hans`, and `zh_Hant`. The base catalog
(`messages.properties`) remains the final fallback for each plugin.

## Runtime selection

At startup the runtime resolves one effective locale, in this order:

1. valid `-Dturboism.locale=<id>`;
2. valid `locale` in `<turboism.home>/config.json`;
3. the Cubism host display locale;
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
readiness. Run `:testing:integration-tests:officialPluginI18nCompletenessTest` or
`checkOfficialPluginI18nCompleteness` for catalog checks, and use
`launch-cubism-host-locale-validation.sh` (or the `-52`/`-53` exact-version
wrappers) only after the exact host fixture, identity, readiness, result, and
cleanup prerequisites are prepared. The wrappers use the shared runner, which
launches the official `CubismEditor5.bat`; `--dry-run` validates arguments only
and never launches Cubism.
