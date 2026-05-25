# Corporate proxy CA certificates

If you build behind a TLS-inspecting corporate proxy, downloads in the Docker build
(rustup via `curl`, cargo crate fetches, JVM HTTPS) fail with errors like
`self-signed certificate in certificate chain` or `PKIX path building failed`.

To fix it, drop your organization's **root CA** certificate file(s) here, then rebuild:

```
certs/
  corp-root-ca.crt     <- your file(s), PEM-encoded (.crt or .pem)
```

The Docker build trusts every `*.crt` / `*.pem` in this folder for both the OS bundle
(used by curl and cargo) and the JDK truststore. If this folder contains no certificates,
the build skips this step (fine unless your network requires the CA).

## Getting the cert

- Ask your IT/security team for the proxy's root CA, **or**
- macOS: Keychain Access → System/System Roots → find the proxy/root CA → export as `.pem`,
  **or**
- Export from a browser visiting any HTTPS site (view certificate → root of the chain).

The actual `.crt` / `.pem` files are git-ignored so the cert is not committed.

----
SPDX-License-Identifier: Apache-2.0\
Copyright 2018-2026 contributors to the OpenLineage project
