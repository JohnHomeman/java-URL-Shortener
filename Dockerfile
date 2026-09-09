# syntax=docker/dockerfile:1

# ==========================================
# Stage 1: Build Stage
# ==========================================
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper & pom.xml for layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./

# Ensure maven wrapper is executable
RUN chmod +x mvnw

# Download dependencies in advance to leverage Docker layer caching
RUN ./mvnw dependency:go-offline -B

# Copy project source code
COPY src ./src

# Package application jar (skip tests during container build as CI handles testing)
RUN ./mvnw clean package -DskipTests

# ==========================================
# Stage 2: Runtime Stage
# ==========================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Create a dedicated non-root user and group
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy the built jar from builder stage
COPY --from=builder /app/target/url-shortener-*.jar app.jar

# Adjust file permissions
RUN chown appuser:appgroup app.jar

# Switch to non-root user
USER appuser

# Expose HTTP port
EXPOSE 8080

# Container runtime JVM flags
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
