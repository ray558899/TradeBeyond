# Multi-stage build（CLAUDE.md Part 10）：第一階段用完整的 Maven + JDK image 編譯，
# 第二階段只留執行期需要的 JRE，image 體積小很多，也不會把原始碼、Maven 快取這些
# 建置期才需要的東西一起帶進正式環境的 image。

# ---- Build stage ----
FROM maven:3.8.8-eclipse-temurin-17 AS build
WORKDIR /build

# 先只複製 pom.xml 並解析依賴，這一層才能被 Docker layer cache 命中——
# 之後只改 src 底下的程式碼，重新 build 不用每次都重新下載一次所有依賴。
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
# 測試在 CI pipeline 是獨立的一個步驟（Part 10 CI/CD 流程的第 1 步，在建置 image 之前），
# 這裡只負責打包，不重複跑一次——尤其是 Testcontainers 的整合測試需要跟 Docker daemon
# 互動，不適合、也沒必要放進 image build 這個過程裡再跑一次。
RUN mvn -B -q package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 非 root 使用者執行：容器萬一被入侵，攻擊者拿到的權限被限制在這個專用帳號，
# 不是 root，降低影響範圍。
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring

COPY --from=build /build/target/trade-beyond-api-*.jar app.jar
RUN chown spring:spring app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
