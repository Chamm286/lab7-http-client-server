FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY src/core/AuditStore.java src/core/HttpServerCore.java src/core/UrlInspector.java src/server/ServerMain.java src/
RUN javac -encoding UTF-8 -d out \
    src/AuditStore.java src/HttpServerCore.java src/UrlInspector.java src/ServerMain.java

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/out ./out
COPY www/index.html ./www/index.html
COPY deploy/start.sh ./start.sh
RUN chmod +x /app/start.sh
EXPOSE 8080
CMD ["/app/start.sh"]
