# ── Stage 1: build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src

# Download deps first (cached layer)
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -q

# Build the fat jar
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user
RUN addgroup -S lab && adduser -S lab -G lab
USER lab

COPY --from=builder /app/target/db-scalability-lab-*.jar app.jar

EXPOSE 8080

# Health check used by Docker Compose depends_on
HEALTHCHECK --interval=10s --timeout=5s --retries=12 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
