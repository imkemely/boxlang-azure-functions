# =============================================================================
# BoxLang Azure Functions - Development Environment
# Java 21 + Maven + Azure Functions Core Tools
# =============================================================================

FROM eclipse-temurin:21-jdk-jammy

LABEL maintainer="BoxLang Azure Functions Team"
LABEL description="Development environment for BoxLang Azure Functions Runtime"

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# ---------------------------
# 1. Install system dependencies
# ---------------------------
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    wget \
    git \
    unzip \
    gnupg \
    lsb-release \
    apt-transport-https \
    ca-certificates \
    software-properties-common \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------
# 2. Install Maven 3.9.x
# ---------------------------
ARG MAVEN_VERSION=3.9.6
ENV MAVEN_HOME=/opt/maven
ENV PATH="${MAVEN_HOME}/bin:${PATH}"

RUN wget -q "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    -O /tmp/maven.tar.gz \
    && mkdir -p ${MAVEN_HOME} \
    && tar -xzf /tmp/maven.tar.gz -C ${MAVEN_HOME} --strip-components=1 \
    && rm /tmp/maven.tar.gz \
    && ln -s ${MAVEN_HOME}/bin/mvn /usr/bin/mvn

# ---------------------------
# 3. Install Azure CLI (official Microsoft install script)
# ---------------------------
RUN curl -sL https://aka.ms/InstallAzureCLIDeb | bash

# ---------------------------
# 4. Install Azure Functions Core Tools v4
# ---------------------------
RUN curl -sL https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor > /etc/apt/trusted.gpg.d/microsoft-func.gpg \
    && echo "deb [arch=amd64] https://packages.microsoft.com/ubuntu/22.04/prod jammy main" \
    > /etc/apt/sources.list.d/azure-functions.list \
    && apt-get update \
    && apt-get install -y azure-functions-core-tools-4 \
    && rm -rf /var/lib/apt/lists/*

# ---------------------------
# 5. Set up workspace
# ---------------------------
WORKDIR /workspace

# Pre-cache Maven dependencies by copying pom.xml first (Docker layer caching)
# Uncomment these lines once your pom.xml is stable:
# COPY pom.xml .
# RUN mvn dependency:go-offline -B

# ---------------------------
# 6. Verify installations
# ---------------------------
RUN echo "=== Environment Verification ===" \
    && java --version \
    && mvn --version \
    && func --version \
    && az --version | head -1 \
    && echo "=== All tools installed successfully ==="

# Azure Functions default port
EXPOSE 7071

# Keep container running for interactive dev work
CMD ["/bin/bash"]