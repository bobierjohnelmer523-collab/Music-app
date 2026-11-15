# Root Dockerfile for Render build when Root Directory is repository root
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /app

# Copy pom.xml and source code from backend directory
COPY backend/pom.xml .
COPY backend/src ./src

# Build production JAR without tests
RUN mvn clean package -DskipTests -B

# Stage 2: Lightweight runtime image
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Add a non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy built JAR from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Render injects PORT dynamically; default to 8080
ENV PORT=8080
EXPOSE 8080

# Run JVM with container support and random seed optimization
ENTRYPOINT ["sh", "-c", "java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${PORT} -jar app.jar"]
