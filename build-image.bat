@ECHO OFF
SET previous=%cd%
CD %~dp0
CALL mvnw.cmd clean package -Pnative -DskipTests --define quarkus.native.container-build=true --define quarkus.container-image.build=true --define quarkus.native.builder-image=quay.io/quarkus/ubi-quarkus-mandrel:21.3-java11
docker build -f src\main\docker\Dockerfile.jvm -t sprugit/rest-service .
CD %previous%