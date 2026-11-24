# syntax=docker/dockerfile:1

# ---- Build stage: Maven wrapper + JDK 21 -------------------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace

# Dependencies in their own layer: only re-downloaded when pom.xml or the wrapper change, not on every code change.
# mvnw is committed without the executable bit (it is added from Windows), so set it here instead of relying on the checkout.
COPY --chmod=755 mvnw ./
COPY pom.xml ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI (and need Docker for Testcontainers), so they are skipped here.
RUN ./mvnw -B -q package -DskipTests

# Split the fat jar into layers (dependencies change rarely, application classes change on every commit).
RUN java -Djarmode=tools -jar target/order-allocation-engine-*.jar extract --layers --launcher --destination /layers

# ---- Runtime stage: JRE only --------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.13_9-jre-noble
WORKDIR /app

# Non-root user (no login shell, no home directory contents)
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

# Least to most frequently changing, so a code-only change rebuilds only the last layer.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./

USER app

# Size the heap from the container's memory limit, not the host's
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# No secrets here: the database URL, user and password are supplied as environment variables at run time.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
