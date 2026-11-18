# 迁移到嵌入式 TongWeb 7（替换内嵌 Tomcat）

> 目标：把 `xin_test` 的 Servlet 容器从 Spring Boot 内嵌 Tomcat 换成东方通 TongWeb 7 嵌入式版，
> **保持 jar 部署方式不变**（`java -jar` + systemd + Caddy 反代 + MCPWorld 注册地址全部不变）。

---

## 1. 影响面评估

| 层面 | 是否需要改 | 说明 |
|------|-----------|------|
| MCP 协议代码（`com.xin.test.mcp.*`） | ❌ 不需要 | 只用 `javax.servlet.http.HttpServletResponse` 和 Spring MVC 标准注解，无 Tomcat 私有 API |
| 业务代码（controller/service/mapper） | ❌ 不需要 | 纯 Spring MVC + MyBatis |
| `pom.xml` 依赖 | ✅ 需要 | 排除 `spring-boot-starter-tomcat`，引入 TongWeb 嵌入式容器 |
| `application.yml` | ⚠️ 大概率不需要 | 见第 5 节「`server.*` 属性映射」 |
| 部署脚本 / 端口 / 反代 | ❌ 不需要 | 仍为 `java -jar --server.port=9090`，Caddy `handle /mcp*` 不变 |
| 公网 URL / MCPWorld 注册 | ❌ 不需要 | 仍为 `https://<tunnel-domain>/mcp` |

**关键结论**：这是"换容器不换形状"的迁移，改动集中在构建层，业务与协议零改动。

---

## 2. 需要向东方通获取的包

依据《TongWeb V7.0 嵌入式版 JavaEE 标准容器用户指南》：

**环境要求**

| 组件 | 要求 | 本项目 |
|------|------|--------|
| JDK | 8+（HTTP/2 需 8u252+） | ✅ 编译目标 1.8（但服务器跑 JDK 17，见风险清单）|
| Spring Boot | 2.0.x ~ 2.7.x | ✅ 2.4.3 |
| JavaEE 规范 | Servlet 4.0 / JSP 2.3 / EL 3.0 | ✅ 与本项目匹配 |

**发行包**：`tongweb-embed-7.0.E.{n}.zip`（找东方通或公司信创/中间件管理员要）

关键依赖坐标（官方 starter，**直接给 Spring Boot 用**）：

```
com.tongweb.springboot : tongweb-spring-boot-starter-2.x : 7.0.E.{n}
```

补充包（本项目**不需要**，仅备查）：

- `tongweb-spring-boot-websocket-2.x` —— 用 WebSocket 才加
- `tongweb-spring-boot-jsp-2.x` —— 用 JSP 才加（本项目纯 REST，不需要）

---

## 3. 安装到本地仓库 / 私服

zip 解压后目录里有现成脚本，**不要手动 install-file**：

```bash
# Windows
installMavenJar.bat

# Linux
./installMavenJar.sh

# 推到公司 Nexus 私服
./deployMavenJar.sh http://127.0.0.1:8081/nexus/content/repositories/central/ central
```

脚本会把 starter 及其全部依赖装进本地仓库（Windows 上是 `%USERPROFILE%\.m2\repository`，
本项目 Maven 实际用的是 `D:\maven_repo\repository`，注意确认脚本安装位置与 `settings.xml` 的 `localRepository` 一致）。

安装成功的标志：

```bash
ls D:/maven_repo/repository/com/tongweb/springboot/tongweb-spring-boot-starter-2.x/
```

能看到 `7.0.E.{n}` 目录即成功。

---

## 4. pom.xml 改动

### 4.1 排除内嵌 Tomcat

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-tomcat</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

> 只排除 `spring-boot-starter-tomcat` 即可，不要排除整个 web starter。

### 4.2 引入 TongWeb 嵌入式 starter

```xml
<dependency>
    <groupId>com.tongweb.springboot</groupId>
    <artifactId>tongweb-spring-boot-starter-2.x</artifactId>
    <version>${tongweb.version}</version>
</dependency>
```

### 4.3 用 Maven profile 隔离（本项目已配置）

见 `pom.xml` 中的 `<profile><id>tongweb</id>`：默认构建仍走 Tomcat，加 `-Ptongweb` 才切换容器。

```bash
mvn clean package -Ptongweb -DskipTests    # 换容器构建
mvn clean package -DskipTests              # 原 Tomcat 构建（回滚路径）
```

---

## 5. 接入方式（二选一）

### 方式 A：官方 Spring Boot 适配包（首选）

若东方通提供 starter，引入后通常会自动注册 `ServletWebServerFactory`，
Spring Boot 的 `TomcatServletWebServerFactory` 因 `@ConditionalOnMissingBean(ServletWebServerFactory.class)`
自动退让（back off），`server.port` / `server.servlet.context-path` 一般仍生效。

**验证点**：启动日志中不应出现 `Tomcat initialized with port(s)`，应出现 TongWeb 的容器启动日志。

### 方式 B：编程式创建容器（无 starter 时）

主类改为手动构造 TongWeb 容器并注册为 Bean：

```java
@Bean
public ServletWebServerFactory servletContainer() {
    // 以 TongWeb 嵌入式 API 为准，通常是 TongWeb 自己的 Factory 实现
    // 例如 return new TongWebServletWebServerFactory();
    throw new UnsupportedOperationException("按 TongWeb 嵌入式的 Factory 类实现");
}
```

具体类名需查东方通发行包的 `javadoc` / 示例工程，不要凭猜写。

---

## 6. `server.*` 属性映射（务必实测）

TongWeb 嵌入式若实现了 Spring Boot 的 `ServletWebServerFactory`，则：

- `server.port` ✅ 生效
- `server.servlet.context-path` ✅ 生效
- `server.servlet.encoding.*` ✅ 生效
- `server.tomcat.*` ❌ 无意义（本项目未使用，安全）
- `server.max-http-header-size`、`server.compression.*` 等需实测

若 `server.port` 不生效（TongWeb 用自己的 `tongweb.*` 前缀），则部署命令要从

```bash
java -jar app.jar --server.port=9090
```

改为

```bash
java -jar app.jar --tongweb.http.port=9090   # 前缀以东方通文档为准
```

systemd 单元 `xin-test-mcp.service` 里的 `ExecStart` 需同步调整。

---

## 7. 风险清单

| 风险 | 影响 | 应对 |
|------|------|------|
| **JDK 版本** | TongWeb 7 官方支持 JDK 8/11；当前服务器为 **Java 17** | 服务器装 JDK 8/11 并切 `JAVA_HOME`，或确认 TongWeb 7.x 是否已适配 17 |
| **servlet-api 冲突** | 双份 `javax.servlet` jar → `LinkageError` / `NoSuchMethodError` | 排除 TongWeb 包内的 servlet-api，或反之排除 Boot 的 |
| **jackson / slf4j 版本冲突** | 容器自带旧版 jackson 覆盖 Boot 的 2.11.4，JSON 序列化异常 | 确保容器 jar 不暴露这些传递依赖（exclusions） |
| **编码** | 中文错误信息（如「fileId 不能为空」）乱码 | `application.yml` 加 `server.servlet.encoding.force-response: true`、`charset: UTF-8` |
| **License** | 嵌入式版需授权文件，缺失时启动报错 | 向东方通申请 license，确认放置路径（通常 `TongWeb/license.dat` 或系统属性指定） |
| **日志冲突** | TongWeb 自带 JUL/logback 配置覆盖 Boot 的 `logging.*` | 指定 `logging.config`，或关闭容器日志接管 |

---

## 8. 验证清单（沿用现有测试）

容器替换后必须全部复跑：

```bash
# 1. 探活
curl -s http://localhost:9090/mcp/health

# 2. 未授权应 401（配置了 auth-token 时）
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:9090/mcp \
  -H "Content-Type: application/json" -d '{}'

# 3. initialize
curl -s -X POST http://localhost:9090/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "X-MCP-Key: <token>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}'

# 4. tools/list、tools/call、缺参、未知工具、GET /mcp 405（同上，略）

# 5. 中文回归（重点查乱码）
curl -s ... -d '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"download_file_unstructured","arguments":{}}}'
#   应返回 "fileId 不能为空"，不是 "?????"

# 6. WorkBuddy 端到端：在对话里说「下载 F20261117099」
```

---

## 9. 回滚

改动集中在 `.m2` 依赖和 `pom.xml`，回滚只需：

```bash
git checkout pom.xml
mvn clean package -DskipTests
```

默认 profile（Tomcat）保持可用，是天然的回滚路径 —— 这也是建议用 profile 而不是直接替换的原因。
