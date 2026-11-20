# ==============================================================================
# Stage 1: Build & Package with Eclipse Temurin JDK 21
# ==============================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

# Pre-copy POM to leverage Docker layer caching for dependencies
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN mvn dependency:go-offline -B || true

# Copy source code and build production jar
COPY src src
RUN mvn clean package -DskipTests

# ==============================================================================
# Stage 2: Minimal Distroless/Alpine Runtime with Eclipse Temurin JRE 21
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Create a dedicated non-root user and group for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy compiled jar from builder stage
COPY --from=builder /workspace/target/wallet-ledger-*.jar app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

# Production JVM flags optimized for containerized workloads
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Container healthcheck using Spring Boot Actuator
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
