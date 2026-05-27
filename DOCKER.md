# Dockerized build & test environment

This fork adds two enterprise features to OpenLineage:

1. **Oracle TNS → EZConnect conversion** in the Java client / Spark integration.
2. **S3 transport bundled into the Spark shadow JAR**, with AWS STS shaded.

Because the build needs **JDK 17** and a specific build order, the `Dockerfile` at the
repo root provides a self-contained environment so you don't need Java on your host.

## What the image does

Building the image **runs the full build and test suite**. A successful
`docker build` means everything passed:

1. `client/java` unit tests run — including `TnsParserTest` and
   `JdbcDatasetUtilsTestForOracle`.
2. The Java client and `transports-s3` (with AWS STS shaded into
   `io.openlineage.client.shaded.software.amazon`) are published to the in-image local
   Maven repo (`~/.m2`).
3. `openlineage-sql-java` is built and published — this compiles a **Rust** native
   library (the Rust toolchain is installed automatically during the build) that the
   Spark integration depends on transitively.
4. The Spark fat JAR is built (`integration/spark` resolves `transports-s3` and
   `openlineage-sql-java` from `mavenLocal()` — hence the publish-first ordering).
5. The shadow JAR is asserted to contain shaded STS classes **and** the S3 transport's
   `ServiceLoader` entry. If either is missing, the build fails.

> **Note:** the `transports-s3` Testcontainers tests (`shouldWriteEventToS3`, etc.) spin
> up an S3-mock Docker container and are **skipped** in the image build (there's no Docker
> daemon inside `docker build`). The STS shading they would exercise is instead verified
> by inspecting the final shadow JAR. To run those tests, do it on a host with Docker:
> `cd client/java && ./gradlew :transports-s3:test`.

## Prerequisites

- Docker Desktop (or Docker Engine).
- Allocate **≥ 8 GB** of memory to Docker — the multi-module Spark build is heavy.
  (Docker Desktop → Settings → Resources → Memory.)
- A network connection: the build downloads Gradle, the AWS SDK, Spark artifacts, and a
  Rust toolchain. The first build takes a while (Rust compilation + Spark modules).

## Build + test everything

```bash
docker build -t openlineage-fork .
```

This single command **is** the test run. A green build = TNS/Oracle tests passed and the
Spark shadow JAR correctly bundles the S3 transport with shaded STS. A non-zero exit
means one of the steps failed — read the build log to see which.

## Changing the version

The artifact version is controlled by a **single file**: [`VERSION`](VERSION) at the repo
root. To change it, edit that file and rebuild:

```bash
echo "1.48.0-mycorp" > VERSION
docker build -t openlineage-fork .
```

The build stamps this value into all three modules' `gradle.properties` (client, spark,
sql-java) so they stay in sync — required, because the Spark build resolves the other two
by version from the local Maven repo. The produced jar is named after it, e.g.
`openlineage-spark_2.12-1.48.0.jar` (no `-SNAPSHOT`). A non-`SNAPSHOT` version normally
makes Gradle require GPG signing; the build.gradle signing config has been relaxed to
require it only when a `signingKey` is actually provided, so local builds work unsigned.

## Get a shell / re-run targeted tests

```bash
docker run --rm -it openlineage-fork bash
```

Then, inside the container:

```bash
# Just the TNS parser tests
cd client/java && ./gradlew test --tests "*TnsParser*"

# TNS + Oracle integration tests
cd client/java && ./gradlew test --tests "*TnsParser*" --tests "*ForOracle*"

# Rebuild the Spark fat JAR
cd integration/spark && ./gradlew shadowJar
```

## Extract the built Spark JAR to your host

```bash
docker create --name ol openlineage-fork
docker cp ol:/openlineage/integration/spark/build/libs ./out
docker rm ol
ls ./out   # openlineage-spark_2.12-1.48.0.jar  (matches the VERSION file)
```

## Publish to JFrog

The Spark fat JAR publishes as a Maven artifact:

```text
${MAVEN_GROUP_ID}:${MAVEN_ARTIFACT_ID}:<VERSION>
```

Publish configuration is read from environment variables or from the repo-root `.env`
file. Environment variables take precedence over `.env`.

Start from the template:

```bash
cp .env.example .env
```

Required publish variables:

```bash
export MAVEN_GROUP_ID="ge.myorg"
export MAVEN_ARTIFACT_ID="openlineage-spark_2.12"
export JFROG_REPOSITORY_NAME="jfrog"
export JFROG_MAVEN_URL="<artifactory-maven-deploy-url>"
# Required by Gradle only when JFROG_MAVEN_URL uses http:// instead of https://.
export JFROG_ALLOW_INSECURE_PROTOCOL="true"
export JFROG_USERNAME="<username>"
export JFROG_PASSWORD="<password-or-api-key>"
# or use JFROG_TOKEN instead of JFROG_PASSWORD
```

From a local Gradle environment, publish with:

```bash
cd integration/spark
./gradlew publishMavenJavaPublicationToJfrogRepository
```

From the Docker build image, build/test first, then publish explicitly:

```bash
docker build -t openlineage-fork .
docker run --rm \
  --env-file .env \
  openlineage-fork \
  bash -lc 'cd integration/spark && ./gradlew publishMavenJavaPublicationToJfrogRepository'
```

The artifact should appear under:

```text
<repo-key>/<MAVEN_GROUP_ID with dots as slashes>/<MAVEN_ARTIFACT_ID>/<VERSION>/
```

## Using the features at runtime

These are toggled on the Spark job, not at build time.

- **Enable Oracle TNS parsing** (disabled by default for backward compatibility) via any
  of, in priority order:
  - Spark conf: `spark.openlineage.jdbc.oracle.tns.enabled=true`
  - JVM property: `-Dspark.openlineage.jdbc.oracle.tns.enabled=true`
  - Env var: `SPARK_OPENLINEAGE_JDBC_ORACLE_TNS_ENABLED=true`
- **Select the S3 transport**: `spark.openlineage.transport.type=s3` (the single
  `openlineage-spark` JAR is sufficient — no separate `transports-s3` JAR needed).

When a TNS descriptor lists multiple addresses (load balancing / failover), hosts are
lowercased and ordered, and the first is used for the dataset namespace (a warning is
logged), since a namespace can't contain commas.

## Troubleshooting

### TLS / certificate errors behind a corporate proxy

Symptoms, all caused by a TLS-inspecting proxy whose root CA the build doesn't trust:
- `curl: (60) ... self-signed certificate in certificate chain` (rustup install in
  `compile.sh`, step 2).
- `PKIX path building failed` / `SSLHandshakeException` (JVM HTTPS).

**Fix: provide your org's root CA.** Drop the CA file(s) into the [`certs/`](certs/) folder
(`*.crt` / `*.pem`) and rebuild:

```
certs/corp-root-ca.crt
docker build -t openlineage-fork .
```

The Docker build imports everything in `certs/` into both the OS trust bundle (used by
`curl` and `cargo`) and the JDK truststore. The actual cert files are git-ignored. See
`certs/README.md` for how to obtain the cert.

Notes:
- The downloads themselves (rustup, crates.io, Gradle, Maven) must reach the network; the
  CA only makes the proxy's interception **trusted**. Setting `-Dhttps.proxyHost` alone
  does **not** help — this is a certificate-trust error, not a routing one.
