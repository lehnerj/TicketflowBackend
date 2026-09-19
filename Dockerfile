# ── Stage 1: build ────────────────────────────────────────────────────────────
# eclipse-temurin:17-jdk-alpine has no arm64 variant; use the Debian-slim tag
# which is published for both linux/amd64 and linux/arm64.
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace

COPY pom.xml .
COPY src ./src

RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends maven && \
    rm -rf /var/lib/apt/lists/* && \
    mvn -q -B package -DskipTests

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
