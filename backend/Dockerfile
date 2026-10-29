FROM eclipse-temurin:25.0.1_8-jre@sha256:9d1d3068b16f2c4127be238ca06439012ff14a8fdf38f8f62472160f9058464a
WORKDIR /app
COPY --chmod=0444 target/debt-management-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
