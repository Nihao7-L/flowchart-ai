# ===== Stage 1: 编译打包（有 Maven + JDK）=====
# 用 Maven 官方镜像，不用本地装 Maven
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只复制 pom.xml 下载依赖（利用 Docker 层缓存：改代码不用重新下依赖）
COPY pom.xml .
RUN mvn dependency:resolve -B

# 再复制源码，编译打包（跳过测试加快速度）
COPY src ./src
RUN mvn package -DskipTests -B

# ===== Stage 2: 运行（只有 JRE，体积小）=====
FROM eclipse-temurin:17-jre

WORKDIR /app

# 从 Stage 1 复制打包好的 fat JAR
# 文件名 = artifactId-version.jar（pom.xml 里的 flowchart-0.0.1-SNAPSHOT）
COPY --from=builder /build/target/flowchart-0.0.1-SNAPSHOT.jar app.jar

# Spring Boot 默认端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
