# ── Stage 1: build ────────────────────────────────────────────────────────────
# maven:3.9-eclipse-temurin-17 ships both JDK 17 and Maven — no apt install needed.
# Published for linux/amd64 and linux/arm64.
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /workspace

# Copy pom.xml first so the dependency-download layer is cached independently
# of source changes.  Re-downloaded only when pom.xml actually changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

# Extract layered jar for a smaller final image
RUN java -Djarmode=layertools \
    -jar target/ticketflow-backend-1.0.0.jar extract

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
# eclipse-temurin:17-jre (Debian-slim) supports both amd64 and arm64
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy layers in dependency-stability order (least → most likely to change)
COPY --from=builder /workspace/dependencies/          ./
COPY --from=builder /workspace/spring-boot-loader/    ./
COPY --from=builder /workspace/snapshot-dependencies/ ./
COPY --from=builder /workspace/application/           ./

# Use a non-zero numeric UID so the image is compatible with OpenShift's
# restricted-v2 SCC, which forbids UID 0 and assigns an arbitrary UID from
# the project's namespace range at runtime.  A numeric USER directive (not a
# named user that may not exist in the target runtime) satisfies both vanilla
# Kubernetes and OpenShift.
USER 1001

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "org.springframework.boot.loader.launch.JarLauncher"]
