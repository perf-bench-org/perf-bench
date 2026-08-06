# syntax=docker/dockerfile:1

# --- сборка ------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

# Зеркало Maven для сборки внутри закрытого контура. Пустое значение — сборка идёт напрямую
# с Maven Central (так собирают снаружи, чтобы потом перенести образ).
#   docker compose build --build-arg MAVEN_MIRROR_URL=https://nexus.example/repository/maven-public
ARG MAVEN_MIRROR_URL=""

WORKDIR /build

RUN if [ -n "$MAVEN_MIRROR_URL" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s\n' \
        '<settings>' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>internal</id>' \
        '      <name>closed-loop mirror</name>' \
        "      <url>${MAVEN_MIRROR_URL}</url>" \
        '      <mirrorOf>*</mirrorOf>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' > /root/.m2/settings.xml; \
    fi

# Слой зависимостей кэшируется отдельно от исходников: правка кода не тянет повторную загрузку.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp package

# --- runtime -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /build/target/loadtest.jar /app/loadtest.jar

# Каталоги монтируются снаружи; создаются здесь, чтобы контейнер стартовал и без монтирования.
RUN mkdir -p /config /data /results

ENTRYPOINT ["java", "-jar", "/app/loadtest.jar"]
CMD ["/config/test.properties"]
