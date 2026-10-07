# FediFerry server. Build from the repository root:
#   docker build -f server/Dockerfile -t fediferry .
# or use server/docker-compose.yml.

FROM eclipse-temurin:21-jdk AS build
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
