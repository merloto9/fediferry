# FediFerry server. Build from the repository root:
#   docker build -f server/Dockerfile -t fediferry .
# or use server/docker-compose.yml.

# The build output is plain JVM bytecode, so Gradle runs on the builder's own
# platform even for a multi-arch image; only the runtime stage is per-arch.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# No Android SDK in here: leave the app out of the build.
ENV FEDIFERRY_SERVER_ONLY=1
RUN ./gradlew --no-daemon -q :server:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --home /data fediferry && mkdir -p /data && chown fediferry /data
COPY --from=build /src/server/build/install/fediferry /opt/fediferry
USER fediferry
ENV FEDIFERRY_DATA=/data FEDIFERRY_PORT=8080
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/opt/fediferry/bin/fediferry"]
CMD ["serve"]
