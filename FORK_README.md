# OpenLineage — custom fork

This is a fork of [OpenLineage](https://github.com/OpenLineage/OpenLineage). It adds two
capabilities that the upstream Spark integration does not provide out of the box, plus the
build tooling needed to produce a single usable Spark JAR from this fork.

## Why this fork exists

1. **Oracle TNS support.** Upstream's Spark integration understands Oracle JDBC URLs in
   **EZConnect** form (`//host:port/serviceName`) but explicitly rejects Oracle **TNS**
   (Transparent Network Substrate) descriptors — the format used in most enterprise Oracle
   environments (load balancing, failover, multi-address `DESCRIPTION_LIST`s). Without
   support, Spark jobs reading those Oracle sources produce no/incorrect lineage. This fork
   parses TNS descriptors and converts them to EZConnect so lineage events carry the correct
   dataset namespace — no manual URL rewriting required.

2. **S3 transport bundled into the Spark JAR.** The S3 OpenLineage transport relies on AWS
   STS, which was not shaded, and the transport itself was not bundled into the Spark fat
   JAR. That caused class conflicts / `ServiceConfigurationError` at Spark runtime. This fork
   shades STS and bundles the S3 transport so a **single** `openlineage-spark` JAR can emit
   lineage events directly to S3 — no separate `transports-s3` JAR needed.

All changes are **additive**. No upstream functionality was removed, and every other
database and transport behaves exactly as before — this JAR is a superset of the upstream
build. See [DOCKER.md](DOCKER.md) for how to build and test it.

---

## What changed, and why

### Feature 1 — Oracle TNS → EZConnect

| File | Change |
|---|---|
| `client/java/.../utils/jdbc/TnsParser.java` *(new)* | Parses an Oracle TNS descriptor and emits EZConnect (`host:port/serviceName`). Handles `SERVICE_NAME` / `SID` / `INSTANCE_NAME`, default port `1521`, case-insensitive keywords, and multi-address descriptors. |
| `client/java/.../utils/jdbc/OracleJdbcExtractor.java` | Replaced the hard "TNS format is unsupported" error with TNS detection + conversion via `TnsParser`, **gated behind a config flag** (see below). When disabled (default), behavior is unchanged. |
| `integration/spark/app/.../agent/ArgumentParser.java` | Added the config constant `spark.openlineage.jdbc.oracle.tns.enabled`. |
| `client/java/.../utils/jdbc/TnsParserTest.java` *(new)* | Unit tests: simple descriptor, multi-address ordering, `INSTANCE_NAME`, default port, malformed input. |
| `client/java/.../utils/jdbc/JdbcDatasetUtilsTestForOracle.java` | Integration tests for TNS URLs end-to-end, with the flag on/off. |

**Enabling TNS parsing** (disabled by default for backward compatibility), in priority order:
- Spark conf: `spark.openlineage.jdbc.oracle.tns.enabled=true`
- JVM property: `-Dspark.openlineage.jdbc.oracle.tns.enabled=true`
- Env var: `SPARK_OPENLINEAGE_JDBC_ORACLE_TNS_ENABLED=true`

**Multi-address descriptors.** When a descriptor lists several `(ADDRESS=...)` entries (load
balancing / failover), only one address is used for the namespace — a dataset namespace
cannot contain commas, and all addresses point to the same service. Hosts are lowercased,
ordered, and the first is selected, with a warning logged about the dropped addresses.

### Feature 2 — S3 transport / AWS STS shading

| File | Change |
|---|---|
| `client/java/transports-s3/build.gradle` | Added `software.amazon.awssdk:sts` so STS is relocated into the `io.openlineage.client.shaded` namespace by the existing shading rule. |
| `integration/spark/build.gradle` | Added `io.openlineage:transports-s3` as a dependency and excluded it from `shadowJar { minimize() }` so its `ServiceLoader`-discovered classes survive optimization. The existing `software.amazon` relocation + `mergeServiceFiles()` cover the rest. |

Result: a single `openlineage-spark_2.12-<version>.jar` can be used with
`spark.openlineage.transport.type=s3`.

### Build-enabling fixes (not feature work, but required to build this fork)

| File | Change |
|---|---|
| `integration/spark/spark3/.../iceberg/SnowflakeCatalogTypeHandler.java` | **Pre-existing compile bug.** It overrode `getIdentifier` returning a bare `DatasetIdentifier`, while the base class and all sibling handlers return `Optional<DatasetIdentifier>`. The Spark module would not compile until this was fixed (wrapped in `Optional`). Unrelated to the two features — fixed only because it blocked the build. |
| `client/java/build.gradle`, `client/java/transports.build.gradle`, `integration/spark/build.gradle`, `integration/sql/iface-java/build.gradle` | Relaxed signing: `required { isReleaseVersion }` → `required { isReleaseVersion && findProperty("signingKey") != null }`. Lets non-`SNAPSHOT` versions build/publish locally without GPG keys (signing still happens when a key is provided). |

### Version management

| File | Change |
|---|---|
| `VERSION` *(new)* | Single source of truth for the artifact version. Edit this one file to change the produced JAR's version; the Docker build stamps it into all three modules' `gradle.properties` (client, spark, sql-java), which must stay in sync because Spark resolves the others by version from the local Maven repo. A non-`SNAPSHOT` value yields a JAR without the `-SNAPSHOT` suffix. |

### Build & test environment

| File | Change |
|---|---|
| `Dockerfile` *(new)* | Self-contained JDK 17 build image. Building it runs the whole pipeline as a pass/fail gate: client tests → publish client + `transports-s3` (shaded STS) to mavenLocal → build the Rust `openlineage-sql-java` dependency → build the Spark shadow JAR → assert STS is shaded in and the S3 transport is registered. |
| `DOCKER.md` *(new)* | How to build, test, change the version, get a shell, and extract the JAR. |
| `.dockerignore` | Excludes host build outputs / caches / `.git` from the build context. |

---

## Building

```bash
docker build -t openlineage-fork .
```

A green build means all tests passed and the JAR contains shaded STS + the S3 transport.
Full instructions (version changes, extracting the JAR, runtime flags) are in
[DOCKER.md](DOCKER.md).
