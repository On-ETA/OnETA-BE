# 1단계: 빌드 환경 설정
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# Gradle 빌드에 필요한 파일 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src

# 권한 부여 및 빌드 (테스트는 시간 단축을 위해 제외)
RUN chmod +x ./gradlew
RUN ./gradlew bootJar -x test

# 2단계: 실행 환경 설정
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 빌드 단계에서 생성된 jar 파일 복사
COPY --from=build /app/build/libs/app.jar app.jar

# 서버 포트 개방 (Spring Boot 기본 포트)
EXPOSE 8080

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
