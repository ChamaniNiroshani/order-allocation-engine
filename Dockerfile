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

# Unpack the fat jar into layers (dependencies change rarely, application classes change on every commit): a thin
# application.jar next to lib/. Class data sharing needs plain jars on the class path, not jars nested in a jar.
RUN cp target/order-allocation-engine-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination /layers

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

# Class data sharing (CDS), for faster startup on a small CPU: a training run starts the application until every bean
# exists, then exits, and the JVM saves the classes it loaded in application.jsa. It runs on the runtime image's own JVM,
# so the archive matches it. There is no database at build time, so everything that would connect to one is switched
# off, for this command only. spring.context.exit must be a -D system property: passed as an application argument it
# does nothing and the application keeps running. The time limit turns such a hang into a failed build.
RUN timeout 900 java -XX:ArchiveClassesAtExit=application.jsa -XX:+UseSerialGC -Dspring.context.exit=onRefresh \
      -jar application.jar \
      --spring.profiles.active=prod \
      --SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/training \
      --SPRING_DATASOURCE_USERNAME=training \
      --SPRING_DATASOURCE_PASSWORD=training \
      --spring.docker.compose.enabled=false \
      --spring.flyway.enabled=false \
      --spring.jpa.hibernate.ddl-auto=none \
      --spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
      --spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
      --spring.datasource.hikari.initialization-fail-timeout=-1 \
 && test -s application.jsa

USER app

# Size the heap from the container's memory limit, not the host's
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# No secrets here: the database URL, user and password are supplied as environment variables at run time.
# If the archive does not match the JVM or the jars, the JVM ignores it and starts normally, only slower.
ENTRYPOINT ["java", "-XX:SharedArchiveFile=application.jsa", "-jar", "application.jar"]
