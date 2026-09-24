# AuthServer image. Build the jar first: ./gradlew :server:shadowJar && docker build -t authserver .
# config.yml, the database and plugins/ live in /data; mount a volume there to keep them.
FROM docker.io/library/eclipse-temurin:25-jre

RUN useradd --system --home-dir /data --shell /usr/sbin/nologin authserver \
    && mkdir -p /opt/authserver /data && chown authserver /data
COPY server/build/libs/server-*-all.jar /opt/authserver/authserver.jar

USER authserver
WORKDIR /data
VOLUME /data
EXPOSE 25565
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
# exec keeps java as PID 1 so `docker stop` (SIGTERM) runs the shutdown hook.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/authserver/authserver.jar \"$@\"", "authserver"]
