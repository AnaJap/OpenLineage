# Self-contained build & test environment for the OpenLineage fork
# (Oracle TNS support + S3 transport bundled into the Spark shadow JAR).
#
# Building this image RUNS the full build and test suite. A successful
# `docker build` is the pass/fail gate:
#   - client/java unit tests (incl. TnsParserTest + JdbcDatasetUtilsTestForOracle)
#   - publishes client + transports-s3 (with shaded AWS STS) to the local Maven repo
#   - builds the Spark shadow JAR
#   - asserts STS is shaded in and the S3 transport is bundled
#
# See DOCKER.md for usage.

FROM eclipse-temurin:17-jdk

# unzip: inspect the shaded JAR. curl + build-essential: build the Rust-based
# openlineage-sql-java native library that the Spark integration depends on
# (compile.sh installs the Rust toolchain via rustup if absent).
RUN apt-get update \
    && apt-get install -y --no-install-recommends bash git unzip curl build-essential ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Optional corporate-proxy CA trust. Behind a TLS-inspecting proxy, the downloads this
# build performs (rustup via curl, cargo crate fetches, the JVM) fail with self-signed /
# PKIX certificate errors. Drop your org's root CA file(s) into ./certs/ (*.crt / *.pem)
# before building and they are trusted by the OS bundle (curl + cargo) and the JDK
# truststore (keytool). If ./certs/ has no certs, this is a harmless no-op.
COPY certs/ /usr/local/share/ca-certificates/extra/
RUN set -e; \
    found=0; \
    for c in /usr/local/share/ca-certificates/extra/*.crt /usr/local/share/ca-certificates/extra/*.pem; do \
      [ -e "$c" ] || continue; \
      found=1; \
      der="/usr/local/share/ca-certificates/extra/$(basename "$c" | sed 's/\.[^.]*$//').crt"; \
      if [ "$c" != "$der" ]; then cp "$c" "$der"; fi; \
      keytool -importcert -noprompt -trustcacerts -cacerts -storepass changeit \
        -alias "corp-$(basename "$c")" -file "$c"; \
    done; \
    if [ "$found" = 1 ]; then update-ca-certificates; echo "Imported corporate CA cert(s)."; \
    else echo "No corporate CA certs in ./certs/ — skipping (fine unless your proxy needs one)."; fi

# Make curl and cargo use the (possibly CA-augmented) system trust bundle.
ENV SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    CARGO_HTTP_CAINFO=/etc/ssl/certs/ca-certificates.crt

# Build as a non-root user. Some tests assert filesystem permission behavior
# (e.g. a file marked non-writable should not be appended to) — root bypasses
# those permissions and would fail the test, so the build must not run as root.
RUN useradd --create-home --shell /bin/bash builder

# Run gradle without a daemon and give the heavy Spark build enough heap.
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx4g"

WORKDIR /openlineage

# Host build outputs / caches are excluded via .dockerignore.
COPY --chown=builder:builder . .

USER builder

# Stamp the version from the single source-of-truth /VERSION file into every module's
# gradle.properties (client, spark, sql-java). Edit the repo-root VERSION file to change
# the produced artifact version — all three modules stay in sync (required, since the
# Spark build resolves the others by version from mavenLocal). A non-SNAPSHOT value here
# yields a jar named e.g. openlineage-spark_2.12-<VERSION>.jar.
RUN VERSION="$(cat VERSION)" \
    && echo "Building OpenLineage version: ${VERSION}" \
    && for f in client/java/gradle.properties \
                integration/spark/gradle.properties \
                integration/sql/iface-java/gradle.properties; do \
         sed -i "s/^version=.*/version=${VERSION}/" "$f"; \
       done

# 1) Build + test the Java client, then publish it (and transports-s3 with shaded STS)
#    to the local Maven repo so the Spark module can resolve it via mavenLocal().
#    :transports-s3:test is excluded: those are Testcontainers (s3mock) integration
#    tests that need a Docker daemon, which isn't available inside `docker build`.
#    STS shading is still verified by the JAR inspection in step 4.
RUN cd client/java \
    && ./gradlew --no-daemon test -x :transports-s3:test publishToMavenLocal

# 2) Build & publish openlineage-sql-java (Rust native lib + JNI wrapper) to mavenLocal.
#    The Spark integration depends on it transitively. compile.sh builds the native
#    library (installing Rust if needed); build.sh runs tests and publishes the artifact.
RUN cd integration/sql/iface-java \
    && ./script/compile.sh \
    && ./script/build.sh

# 3) Build the Spark fat JAR (resolves transports-s3 + openlineage-sql-java from mavenLocal).
RUN cd integration/spark \
    && ./gradlew --no-daemon shadowJar

# 4) Verify the shading/bundling: fail the build if STS isn't shaded in, or the
#    S3 transport's ServiceLoader entry is missing from the shadow JAR.
RUN VERSION="$(cat VERSION)" \
    && JAR="integration/spark/build/libs/openlineage-spark_2.12-${VERSION}.jar" \
    && echo "Verifying $JAR" \
    && unzip -l "$JAR" | grep -q "io/openlineage/spark/shaded/software/amazon/awssdk/services/sts/" \
        || (echo "FAIL: AWS STS classes are not shaded into the Spark JAR" && exit 1) \
    && unzip -p "$JAR" "META-INF/services/io.openlineage.client.transports.TransportBuilder" \
        | grep -qi "s3" \
        || (echo "FAIL: S3 transport builder is not registered in the Spark JAR" && exit 1) \
    && echo "OK: STS shaded and S3 transport bundled."

CMD ["bash"]
