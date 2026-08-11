FROM eclipse-temurin:21-jre
WORKDIR /app
# 뉴렐릭 자바 에이전트. 판번호를 박아 둔다. latest 로 두면 이미지를 다시 구울 때마다 다른 것이 들어가
# 어제와 오늘의 계측이 달라진 이유를 설명할 수 없게 된다.
ADD https://repo1.maven.org/maven2/com/newrelic/agent/java/newrelic-agent/8.21.0/newrelic-agent-8.21.0.jar /app/newrelic.jar
# CI가 build/libs 에 boot jar 하나만 남겨 전달 (plain jar 는 제거됨)
COPY build/libs/*.jar app.jar
# 에이전트 설정(Kafka 분산추적). 에이전트는 자기 jar 와 같은 디렉터리의 newrelic.yml 을 읽는다.
COPY build/libs/newrelic.yml /app/newrelic.yml
EXPOSE 8082
ENTRYPOINT ["java", "-javaagent:/app/newrelic.jar", "-jar", "/app/app.jar"]
