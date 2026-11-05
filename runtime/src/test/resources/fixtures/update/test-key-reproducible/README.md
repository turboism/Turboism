# test-key-reproducible signed receipt fixture

This directory holds a **non-historical** framework-update receipt used only by
`SignedReceiptAndUpdateJournalCompatibilityTest`. It proves that the production
`UpdateManifestVerifier#authenticate` path verifies a fixed signed document and refuses
tampered bytes; it is **not** evidence of any published Turboism release.

- Key id: `test-key-reproducible`
- Algorithm: Ed25519
- Private seed (hex, test-only): `bb40f1e2c19b88de46ee23180259ccd80fd8ea3beee91d81907f52ba38e32a27`
- Public key (X.509 DER, Base64): `MCowBQYDK2VwAyEAIRzyTAHu9jIM5GHnFnjcAmMVZEDbCDnZm73tWQXR8N8=`
- `signed-receipt-manifest.json` is the canonical manifest bytes covered by the signature.
- `signed-receipt-manifest.json.sig` is the detached envelope.

The key material is reproducible from the seed and is deliberately committed only as test
fixtures. It must never be presented as a historical release key, and this receipt must never
be re-signed or treated as provenance for a published artifact.
