# Build stage: compiles the service and packages the executable jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
# Downloads the dependencies once, including the Nutrient Java SDK (about 360 MB).
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# Runtime stage: Java 21, Chromium for HTML conversion, document fonts.
# The native engine needs ICU and links to libjawt.so, so the full JRE is installed
# and its lib folder is added to the linker path.
FROM debian:trixie-slim
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
       openjdk-21-jre \
       chromium \
       fontconfig \
       fonts-dejavu-core \
       fonts-liberation2 \
       fonts-crosextra-carlito \
       fonts-crosextra-caladea \
       libicu76 \
       locales \
       ca-certificates \
    && sed -i 's/^# *en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen \
    && locale-gen \
    && rm -rf /var/lib/apt/lists/* \
    && echo "$(dirname "$(readlink -f "$(command -v java)")")/../lib" > /etc/ld.so.conf.d/java.conf \
    && ldconfig

# Spreadsheet number formats follow the process locale.
ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    PORT=8080 \
    JOBS_DIR=/tmp/conversion-jobs

RUN useradd --system --create-home --home-dir /home/app app
WORKDIR /app
COPY --from=build /src/target/office-conversion-service-1.0.0.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
