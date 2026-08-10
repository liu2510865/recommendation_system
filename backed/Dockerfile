# ==================== 运行阶段（预构建 JAR） ====================
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="xiaowu"
LABEL description="Recommendation System - Online Service (backed)"

RUN apt-get update && apt-get install -y --no-install-recommends curl tini && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 复制预构建的 JAR
COPY backed/target/recommendation-online-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /tmp/spark-checkpoint /data

EXPOSE 8922

ENV JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENV SPRING_PROFILES_ACTIVE=default

ENTRYPOINT ["tini", "--", "sh", "-c", "java $JAVA_OPTS -jar app.jar"]
