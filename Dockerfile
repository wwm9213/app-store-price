FROM --platform=$BUILDPLATFORM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B verify

FROM amazoncorretto:21-alpine
ENV LANG=C.UTF-8 TZ=Asia/Shanghai APPSTORE_DATA_DIR=/app/data
RUN apk add --no-cache tzdata && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /build/target/app-store-price-*.jar /app/app.jar
RUN mkdir /app/data && chown -R app:app /app
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD wget -q -O /dev/null http://localhost:${PORT:-8080}/api/v2/storefronts || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
