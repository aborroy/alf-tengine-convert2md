# syntax=docker/dockerfile:1.4
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

FROM python:3.11-slim

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        poppler-utils \
        tesseract-ocr \
        libleptonica-dev \
        libtesseract-dev \
        libgl1 \
        libmagic1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir docling \
    --extra-index-url https://download.pytorch.org/whl/cpu

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]