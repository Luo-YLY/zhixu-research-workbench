# Build the Vue bundle and the Java application in isolated build stages.
# Resolve these versioned base tags to digests during the deployment review.
FROM node:24-bookworm-slim AS web
WORKDIR /source/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS java-build
WORKDIR /source
COPY backend/pom.xml backend/pom.xml
COPY backend/src/ backend/src/
COPY --from=web /source/frontend/dist/ backend/src/main/resources/static/
RUN mvn -B -ntp -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /opt/workbench
RUN mkdir -p /app/data && chown -R 10001:10001 /app/data
COPY --from=java-build --chown=10001:10001 /source/backend/target/research-workbench-backend-0.1.0.jar /opt/workbench/app.jar
COPY --chmod=0555 deploy/run.sh /opt/workbench/run.sh
USER 10001:10001
EXPOSE 18081
ENTRYPOINT ["/opt/workbench/run.sh"]
