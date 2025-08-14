# syntax=docker/dockerfile:1.4
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

FROM python:3-slim

# --- Install system dependencies ---
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless \
        poppler-utils \
        tesseract-ocr \
        libleptonica-dev \
        libtesseract-dev \
        libgl1 \
        libmagic1 \
    && rm -rf /var/lib/apt/lists/*

# --- Install docling and dependencies ---
RUN pip install --no-cache-dir docling \
    --extra-index-url https://download.pytorch.org/whl/cpu

# --- Add non-root user and set home directory properly ---
ARG GROUP_NAME=alfresco
ARG GROUP_ID=1001
ARG USER_NAME=alftemd
ARG USER_ID=33033
ARG HOME_DIR=/home/${USER_NAME}

RUN groupadd -g ${GROUP_ID} ${GROUP_NAME} && \
    useradd -u ${USER_ID} -g ${GROUP_NAME} -m -d ${HOME_DIR} ${USER_NAME}

# --- Set HOME explicitly and fix permissions ---
ENV HOME=${HOME_DIR}
ENV EASY_OCR_MODEL_STORAGE=${HOME_DIR}/.EasyOCR

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar
RUN chown -R ${USER_NAME}:${GROUP_NAME} /app ${HOME_DIR}

USER ${USER_NAME}

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
