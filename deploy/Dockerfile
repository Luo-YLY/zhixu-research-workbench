# Build the Vue bundle and the Java application in isolated build stages.
# Resolve these versioned base tags to digests during the deployment review.
FROM node:24-bookworm-slim AS web
WORKDIR /source/frontend
COPY frontend/package.json frontend/package-lock.json ./
ARG NPM_REGISTRY=https://registry.npmjs.org/
RUN npm ci --no-audit --no-fund --registry="$NPM_REGISTRY" --replace-registry-host=always --fetch-retries=5
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS java-build
WORKDIR /source
COPY backend/pom.xml backend/pom.xml
COPY backend/src/ backend/src/
COPY --from=web /source/frontend/dist/ backend/src/main/resources/static/
ARG MAVEN_MIRROR_URL=
RUN --mount=type=cache,target=/root/.m2 \
    if [ -n "$MAVEN_MIRROR_URL" ]; then \
      printf '<settings><mirrors><mirror><id>local-mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>\n' "$MAVEN_MIRROR_URL" > /tmp/maven-settings.xml && \
      mvn -B -ntp -s /tmp/maven-settings.xml -f backend/pom.xml -DskipTests package; \
    else \
      mvn -B -ntp -f backend/pom.xml -DskipTests package; \
    fi

FROM eclipse-temurin:21-jre
WORKDIR /opt/workbench
RUN mkdir -p /app/data && chown -R 10001:10001 /app/data
COPY --from=java-build --chown=10001:10001 /source/backend/target/research-workbench-backend-0.1.0.jar /opt/workbench/app.jar
COPY --chmod=0555 deploy/run.sh /opt/workbench/run.sh
USER 10001:10001
EXPOSE 18081
ENTRYPOINT ["/opt/workbench/run.sh"]
