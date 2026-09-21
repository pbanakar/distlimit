# ── Stage 1: Build ──────────────────────────────────────────────
# Full Maven + JDK image to compile the project and produce a fat JAR.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy POM first — Docker caches this layer, so dependency downloads
# are skipped on subsequent builds if only source code changed.
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build (skip tests — they need Docker/Testcontainers
# which isn't available inside a Docker build)
COPY src ./src
RUN mvn package -DskipTests -B

# ── Stage 2: Runtime ───────────────────────────────────────────
# Slim JRE image — no compiler, no Maven, no source code.
# ~200MB instead of ~800MB from the build stage.
FROM eclipse-temurin:21-jre-alpine

# WHY NON-ROOT?
# Running as root inside a container is a security risk:
# 1. If an attacker exploits a vulnerability in the app, they get root
#    access to the container — and potentially to the host via container
#    escape exploits.
# 2. Root can modify system files, install packages, and access all
#    network interfaces inside the container.
# 3. Many container orchestrators (Kubernetes) enforce non-root by
#    default via PodSecurityPolicies/Standards.
# Running as a dedicated low-privilege user limits the blast radius.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=build /app/target/distributed-rate-limiter-1.0.0-SNAPSHOT.jar app.jar

# Switch to non-root user
USER appuser

EXPOSE 8080

# Use exec form so the JVM process receives SIGTERM directly for graceful shutdown
ENTRYPOINT ["java", "-jar", "app.jar"]
