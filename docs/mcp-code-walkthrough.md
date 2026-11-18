# xin_test MCP 包：从入口到返回的完整代码讲解（初学者版）

> 讲解对象：`D:/workspace/xin_test/src/main/java/com/xin/test/mcp/` 下全部 12 个类，
> 外加入口类 `XinTestApplication.java`（理解初始化必需）。
> 建议打开源码，对照本文的行号一段段读。
>
> 约定：文中 `L12` 表示源码第 12 行；「Bean」= 由 Spring 容器创建和管理的对象；
> 「JSON-RPC」= 一种用 JSON 表示"我要调用哪个方法"的简单协议。

---

## 0. 开始之前：哪些概念必须懂，哪些可以先跳过

### 必须掌握的 5 个概念

| 概念 | 一句话说明 | 在本项目里对应什么 |
|------|-----------|------------------|
| Spring Bean | 加了 `@Component` 的类，启动时由框架创建好，别人要用时框架自动递过来 | 几乎所有 mcp 类 |
| 构造器注入 | 类需要别人帮忙时，在构造方法上写参数，Spring 自动把对应 Bean 传进来 | `McpController(McpProtocolHandler, ...)` |
| JSON-RPC 2.0 | 请求体里用 `method` 字段说明要干什么，`id` 用来配对响应 | `McpProtocolHandler` 的全部工作 |
| JSON Schema | 用 JSON 描述"工具的参数长什么样"，给大模型看 | `inputSchema()` / `outputSchema()` |
| ThreadLocal | 每个线程独占的小抽屉，往里放东西只有本线程能取到 | `McpCallContextHolder` |

### 了解即可，不必深究

- **Jackson**：一个 JSON 序列化库。`JsonUtils` 已经封装好了，你只需要会调 `toJson()` 和 `readTree()`。
- **Base64**：把二进制（文件）变成纯文本的编码方式，MCP 规范要求资源用这种形式传输。
- **ResponseEntity**：Spring MVC 表示"HTTP 响应"的对象，可以指定状态码、响应头、响应体。
- **Servlet 容器**：Tomcat / TongWeb，负责把 HTTP 请求转成 Java 方法调用。

### 可以直接忽略的部分

- `pom.xml` 里的传递依赖（Jackson、slf4j、Tomcat 等是怎么被带进来的）
- 业务包 `controller` / `service` / `mapper` / `entity` 的内部实现
- `docs/` 下的提交文档、`postman/` 目录

---

## 1. 启动阶段：这些类是怎么"活起来"的

### 1.1 入口类 `XinTestApplication`

```java
L9  @SpringBootApplication
L10 public class XinTestApplication {
L12     public static void main(String[] args) {
L13         SpringApplication.run(XinTestApplication.class, args);
L14     }
L15 }
```

**逐行作用**：

- **L9 `@SpringBootApplication`**：这是一个"组合开关"，打开它等于同时打开了三件事：
  1. 告诉框架"从这里开始扫描"——会以这个类所在的 `com.xin.test` 包为起点，往下找所有带 `@Component` / `@RestController` 的类；
  2. 自动配置——看到 classpath 里有 Spring MVC 就自动配好 Web 环境，有 Jackson 就自动配好 JSON；
  3. 读取 `application.yml` 里的配置。
- **L12 `main` 方法**：程序入口，`java -jar` 或 IDEA 点运行就是从这里开始。
- **L13 `SpringApplication.run(...)`**：把框架启动起来。框架会扫包、创建对象、绑定配置、启动 Tomcat（或 TongWeb）。这一步做完，下面所有类才存在。

**调用时机**：只在进程启动时执行一次，跟 MCP 请求无关。

### 1.2 启动时对象是怎么被创建和串起来的

下面这些类在启动阶段被 Spring 扫描到并创建：

| 类 | 靠什么被创建 | 创建时需要什么 |
|----|-------------|---------------|
| `McpServerProperties` | `@Component` (L9) | 框架自动把 `application.yml` 里 `mcp.server.*` 的值填进去 |
| `DownloadFileUnstructuredTool` | `@Component` (L25) | 需要 `FileService` 和 `McpServerProperties`，框架从容器里拿 |
| `McpToolRegistry` | `@Component` (L14) | 需要 `List<McpTool>`——框架把所有 `McpTool` 实现塞进一个列表传进来 |
| `McpProtocolHandler` | `@Component` (L29) | 需要 `McpToolRegistry` 和 `McpServerProperties` |
| `McpController` | `@RestController` (L33，本质也是 `@Component`) | 需要 `McpProtocolHandler`、`McpToolRegistry`、`McpServerProperties` |

**依赖链图**（箭头方向 = "需要谁"）：

```
McpController ──需要──> McpProtocolHandler ──需要──> McpToolRegistry
      │                        │                          │
      └────────────────────────┴──────需要──────────────────┘
                               ↓
                     McpServerProperties ← 读 application.yml

McpToolRegistry ──收集──> DownloadFileUnstructuredTool ──需要──> FileService
```

**关键设计意图**：`McpToolRegistry` 的构造参数是 `List<McpTool>`（L19）。这是 Spring 的一个特性——当构造参数是一个接口列表时，框架会把容器里**所有**该接口的实现都收集进来。所以以后你新增一个工具，只要写一个类 `implements McpTool` 并加 `@Component`，它会自动出现在工具列表里，**不用改任何现有代码**。这就是"开闭原则"的体现。

### 1.3 配置绑定 `McpServerProperties`

```java
L9  @Component
L10 @ConfigurationProperties(prefix = "mcp.server")
L11 public class McpServerProperties {
```

**逐行作用**：

- **L10**：`@ConfigurationProperties(prefix = "mcp.server")` 的意思是："把 yml 里 `mcp.server` 开头的配置，按名字对应到本类的同名字段"。例如 yml 里的 `auth-token: ""` 会填到 `authToken` 字段（横线命名自动转成驼峰命名）。
- **L14-L24** 是 7 个字段和默认值：

| 字段 | yml 中的键 | 默认值 | 作用 |
|------|-----------|--------|------|
| `name` | `mcp.server.name` | `xin-test-mcp` | initialize 时告诉客户端"我是谁" |
| `version` | `mcp.server.version` | `1.0.0` | 同上 |
| `protocolVersion` | `mcp.server.protocol-version` | `2025-06-18` | 客户端版本不在支持列表时的兜底版本 |
| `authToken` | `mcp.server.auth-token` | `""`（空） | 预共享密钥，空表示不校验 |
| `maxFileSizeBytes` | `mcp.server.max-file-size-bytes` | 10MB | 工具返回文件的大小上限 |
| `defaultMimeType` | `mcp.server.default-mime-type` | `application/octet-stream` | MIME 映射失败时的兜底值 |

- **L26-L72** 是标准的 getter/setter。**Spring 靠 setter 把 yml 的值写进来**，所以这些 setter 不能删。初学者不需要逐行读，知道"字段 + getter/setter"是 Java 类的标准写法即可。

**状态变化**：这个类在启动时被填充一次，之后整个进程里是同一个对象、值不再变。它是"只读配置"。

---

## 2. 一次调用的完整流程（先看全貌，再看细节）

以用户说"帮我下载 F20261117099"为例，实际发生的事情：

```
① WorkBuddy 发 HTTP POST 到 /mcp，请求体是 JSON-RPC 字符串
        ↓
② Servlet 容器转给 McpController.handle(...)
        ↓
③ 鉴权检查（X-MCP-Key 或 Authorization）
        ↓
④ 把 5 个 Agent-* 请求头装进 McpCallContext，存入 ThreadLocal
        ↓
⑤ 生成或沿用 Mcp-Session-Id，写到响应头
        ↓
⑥ 调用 McpProtocolHandler.handle(body, context)
        ↓
⑦ 解析 JSON → 判断是单条还是批量 → 判断是通知还是请求
        ↓
⑧ 按 method 分发：initialize / ping / tools/list / tools/call
        ↓
⑨ tools/call 分支：McpToolRegistry.find("download_file_unstructured")
        ↓
⑩ DownloadFileUnstructuredTool.call(arguments)：校验 → 读文件 → 拼结果
        ↓
⑪ McpToolResult.toMap() → 变成普通 Map
        ↓
⑫ 包成 JSON-RPC 响应：{"jsonrpc":"2.0","id":...,"result":{...}}
        ↓
⑬ 转回 JSON 字符串 → ResponseEntity → 客户端
        ↓
⑭ finally 块清空 ThreadLocal（重要！）
```

**这个流程体现了典型的分层设计**：传输层只管 HTTP，协议层只管 JSON-RPC 规则，工具层只管业务，三者互相不知道对方的细节。好处是换传输（HTTP → SSE）或换工具（PDF → Word）时，其他层不用动。

---

## 3. 传输层：`McpController` 逐行讲解

**类职责**：HTTP 入口。做四件事——接收请求、鉴权、采集上下文、把协议层的返回值包装成 HTTP 响应。

### 3.1 字段与构造器（L36-L48）

```java
L36 private static final Logger log = LoggerFactory.getLogger(McpController.class);
L38 private final McpProtocolHandler protocolHandler;
L39 private final McpToolRegistry toolRegistry;
L40 private final McpServerProperties properties;
L42 public McpController(McpProtocolHandler protocolHandler,
L43                      McpToolRegistry toolRegistry,
L44                      McpServerProperties properties) {
L45     this.protocolHandler = protocolHandler;
L46     this.toolRegistry = toolRegistry;
L47     this.properties = properties;
L48 }
```

- **L36**：日志工具，`log.info/warn/error` 输出到控制台。`static final` 表示全类共享一个实例。
- **L38-L40**：三个依赖，都用 `final` 修饰——赋值后不能改，避免运行中被误改。
- **L42-L48**：构造器注入。Spring 启动时看到这三个参数，就把对应 Bean 传进来（这就是 1.2 节说的机制）。

### 3.2 主方法 `handle()`（L50-L86）

**调用时机**：每次客户端发 `POST /mcp` 都会进到这里。

**参数**（L51-L60）：

| 参数 | 来源 | 说明 |
|------|------|------|
| `body` | HTTP 请求体 | JSON-RPC 请求字符串，`required=false` 表示空体也允许进来（由协议层决定怎么报错） |
| `tenantId` ... `runId` | 5 个 `Agent-*` 请求头 | Agent 平台透传的上下文，`required=false` 表示没有也行 |
| `authorization` | `Authorization` 头 | 鉴权方式一：Bearer Token |
| `mcpKey` | `X-MCP-Key` 头 | 鉴权方式二：预共享密钥 |
| `mcpSessionId` | `Mcp-Session-Id` 头 | 客户端带回的会话 ID，没有就新建一个 |
| `response` | Servlet 原生响应对象 | 只用来设置 `Mcp-Session-Id` 响应头 |

**返回值**：`ResponseEntity<String>`——HTTP 状态码 + 响应头 + JSON 字符串响应体。

**逐行讲解**：

```java
L62  if (!authorized(authorization, mcpKey)) {
```
调用私有方法做鉴权。如果没通过……

```java
L63      log.warn("MCP 鉴权失败，来源未通过预共享密钥校验");
L64      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
L65              .contentType(MediaType.APPLICATION_JSON)
L66              .body(errorJson(-32001, "Unauthorized"));
L67  }
```
- L63：记一条警告日志（方便排查是谁在乱调）。
- L64-L66：返回 **401 未授权**，`errorJson()` 生成一个符合 JSON-RPC 格式的错误对象。**注意：L67 的大括号在这一段末尾**，鉴权失败后直接 return，后面的代码不会执行——这是典型的"卫语句"写法，让主流程不嵌套在 if 里。

```java
L69  McpCallContext context = buildContext(tenantId, agentId, userId, sessionId, runId);
```
把 5 个请求头字符串打包成一个对象（详见 3.3）。

```java
L70  String responseSessionId = mcpSessionId != null && !mcpSessionId.isEmpty()
L71          ? mcpSessionId : UUID.randomUUID().toString();
```
**会话 ID 的处理**：客户端带了就用带的，没带就生成一个随机的 UUID。用途是让客户端在后续请求里能带上同一个 ID，表明"还是我"。这里不验证 ID 是否合法、也不保存状态——是**无状态会话**，只做透传。

```java
L72  response.setHeader("Mcp-Session-Id", responseSessionId);
```
把会话 ID 写进 HTTP 响应头，客户端下次请求要带回来。

```java
L74  McpCallContextHolder.set(context);
```
**关键一步**：把上下文放进 ThreadLocal。从这里开始，同一个请求处理线程里的任何代码调用 `McpCallContextHolder.get()` 都能拿到它——工具层就是这么读到租户 ID 的。

```java
L75  try {
```
开始 try 块，目的是保证 L84 的 `clear()` 一定执行。

```java
L76      String result = protocolHandler.handle(body, context);
```
把请求体交给协议层。返回值是 JSON 字符串。

```java
L77      if (result == null) {
L78          return ResponseEntity.status(HttpStatus.ACCEPTED).build();
L79      }
```
**边界处理**：`result == null` 表示这是一条"通知"（没有 id 的请求，规范规定不返回响应体）。返回 **202 Accepted**，只有状态码没有内容。

```java
L80      return ResponseEntity.ok()
L81              .contentType(MediaType.APPLICATION_JSON)
L82              .body(result);
```
正常情况：200 + `Content-Type: application/json` + JSON 字符串。

```java
L83  } finally {
L84      McpCallContextHolder.clear();
L85  }
```
**极其重要**：无论 try 里正常返回还是抛异常，finally 都会执行，把 ThreadLocal 清空。不清会怎样？Web 服务器的线程是复用的（线程池），下一个请求可能拿到同一个线程，读到上一个请求的租户 ID —— **数据串号**，是安全漏洞。这是本项目最值得记住的一行。

### 3.3 私有方法 `authorized()`（L132-L148）

**时机**：每次 `handle()` 和 `tools()` 调用开头。

**参数**：两个请求头字符串。**返回**：`boolean`， true 表示允许。

```java
L133 String token = properties.getAuthToken();
L134 if (token == null || token.trim().isEmpty()) {
L135     return true;
L136 }
```
**边界处理**：配置里没设密钥（本地开发场景）→ 直接放行。注意这是"开发默认值"，部署公网前必须配置非空密钥。

```java
L137 if (mcpKey != null && token.equals(mcpKey.trim())) {
L138     return true;
L139 }
```
方式一：请求头 `X-MCP-Key` 的值（去掉首尾空格）和配置密钥完全相等 → 放行。

```java
L140 if (authorization != null) {
L141     String value = authorization.trim();
L142     if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
L143         value = value.substring(7).trim();
L144     }
L145     return token.equals(value);
L146 }
L147 return false;
```
方式二：解析 `Authorization` 头。
- L142：`regionMatches(true, ...)` 的第一个参数 `true` 表示**忽略大小写**，所以 `bearer xxx`、`BEARER xxx` 都能识别；`0, 7` 表示从第 0 个字符开始比对 7 个字符（"Bearer " 的长度）。
- L143：去掉 "Bearer " 前缀，留下真正的 token。
- L145：和配置密钥比对。
- L147：两种方式都不匹配 → 拒绝。

**设计意图**：同时支持两种鉴权头，是为了兼容不同平台的调用习惯（有的平台用标准的 `Authorization: Bearer`，有的用自定义的 `X-MCP-Key`）。

### 3.4 私有方法 `buildContext()`（L150-L159）

**时机**：鉴权通过后立即调用。**参数**：5 个请求头字符串。**返回**：`McpCallContext` 对象。

```java
L151 McpCallContext context = new McpCallContext();
L152 context.setTenantId(tenantId);
L153 context.setAgentId(agentId);
L154 context.setUserId(userId);
L155 context.setSessionId(sessionId);
L156 context.setRunId(runId);
L157 return context;
```
纯粹的数据搬运：new 一个对象，5 个 setter 填值，返回。没有校验、没有默认值——缺的头就是 null。这是 BE04 技术方案要求的上下文透传。

### 3.5 其他三个端点

**`stream()`（L91-L97）** — `GET /mcp`。
```java
L93  return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
L94          .header(HttpHeaders.ALLOW, "POST")
L95          .contentType(MediaType.APPLICATION_JSON)
L96          .body(errorJson(-32000, "SSE stream is not provided, use POST /mcp"));
```
返回 **405 Method Not Allowed**，并在 `Allow` 响应头里写明"只支持 POST"。**为什么这么写？** MCP 的 Streamable HTTP 传输规范允许服务端不提供 GET 长连接流（SSE），此时必须对 GET 返回 405。这是规范合规的一部分，不是偷懒。有些 MCP 客户端会先试探性发个 GET，收到 405 就知道"这个服务只走 POST"。

**`health()`（L102-L110）** — `GET /mcp/health`，免鉴权。
```java
L104 Map<String, Object> result = new LinkedHashMap<>();
L105 result.put("status", "UP");
...
L109 return ResponseEntity.ok(JsonUtils.toJson(result));
```
拼一个 `{status, server, version, transport}` 的 Map 转成 JSON。给运维探活、负载均衡健康检查用。**为什么用 `LinkedHashMap` 而不是 `HashMap`？** 前者保留插入顺序，输出 JSON 的字段顺序稳定，便于人眼阅读和 diff 比对。

**`tools()`（L115-L130）** — `GET /mcp/tools`，**需要鉴权**（和 POST 一致）。这是项目自造的调试端点，不属于 MCP 规范。返回已注册工具的完整描述。当初这里曾漏了鉴权，后来补上（L118-L123），避免工具清单被任意人查看。

**`errorJson()`（L161-L171）** — 私有工具方法。
```java
L162 Map<String, Object> error = new LinkedHashMap<>();
L163 error.put("code", code);
L164 error.put("message", message);
L166 Map<String, Object> body = new LinkedHashMap<>();
L167 body.put("jsonrpc", "2.0");
L168 body.put("id", null);
L169 body.put("error", error);
L170 return JsonUtils.toJson(body);
```
按 JSON-RPC 2.0 规范拼错误响应：`{jsonrpc, id, error:{code, message}}`。这几个端点都用同一套格式。

---

---

## 4. 协议层：`McpProtocolHandler` 逐行讲解

**类职责**：JSON-RPC 2.0 的"总调度"。它收到的都是字符串，返回的也是字符串；中间负责解析请求、判断类型、按 `method` 分发、捕获异常、拼响应。**它完全不知道 HTTP 的存在**——这是分层的好处。

### 4.1 常量与字段（L32-L43）

```java
L34 private static final List<String> SUPPORTED_VERSIONS = Arrays.asList(
L35         "2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
```
**协议版本白名单**。MCP 规范 evolution 很快，历史上出现过 4 个版本。客户端发来 `initialize` 时会带一个它支持的版本，如果在这个列表里就采用它（向后兼容），否则用配置里的默认版本（兜底）。

```java
L37 private final McpToolRegistry toolRegistry;
L38 private final McpServerProperties properties;
```
两个依赖：`tools/list` 和 `tools/call` 需要查注册表；`initialize` 需要读配置里的服务名和版本。

### 4.2 入口方法 `handle()`（L48-L65）

**时机**：`McpController.handle()` 的 L76 调用它。
**参数**：`body`（JSON-RPC 请求字符串）、`context`（调用上下文，实际本协议层暂时只往下传给工具调用路径）。
**返回**：JSON 响应字符串；**`null` 表示不需要返回响应体**（通知/批量通知）。

```java
L49  if (body == null || body.trim().isEmpty()) {
L50      return JsonUtils.toJson(errorResponse(null, McpErrorCode.INVALID_REQUEST, "Empty request body"));
L51  }
```
**边界处理 1**：空请求体。`trim()` 去掉空白字符后判断，避免一个只有空格的请求混过去。返回 JSON-RPC 标准错误 `-32600`。

```java
L53  JsonNode root;
L54  try {
L55      root = JsonUtils.readTree(body);
L56  } catch (Exception e) {
L57      return JsonUtils.toJson(errorResponse(null, McpErrorCode.PARSE_ERROR, "Parse error"));
L58  }
```
**边界处理 2**：JSON 语法错误。`JsonNode` 是 Jackson 的"任意 JSON 树"表示。`readTree` 解析失败会抛异常，这里捕获并转成 `-32700 Parse error`。**为什么不直接让异常冒泡？** 因为规范要求协议错误要以 JSON-RPC error 对象的形式返回，而不是 HTTP 500。

```java
L60  if (root.isArray()) {
L61      return handleBatch((ArrayNode) root, context);
L62  }
```
**边界处理 3**：批量请求。JSON-RPC 2.0 允许请求体是数组，表示一次发多条。遇到数组走专门的批量处理（见 4.3）。

```java
L63  ObjectNode response = handleSingle(root, context);
L64  return response == null ? null : JsonUtils.toJson(response);
```
单条请求：交给 `handleSingle` 处理。如果它返回 null（通知），就也返回 null；否则序列化成字符串。

### 4.3 批量处理 `handleBatch()`（L67-L79）

```java
L68  if (requests.size() == 0) {
L69      return JsonUtils.toJson(errorResponse(null, McpErrorCode.INVALID_REQUEST, "Empty batch"));
L70  }
```
**边界处理 4**：空数组 `[]` 是非法请求，返回 `-32600`。

```java
L71  ArrayNode responses = JsonUtils.mapper().createArrayNode();
L72  for (JsonNode request : requests) {
L73      ObjectNode response = handleSingle(request, context);
L74      if (response != null) {
L75          responses.add(response);
L76      }
L77  }
```
逐条调用 `handleSingle`，**只把有响应的加进结果数组**（通知没有响应，跳过）。

```java
L78  return responses.size() == 0 ? null : JsonUtils.toJson(responses);
```
**边界处理 5**：如果整批都是通知，一个响应都没有 → 返回 null（HTTP 层会变成 202）。否则返回 JSON 数组。

### 4.4 单条处理 `handleSingle()`（L81-L108）

```java
L82  JsonNode idNode = request.get("id");
L83  Object id = idNode == null ? null : JsonUtils.mapper().convertValue(idNode, Object.class);
```
取出 `id` 字段。规范要求响应里要带回同样的 id，客户端才能把响应和请求配对。`convertValue` 把 JsonNode 转成普通 Java 对象（可能是 String 或 Integer）。

```java
L85  if (!request.isObject()) {
L86      return errorResponse(id, McpErrorCode.INVALID_REQUEST, "Invalid request");
L87  }
```
**边界处理 6**：请求体不是对象（比如 `"hello"` 或 `123`）→ 非法请求。注意 L82 在检查类型之前就调了 `request.get("id")`，对于非对象 JsonNode，`get()` 返回 null，不会崩，所以顺序是安全的。

```java
L88  String method = request.path("method").asText(null);
L89  if (method == null) {
L90      return errorResponse(id, McpErrorCode.INVALID_REQUEST, "Missing method");
L91  }
```
`path("method")` 比 `get()` 安全——字段不存在时返回一个"MissingNode"而不是 null，`asText(null)` 会返回传入的默认值 null。**边界处理 7**：没有 `method` 字段 → 非法请求。

```java
L93  boolean notification = !request.has("id");
```
**关键判断**：没有 `id` 字段 = 通知（notification）。这是 JSON-RPC 的约定：不需要响应的请求不带 id。

```java
L94  try {
L95      if (notification) {
L96          handleNotification(method);
L97          return null;
L98      }
L99      Object result = dispatch(method, request.get("params"), context);
L100     return successResponse(id, result);
L101 } catch (McpException e) {
L102     log.warn("MCP 协议错误，method={}, code={}, message={}", method, e.getCode(), e.getMessage());
L103     return errorResponse(id, e.getCode(), e.getMessage());
L104 } catch (Exception e) {
L105     log.error("MCP 处理异常，method={}", method, e);
L106     return errorResponse(id, McpErrorCode.INTERNAL_ERROR, "Internal error");
L107 }
```
这段是**错误处理的分层**，初学者要重点理解：
- **L95-L97**：通知只记录日志，不返回响应（符合规范）。
- **L99**：正常请求分发给 `dispatch()`。
- **L100**：成功 → 包成 success 响应。
- **L101-L103**：捕获 `McpException`（协议层主动抛的、知道原因的错误，如"未知方法"），把里面的错误码原样返回。**注意日志级别是 warn**——这类错误是"客户端传错了"，不是系统故障。
- **L104-L106**：捕获其他所有异常（如空指针、数据库异常），统一返回 `-32603 Internal error`。**不把异常细节返回给客户端**，避免泄露内部信息；但用 `log.error(..., e)` 把完整堆栈记到服务端日志。**日志级别是 error**——这类是"我们自己出问题了"。

### 4.5 通知处理 `handleNotification()`（L110-L118）

```java
L111 if ("notifications/initialized".equals(method)
L112         || "notifications/cancelled".equals(method)
L113         || "notifications/roots/list_changed".equals(method)) {
L114     log.debug("收到 MCP 通知: {}", method);
L115     return;
L116 }
L117 log.debug("收到未识别的 MCP 通知: {}", method);
```
**设计意图**：MCP 规范定义了几种标准通知，客户端发来时服务端只需"收到即可"，不用回复也不用做事。这里把它们列出来记 debug 日志。未知通知也只记日志——**不报错**，因为通知本来就不该有响应，报错反而违反规范。这是一种"宽容处理"。

### 4.6 核心分发 `dispatch()`（L120-L134）

```java
L121 if ("initialize".equals(method)) {
L122     return buildInitializeResult(params);
L123 }
L124 if ("ping".equals(method)) {
L125     return new LinkedHashMap<String, Object>();
L126 }
L127 if ("tools/list".equals(method)) {
L128     return buildToolsListResult();
L129 }
L130 if ("tools/call".equals(method)) {
L131     return handleToolsCall(params);
L132 }
L133 throw McpException.methodNotFound(method);
```
**整个 MCP 服务的"路由表"**。就这么简单——4 个 if 判断，每个 `method` 对应一个方法。这是最直白的实现方式，初学者一眼能看懂。

- **L124-L125 `ping`**：返回空 `{}`。心跳检测用，客户端发过来只要不报错就说明服务活着。
- **L133**：都不匹配 → 抛 `McpException` 带 `-32601 Method not found`，会被 4.4 的 catch 转成 JSON-RPC 错误响应。

### 4.7 握手响应 `buildInitializeResult()`（L136-L156）

**时机**：客户端第一次连接时调用，整个会话通常只有一次。
**参数**：`params`（initialize 请求的参数，可能含客户端支持的协议版本）。
**返回**：一个 Map，最终变成 JSON 响应。

```java
L137 String requested = params == null ? null : params.path("protocolVersion").asText(null);
```
取出客户端要求的协议版本。**边界处理**：`params` 可能为 null，所以先判空再调 `path()`。

```java
L138 String protocolVersion = SUPPORTED_VERSIONS.contains(requested)
L139         ? requested : properties.getProtocolVersion();
```
**版本协商**：客户端的版本在白名单里 → 用它的（兼容客户端）；不在 → 用服务端配置的默认版本。**状态变化**：协商结果会被客户端记住，后续请求不再带版本。

```java
L141 Map<String, Object> toolsCapability = new LinkedHashMap<>();
L142 toolsCapability.put("listChanged", false);
L143 Map<String, Object> capabilities = new LinkedHashMap<>();
L144 capabilities.put("tools", toolsCapability);
```
声明服务端能力：`tools.listChanged = false` 意思是"我的工具列表不会动态变化，你缓存起来没问题"。客户端据此优化行为。

```java
L146 Map<String, Object> serverInfo = new LinkedHashMap<>();
L147 serverInfo.put("name", properties.getName());
L148 serverInfo.put("version", properties.getVersion());
```
返回服务身份，来自配置。

```java
L150 Map<String, Object> result = new LinkedHashMap<>();
L151 result.put("protocolVersion", protocolVersion);
L152 result.put("capabilities", capabilities);
L153 result.put("serverInfo", serverInfo);
L154 result.put("instructions", "按 fileId 提供非结构化文件下载能力，供文档解析类 Agent 调用。");
L155 return result;
```
拼最终响应。**`instructions` 字段很重要**——它是给大模型看的"使用说明"，告诉模型这个服务是干嘛的。模型会在决定要不要调用工具时参考它。这也是为什么写 MCP 服务时 description / instructions 要写得清楚——它们是"给 AI 的文档"。

### 4.8 工具列表 `buildToolsListResult()`（L158-L162）

```java
L159 Map<String, Object> result = new LinkedHashMap<>();
L160 result.put("tools", toolRegistry.descriptors());
L161 return result;
```
把注册表生成的工具描述列表塞进 `tools` 字段。自己几乎不做事，全委托给 `McpToolRegistry`。

### 4.9 工具调用 `handleToolsCall()`（L164-L180）

**时机**：客户端说"我要调用某个工具"时。
**参数**：`params`——应包含 `name`（工具名）和 `arguments`（参数对象）。
**返回**：`McpToolResult.toMap()` 的结果。

```java
L165 if (params == null || !params.isObject()) {
L166     throw McpException.invalidParams("params 不能为空");
L167 }
```
**边界处理 8**：没有 params 或 params 不是对象 → `-32602 Invalid params`。

```java
L168 String name = params.path("name").asText(null);
L169 if (name == null || name.trim().isEmpty()) {
L170     throw McpException.invalidParams("工具名不能为空");
L171 }
```
**边界处理 9**：没有工具名或空字符串 → `-32602`。

```java
L172 McpTool tool = toolRegistry.find(name);
L173 if (tool == null) {
L174     throw McpException.invalidParams("未知工具: " + name);
L175 }
```
**边界处理 10**：工具名不存在 → `-32602`。注意这里用的是"参数错误"而不是"方法不存在"，因为工具名是 `tools/call` 的参数，不是 JSON-RPC 的 method。

```java
L177 Map<String, Object> arguments = toArguments(params.get("arguments"));
L178 McpToolResult toolResult = tool.call(arguments);
L179 return toolResult.toMap();
```
- L177：把 `arguments` 这个 JsonNode 转成普通 `Map<String, Object>`，传给工具。
- L178：**真正执行业务**——多态调用，具体跑哪个类取决于 `tool` 是谁（`DownloadFileUnstructuredTool`）。
- L179：把结果对象转成 Map，交给上层序列化。

### 4.10 参数转换 `toArguments()`（L182-L191）

```java
L184 if (argumentsNode == null || argumentsNode.isNull()) {
L185     return new LinkedHashMap<>();
L186 }
```
**边界处理 11**：没传 arguments → 返回空 Map（而不是 null），让工具自己决定怎么报"缺参"。**这样设计让错误由工具层用自己的业务错误码报出**（`isError=true` + `INVALID_ARGUMENT`），而不是协议错误——语义更准确。

```java
L187 if (!argumentsNode.isObject()) {
L188     throw McpException.invalidParams("arguments 必须为对象");
L189 }
```
**边界处理 12**：arguments 是数组或字符串 → `-32602`。

```java
L190 return JsonUtils.mapper().convertValue(argumentsNode, Map.class);
```
把 JsonNode 转成 `Map`。**类型转换规则**：JSON 字符串 → String，数字 → Integer/Double，布尔 → Boolean，嵌套对象 → 嵌套 Map。

### 4.11 响应包装 `successResponse()` / `errorResponse()`（L193-L209）

```java
L194 ObjectNode response = JsonUtils.objectNode();
L195 response.put("jsonrpc", "2.0");
L196 response.set("id", JsonUtils.mapper().valueToTree(id));
L197 response.set("result", JsonUtils.mapper().valueToTree(result));
L198 return response;
```
成功响应固定三件套：`jsonrpc: "2.0"` + `id`（原样带回）+ `result`（业务结果）。`valueToTree()` 把任意 Java 对象转成 JsonNode。

```java
L201 ObjectNode errorResponse(Object id, int code, String message) {
L202 ObjectNode response = JsonUtils.objectNode();
L203 response.put("jsonrpc", "2.0");
L204 response.set("id", ...);
L205 ObjectNode error = response.putObject("error");
L206 error.put("code", code);
L207 error.put("message", message);
L208 return response;
```
错误响应：`{jsonrpc, id, error:{code, message}}`。注意 id 可能是 null（如解析失败时还没读到 id），规范要求此时用 null。

### 4.12 `toolNames()`（L211-L219）

调试辅助方法，遍历注册表取所有工具名。目前没有生产代码调用它，可以视为预留的排查接口。

---

---

## 5. 工具层：真正干活的地方

### 5.1 `McpTool` 接口——工具的"契约"（全 38 行）

**类职责**：定义一个 MCP 工具必须长什么样。它本身没有实现，只是一份规范。

```java
L11  String name();             // 工具唯一标识，如 download_file_unstructured
L14  String title();            // 人类可读的展示名
L17  String description();      // 给大模型看的说明，决定模型何时调用它
L20  Map<String, Object> inputSchema();   // 入参的 JSON Schema
L23  default Map<String, Object> outputSchema() { return null; }  // 出参 schema，可选
L28  default Map<String, Object> annotations() { return null; }   // 工具提示，可选
L37  McpToolResult call(Map<String, Object> arguments);           // 执行工具
```

**逐项说明**：

- **L23、L28 的 `default` 关键字**：接口里的默认实现。实现类可以不覆盖这两方法，直接用 null。这是 Java 8 的特性——**新加接口方法时不会强制所有实现类都改一遍**，向后兼容。
- **L17 `description()` 特别重要**：大模型靠这段话判断"用户这个问题该不该用这个工具"。写得模糊，模型就不会调用；写得过于宽泛，模型会乱调用。本项目的描述里特意写了"仅处理本次请求指定的文件 ID，不遍历目录、不返回文件列表"——这是在**约束模型的行为边界**，安全上的考量。
- **L37 `call()`**：接收参数 Map，返回结果对象。所有业务逻辑都在这里。

**设计意图**：有了这层接口，`McpToolRegistry` 就能用统一的方式对待所有工具，不需要知道每个工具的具体类型。

### 5.2 `McpToolRegistry`——工具的"花名册"（全 57 行）

**类职责**：启动时收集所有工具，运行时提供"列出全部"和"按名查找"两个能力。

```java
L14 @Component
L15 public class McpToolRegistry {
L17     private final Map<String, McpTool> tools = new LinkedHashMap<>();
```

- **L17**：用 Map 存工具，key 是工具名，value 是工具对象。`LinkedHashMap` 保证遍历顺序 = 注册顺序，输出稳定。

```java
L19     public McpToolRegistry(List<McpTool> toolList) {
L20         for (McpTool tool : toolList) {
L21             McpTool previous = tools.put(tool.name(), tool);
L22             if (previous != null) {
L23                 throw new IllegalStateException("MCP 工具名重复: " + tool.name());
L24             }
L25         }
L26     }
```

**构造器逐行**（启动时执行一次）：

- **L19**：参数是 `List<McpTool>`。**Spring 的黑魔法**：容器里所有实现了 `McpTool` 的 Bean 会被自动收集成列表传进来。所以新增工具只要写类 + 加 `@Component`，这里不用改。
- **L21**：`Map.put()` 会返回"原来这个 key 对应的旧值"。第一次放进去时旧值是 null。
- **L22-L24**：**边界处理**——如果旧值不是 null，说明有两个工具用了同一个名字，直接抛异常让**启动失败**。这叫"fail fast"（快速失败）：与其等到运行时调用错了才发现，不如启动时就暴露。命名冲突是配置错误，不该带到生产环境。

```java
L28     public Collection<McpTool> all() { return tools.values(); }
L32     public McpTool find(String name) { return tools.get(name); }
```
两个简单查询方法。`find()` 找不到返回 null，调用方（`handleToolsCall` L173）负责检查。

```java
L39     public List<Map<String, Object>> descriptors() {
L40         List<Map<String, Object>> list = new ArrayList<>();
L41         for (McpTool tool : tools.values()) {
L42             Map<String, Object> descriptor = new LinkedHashMap<>();
L43             descriptor.put("name", tool.name());
L44             descriptor.put("title", tool.title());
L45             descriptor.put("description", tool.description());
L46             descriptor.put("inputSchema", tool.inputSchema());
L47             if (tool.outputSchema() != null) {
L48                 descriptor.put("outputSchema", tool.outputSchema());
L49             }
L50             if (tool.annotations() != null) {
L51                 descriptor.put("annotations", tool.annotations());
L52             }
L53             list.add(descriptor);
L54         }
L55         return list;
L56     }
```

**`descriptors()` 逐行**（`tools/list` 调用时执行）：

- L43-L46：把工具的 4 个核心字段填进描述 Map。**这段就是 Agent 看到的"工具说明书"的生成过程**。
- L47-L52：**可选字段只在非 null 时才放进去**。这样输出的 JSON 更干净，也符合 MCP 规范（这些字段是可选的）。
- L55：返回列表，最终出现在 `tools/list` 响应的 `tools` 字段里。

### 5.3 `DownloadFileUnstructuredTool`——唯一的具体工具（全 186 行）

**类职责**：实现"按 fileId 下载文件（多类型，元数据由上游 fileType/fileName 归一化）"这个业务。前置的 `McpTool` 接口定义了要写哪些方法，这里逐个填实。

#### 5.3.1 声明与元数据（L25-L55）

```java
L25 @Component
L26 public class DownloadFileUnstructuredTool implements McpTool {
L30     public static final String TOOL_NAME = "download_file_unstructured";
L32     private final FileService fileService;
L33     private final McpServerProperties properties;
L35     public DownloadFileUnstructuredTool(FileService fileService, McpServerProperties properties) {
L36         this.fileService = fileService;
L37         this.properties = properties;
L38     }
```

- **L25**：`@Component` 让它成为 Bean，才会被 `McpToolRegistry` 的 `List<McpTool>` 收集到。**漏掉这个注解，工具就不会上线**——这是最常犯的错。
- **L30**：工具名常量。用常量而不是字符串字面量，避免拼写错误。
- **L32-L38**：注入业务服务和配置。两个依赖都是 `final`。

```java
L41-L43  name()        → 返回 TOOL_NAME
L46-L48  title()       → "下载非结构化文件"
L51-L55  description() → 一段给大模型看的话
```
这三个方法是对接口的实现。**`description()` 的内容要字斟句酌**——它是模型决定调用与否的唯一依据。本项目在末尾强调"仅处理本次请求指定的文件 ID，不遍历目录、不返回文件列表"，是主动给模型划边界，防止模型拿着工具做越权的事。

#### 5.3.2 入参 Schema `inputSchema()`（L58-L69）

```java
L59      Map<String, Object> props = new LinkedHashMap<>();
L60      props.put("fileId", property("string",
L61              "非结构化文件标识（等同业务系统的 fileNo），例如 F20261116001", 1, 128));
L63      Map<String, Object> schema = new LinkedHashMap<>();
L64      schema.put("type", "object");
L65      schema.put("properties", props);
L66      schema.put("required", Collections.singletonList("fileId"));
L67      schema.put("additionalProperties", false);
L68      return schema;
```

**这段手工拼出一个 JSON Schema**，等价于：

```json
{
  "type": "object",
  "properties": {
    "fileId": {"type":"string", "description":"...", "minLength":1, "maxLength":128}
  },
  "required": ["fileId"],
  "additionalProperties": false
}
```

- **L66 `required`**：告诉模型"fileId 必填"。
- **L67 `additionalProperties: false`**：告诉模型"不要传多余参数"。这是一层防御——即使模型自作主张传了 `dirPath` 之类的字段，规范上也是不允许的。
- **L166-L177 `property()` 私有辅助方法**：根据传入的 type / description / min / max 生成一个属性描述 Map。min/max 为 null 时不放进去。**这是自己写的 schema 构造器**，Spring AI 里改由 `@ToolParam` 注解自动生成。

**调用时机**：`McpToolRegistry.descriptors()` 被 `tools/list` 触发时调用。模型在决定参数时读的就是这份 schema。

#### 5.3.3 出参 Schema 与提示 `outputSchema()` / `annotations()`（L72-L95）

```java
L74-L77  定义 4 个返回字段：fileId / mimeType / sizeBytes / fileName
L82      required = fileId, mimeType, sizeBytes
```
注意 `fileName` 是可选的（不在 required 里），因为 mock 数据可能没有文件名。

```java
L87-L94  annotations()：
L90      readOnlyHint = true       → 告诉模型"这工具只读，不改数据"
L91      destructiveHint = false   → 不会删除东西
L92      idempotentHint = true     → 调多次结果一样
L93      openWorldHint = false     → 不访问外部开放世界
```
**这 4 个布尔值是给大模型的"行为提示"**，不是强制约束。模型看到 `readOnlyHint=true` 就知道可以放心调用、不用担心副作用。这是 MCP 规范里比较新的字段，手写实现时容易漏，本项目做得很完整。

#### 5.3.4 核心执行 `call()`（L98-L141）

**时机**：`McpProtocolHandler.handleToolsCall()` 的 L178 调用。
**参数**：`arguments`——已经从 JsonNode 转成 Map。**返回**：`McpToolResult` 对象。

```java
L99      McpCallContext context = McpCallContextHolder.get();
```
**从 ThreadLocal 取出上下文**（由 `McpController.handle()` L74 放入）。这样后面打日志时能带上租户、Agent、用户、会话、运行五个 ID。注意工具本身不需要把这 5 个值当参数传——它们通过 ThreadLocal "暗中"传递，这就是它的价值。

```java
L101     String fileId = arguments == null ? null : asTrimmedString(arguments.get("fileId"));
L102     if (fileId == null || fileId.isEmpty()) {
L103         return failure(null, "INVALID_ARGUMENT", "fileId 不能为空", context);
L104     }
L105     if (fileId.length() > 128) {
L106         return failure(fileId, "INVALID_ARGUMENT", "fileId 长度超出限制", context);
L107     }
```
**边界处理 13、14**：缺参和超长。注意返回的是 `failure(...)`——一个 **`isError=true` 的业务错误**，不是抛异常。这是关键设计：JSON-RPC 层的错误（协议错）和业务层的错误（工具执行失败）分开表达。客户端收到后知道"工具调用成功了，但业务上失败了，错误信息在 content 里"。

`asTrimmedString()`（L179-L181）把任意值转成字符串并去首尾空格。即使模型传了数字 123，也会变成 "123"。

```java
L109     long start = System.currentTimeMillis();
L110     try {
L111         FileContentDTO file = fileService.downloadFileDetail(fileId);
```
记录开始时间用于耗时统计，然后调业务服务。**这一步才真正读文件**（服务层把上游 `byte[] + fileType + fileName` 经 `MimeTypeResolver` 映射、清洗后组装为 `FileContentDTO`）。

```java
L112         if (file == null || file.getContent() == null || file.getContent().length == 0) {
L113             return failure(fileId, "FILE_EMPTY", "文件内容为空，请补传后重试", context);
L114         }
L115         if (file.getSizeBytes() > properties.getMaxFileSizeBytes()) {
L116             return failure(fileId, "FILE_TOO_LARGE",
L117                     "文件超过大小限制（" + properties.getMaxFileSizeBytes() + " 字节）", context);
L118         }
```
**边界处理 15、16**：空文件和超大文件。大小上限来自配置（默认 10MB）。**为什么要防超大文件？** 因为要把整个文件转成 Base64 放进 JSON 响应，10MB 文件会变成约 13.3MB 的字符串，再大就会拖垮客户端和模型上下文。

```java
L120         String mimeType = isBlank(file.getMimeType()) ? properties.getDefaultMimeType() : file.getMimeType();
L121         String fileName = isBlank(file.getFileName()) ? fileId + "." + fallbackType : file.getFileName();
```
**兜底处理**：业务系统没给 MIME 类型就用配置的默认值，没给文件名就拼一个。**`isBlank()`**（L183-L185）判断 null 或纯空白，比单纯判 null 更严格。

```java
L123         Map<String, Object> structured = new LinkedHashMap<>();
L124         structured.put("fileId", fileId);
L125         structured.put("mimeType", mimeType);
L126         structured.put("sizeBytes", file.getSizeBytes());
L127         structured.put("fileName", fileName);
```
拼结构化返回数据。这个 Map 最终出现在响应的 `structuredContent` 字段——**给程序读**。而 `content[]` 里的资源是给模型读的。MCP 规范同时提供两种形式，兼顾人和机器。

```java
L129         McpToolResult result = McpToolResult.success()
L130                 .embedResource(buildUri(fileId), mimeType, file.getContent())
L131                 .structuredContent(structured);
```
链式调用构造结果：放一个嵌入式资源 + 结构化数据。

`buildUri()`（L156-L164）：把 fileId 做 URL 编码（防止特殊字符破坏 URI 格式），拼成 `file:///unstructured/<fileId>` 这样的伪 URI。**为什么用 `file:///` 开头？** MCP 规范要求嵌入式资源必须有 URI 标识，这里用伪 URI 只是为了满足格式要求，不代表真的去读本地文件系统。

```java
L133         log.info("MCP 下载成功，fileNo={}, size={}B, cost={}ms, context[{}]",
L134                 fileId, file.getSizeBytes(), System.currentTimeMillis() - start, context);
L135         return result;
```
打成功日志（含耗时和上下文），返回结果。

```java
L136     } catch (Exception e) {
L137         log.error("MCP 下载失败，fileNo={}, cost={}ms, context[{}]", ..., e);
L139         return failure(fileId, "DOWNLOAD_FAILED", "读取文件失败，请稍后重试或补传文件", context);
L140     }
```
**边界处理 17**：任何未预期的异常（数据库挂了、IO 错误等）→ 记完整堆栈 + 返回 `DOWNLOAD_FAILED` 业务错误。**注意错误信息给的是通用提示，不暴露内部异常细节**——安全做法。

#### 5.3.5 失败构造 `failure()`（L143-L154）

```java
L144     Map<String, Object> structured = new LinkedHashMap<>();
L145     structured.put("fileId", fileId);
L146     structured.put("errorCode", errorCode);
L147     structured.put("message", message);
L149     log.warn("MCP 下载返回业务错误，fileNo={}, errorCode={}, context[{}]", ...);
L150     return McpToolResult.success()
L151             .addText(message)
L152             .structuredContent(structured)
L153             .error(true);
```
**所有业务错误走同一个出口**，保证格式一致。注意 L150 调用的是 `success()` 而不是什么 `failure()` 工厂——先用正常构造器拼内容，最后 L153 用 `.error(true)` 打上错误标记。这是建造者模式的常见写法。

**状态变化**：`McpToolResult` 内部有个 `error` 布尔字段，默认 false；`.error(true)` 把它改成 true，序列化时变成 JSON 里的 `isError: true`。客户端靠这个字段区分"成功但内容为空"和"真的失败了"。

### 5.4 `McpToolResult`——返回值的"建造者"（全 74 行）

**类职责**：按 MCP 规范拼装 `tools/call` 的返回结构。

```java
L16     private final List<Map<String, Object>> content = new ArrayList<>();
L17     private Map<String, Object> structuredContent;
L18     private boolean error;
```
三个内部状态：**content 内容列表**（可以有多个文本/资源条目）、**structuredContent 结构化数据**、**error 错误标记**。

```java
L20     public static McpToolResult success() { return new McpToolResult(); }
```
静态工厂方法，比直接 `new` 读起来更像"创建一个成功结果"。实际上只是 new 了一下。

```java
L27-L38  embedResource(uri, mimeType, data)：
L28         Map<String, Object> resource = new LinkedHashMap<>();
L29-L31     放 uri / mimeType / blob（blob = Base64 编码后的字节数组）
L33-L35     外层放 type="resource" 和 resource 对象
L36         content.add(item);
L37         return this;   ← 返回自己，支持链式调用
```
**这一步是"文件字节变成 MCP 资源"的核心**：`Base64.getEncoder().encodeToString(data)` 把二进制字节转成纯文本字符串。为什么？因为 JSON 只能安全传输文本，直接塞二进制会损坏。

```java
L43-L49  addText(text)：往 content 里加一个 type="text" 的文本条目
L51-L54  structuredContent(...)：设置结构化数据字段
L56-L59  error(boolean)：设置错误标记
L61-L63  isError()：读取错误标记
```

```java
L65-L73  toMap()：
L67      result.put("content", content);
L68-L70  if (structuredContent != null) result.put("structuredContent", structuredContent);
L71      result.put("isError", error);
L72      return result;
```
**输出格式固定为** `{content: [...], structuredContent?: {...}, isError: boolean}`。`structuredContent` 可选——为 null 时整个字段不出现。

---

## 6. 支撑设施：4 个小类

### 6.1 `McpCallContext`（全 69 行）——数据的"信封"

5 个字段（L9-L17）：`tenantId`（租户）、`agentId`（Agent）、`userId`（用户）、`sessionId`（平台会话）、`runId`（运行实例）。对应 Agent 平台规定的 5 个 `Agent-*` 请求头。

- **L19-L57 的 getter/setter**：纯样板代码，快速扫过即可。
- **L59-L62 `isEmpty()`**：5 个字段全为 null 才算空。目前没人调用，是预留方法。
- **L64-L68 `toString()`**：把 5 个字段拼成字符串。日志里 `context[{}]` 打印的就是它——**重写 toString 是为了日志可读**，否则会打印出 `com.xin...McpCallContext@1a2b3c` 这种没用的东西。

### 6.2 `McpCallContextHolder`（全 24 行）——ThreadLocal 包装

```java
L8      private static final ThreadLocal<McpCallContext> HOLDER = new ThreadLocal<>();
L13     public static void set(McpCallContext context)   { HOLDER.set(context); }
L17     public static McpCallContext get()               { HOLDER.get(); }
L21     public static void clear()                       { HOLDER.remove(); }
```

**这是全项目最需要理解的小类**。

**ThreadLocal 是什么**：可以想象成"每个线程有一个专属抽屉"。A 线程往里放东西，B 线程看不到。Web 服务器用线程池处理请求，一个线程处理完 A 请求后会被分配给 B 请求——所以**用完必须清空抽屉**（`clear()`），否则 B 请求会读到 A 请求的租户信息。

**调用链**：
- `McpController.handle()` L74 → `set()`：放入
- `DownloadFileUnstructuredTool.call()` L99 → `get()`：取出
- `McpController.handle()` L84（finally）→ `clear()`：清空

**为什么不用方法参数一路传下去？** 技术上可以，但要在 `handle → protocolHandler.handle → handleSingle → dispatch → handleToolsCall → tool.call` 每一层都加一个参数，代码会很啰嗦，而且协议层本来不该关心平台上下文。ThreadLocal 实现了"跨层隐式传递"。

### 6.3 `McpErrorCode`（全 16 行）——错误码常量

`PARSE_ERROR=-32700`、`INVALID_REQUEST=-32600`、`METHOD_NOT_FOUND=-32601`、`INVALID_PARAMS=-32602`、`INTERNAL_ERROR=-32603`。这些数字是 **JSON-RPC 2.0 规范规定的**，不能自己改。用常量避免到处写"魔法数字"。

（L67 里 Controller 用的 `-32001 Unauthorized` 是本项目自定义的错误码，不在标准列表里——规范允许 -32000 以下自定义。）

### 6.4 `McpException`（全 30 行）——协议异常

```java
L6      public class McpException extends RuntimeException {
L8          private final int code;
L10         public McpException(int code, String message) {
L11             super(message);
L12             this.code = code;
L13         }
```
继承 `RuntimeException` 表示 unchecked exception（不用在方法签名上声明 throws）。多带一个 `code` 字段，用来告诉上层"这是哪种协议错误"。

```java
L19     invalidParams(message)  → 用 INVALID_PARAMS 码
L23     methodNotFound(method)  → 用 METHOD_NOT_FOUND 码
L27     internalError(message)  → 用 INTERNAL_ERROR 码
```
三个静态工厂，调用方写 `throw McpException.invalidParams("...")` 比 `throw new McpException(-32602, "...")` 更清楚。**这些异常最终都被 `McpProtocolHandler.handleSingle()` 的 catch（L101-L103）转成 JSON-RPC 错误响应**。

### 6.5 `JsonUtils`（全 38 行）——JSON 工具箱

```java
L13     private static final ObjectMapper MAPPER = new ObjectMapper()
L14             .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
```
**唯一的重点**：`FAIL_ON_UNKNOWN_PROPERTIES=false` 表示"JSON 里有我不认识的多余字段时，不要报错"。这让客户端可以传额外字段而不导致服务崩溃——**宽容性设计**。ObjectMapper 是线程安全的，所以做成 static 单例重复使用。

- **L16 私有构造器**：防止别人 `new JsonUtils()`——工具类只该用静态方法。
- **L19 `mapper()`**：暴露底层 ObjectMapper（协议层要用它做更底层的操作）。
- **L23 `objectNode()`**：创建一个空 JSON 对象节点。
- **L27 `readTree()`**：字符串 → JsonNode。
- **L31-L37 `toJson()`**：对象 → JSON 字符串；序列化失败包成 `IllegalStateException`。

**需要掌握吗？** 不需要了解 Jackson 内部原理，会用这 4 个静态方法就够了。

---

---

## 7. 状态变化：哪些东西会变，什么时候变

初学者读代码容易只盯着"做了什么"，忽略"状态怎么变"。这个项目里有 4 类状态：

### 7.1 进程级状态（启动时设定，之后不变）

| 状态 | 存放在哪 | 何时设定 | 是否变化 |
|------|---------|---------|---------|
| 服务名/版本/协议版本/密钥/文件大小上限 | `McpServerProperties` 的字段 | Spring 启动时读 yml 填充 | ❌ 不变 |
| 工具注册表 | `McpToolRegistry.tools` Map | 构造器里收集所有 `@Component` 工具 | ❌ 不变 |
| ObjectMapper 配置 | `JsonUtils.MAPPER` | 类加载时 | ❌ 不变 |

**含义**：配置改了必须重启服务才生效；工具加了新的也要重启才会被收集。

### 7.2 请求级状态（每个请求一份，用完即弃）

| 状态 | 存放在哪 | 生命周期 |
|------|---------|---------|
| 平台上下文（5 个 Agent-* 头） | ThreadLocal 里的 `McpCallContext` | `Controller.set()` → 工具 `get()` → `Controller.clear()` |
| 会话 ID | 请求头传入或新生成 UUID | 本请求内有效，通过响应头传给客户端 |
| 工具结果对象 | `McpToolResult` 的 3 个字段 | 从 `success()` 到 `toMap()`，用完丢弃 |

**ThreadLocal 状态流转的时间线**（最关键）：

```
线程 T1 处理请求 A：
  set(contextA)  ← 抽屉放进 A 的资料
    ...工具执行，get() 拿到 contextA...
  clear()        ← 抽屉清空 ✓

线程 T1 被复用处理请求 B：
  set(contextB)  ← 抽屉放进 B 的资料
    ...get() 拿到 contextB（不会串到 A）✓
  clear()
```

**如果忘了 clear()**：请求 B 可能读到 contextA，日志里记错租户，甚至如果将来有人用上下文做权限判断，就会造成越权。这是本项目的**头号安全要点**。

### 7.3 协议级状态（客户端保存，服务端不保存）

- **协议版本**：`initialize` 时协商一次，客户端记住。
- **会话 ID**：服务端每次都写响应头，但**不存任何会话数据**——这是"无状态服务器"设计。好处是容易水平扩容（多台机器随便转发），坏处是服务端无法感知会话中断。
- **客户端能力声明**：`capabilities` 是客户端告诉服务端的（比如"我支持采样"），本项目没用到。

### 7.4 错误标记状态

`McpToolResult.error` 布尔值：默认 false → `.error(true)` 改成 true → 序列化成 JSON 的 `isError`。这个字段一旦为 true，内容里的文本应当被当作错误信息读，而不是正常结果。

---

## 8. 异常与边界处理全景表

这张表把散落在各处的 `if` 和 `catch` 汇总，方便对照：

| # | 触发条件 | 处理位置 | 结果 | 类型 |
|---|---------|---------|------|------|
| 1 | 请求体为空 | `McpProtocolHandler.handle()` L49 | JSON-RPC `-32600` | 协议错误 |
| 2 | JSON 语法错误 | 同上 L54 | `-32700 Parse error` | 协议错误 |
| 3 | 请求体是数组 | 同上 L60 | 走批量处理 | 正常分支 |
| 4 | 空数组 `[]` | `handleBatch()` L68 | `-32600` | 协议错误 |
| 5 | 整批都是通知 | 同上 L78 | 返回 null → HTTP 202 | 正常分支 |
| 6 | 请求不是对象 | `handleSingle()` L85 | `-32600` | 协议错误 |
| 7 | 缺 `method` | 同上 L89 | `-32600` | 协议错误 |
| 8 | 未知 `method` | `dispatch()` L133 | `-32601 Method not found` | 协议错误 |
| 9 | 未预期异常 | `handleSingle()` L104 | `-32603 Internal error`（细节只进日志） | 协议错误 |
| 10 | 鉴权失败 | `McpController.authorized()` L62 | HTTP 401 + `-32001` | HTTP 层拦截 |
| 11 | params 为空/非对象 | `handleToolsCall()` L165 | `-32602` | 协议错误 |
| 12 | 工具名为空 | 同上 L169 | `-32602` | 协议错误 |
| 13 | 工具名不存在 | 同上 L173 | `-32602` | 协议错误 |
| 14 | arguments 非对象 | `toArguments()` L187 | `-32602` | 协议错误 |
| 15 | 工具名重复 | `McpToolRegistry` 构造器 L22 | 启动失败 | 配置错误 |
| 16 | fileId 为空/超长 | `DownloadFileUnstructuredTool.call()` L102/L105 | `isError=true` + `INVALID_ARGUMENT` | **业务错误** |
| 17 | 文件内容为空 | 同上 L112 | `isError=true` + `FILE_EMPTY` | **业务错误** |
| 18 | 文件超过 10MB | 同上 L115 | `isError=true` + `FILE_TOO_LARGE` | **业务错误** |
| 19 | 下载过程抛异常 | 同上 L136 | `isError=true` + `DOWNLOAD_FAILED` | **业务错误** |
| 20 | 通知（无 id） | `handleSingle()` L95 | 只记日志，不返回 | 正常分支 |
| 21 | GET /mcp | `McpController.stream()` L91 | HTTP 405 + `Allow: POST` | 规范合规 |

**最重要的区分**（初学者最容易混）：

- **协议错误**（JSON-RPC error 字段）：请求本身有问题——格式错、方法不存在、参数不合规范。相当于"你话说错了，我没法理解"。
- **业务错误**（result 里的 `isError=true`）：请求格式完全正确，工具也执行了，但业务上失败——文件不存在、太大。相当于"我听懂了，但事情没办成"。

两套机制并存是 MCP 规范的设计，这样客户端能区分"该改请求重试"还是"该换业务策略"。

---

## 9. 初学者常见疑问

**Q1：为什么到处用 `LinkedHashMap` 而不是更常见的 `HashMap`？**
A：`HashMap` 遍历顺序不确定，`LinkedHashMap` 保持插入顺序。JSON 响应字段顺序稳定，便于人眼阅读、diff 比对、客户端缓存。功能上没差别，是工程上的讲究。

**Q2：为什么 `tools/list` 要单独一个 method，不能直接在 initialize 里把工具一起返回？**
A：MCP 规范把"握手"和"能力发现"分开。这样客户端可以在会话中途重新拉取工具列表（虽然本项目声明了 `listChanged=false` 不会变），也让 initialize 响应保持轻量。

**Q3：为什么通知不返回响应？HTTP 202 是什么意思？**
A：JSON-RPC 规范规定通知是"fire and forget"（发完不管）。服务端如果返回内容，客户端可能会困惑（拿不到 id 配对）。HTTP 202 Accepted 表示"收到了，但没产生需要返回的结果"。

**Q4：为什么要做批量请求处理？本项目用得上吗？**
A：规范支持，客户端可能一次发多条（比如同时 initialize + tools/list）。虽然 WorkBuddy 目前是一条条发，但实现完整能避免兼容性问题。这是"按规范实现"的好处。

**Q5：`McpToolResult.success()` 为什么在有错误时也用它？**
A：它是个纯粹的构造器，名字只是表达"开始构建一个结果"。错误标记由最后的 `.error(true)` 决定。这是建造者模式的常见写法，不用纠结名字。

**Q6：`McpCallContextHolder` 用 ThreadLocal，如果工具里开了新线程会怎样？**
A：普通 `ThreadLocal` 不会自动传给子线程，新线程里 `get()` 拿到 null。本项目工具是同步执行、不开新线程，所以没问题。将来如果改成异步，要换成 `InheritableThreadLocal` 或手动传递。

**Q7：`/mcp/tools` 和 `tools/list` 有什么区别？**
A：`tools/list` 是 JSON-RPC 方法，走 `POST /mcp`，是标准 MCP 流程的一部分；`/mcp/tools` 是项目自造的 `GET` 调试端点，给运维看，客户端不会调它。两者数据来自同一个 `McpToolRegistry.descriptors()`。

**Q8：为什么要手写而不是用 Spring AI 注解？**
A：项目是 JDK 8 + Spring Boot 2.4.3，Spring AI 要求 JDK 17 + Boot 3.x。手写是一套精简替代方案，只实现用得到的部分（tools + initialize + ping）。详见上一轮对话的对照说明。

---

## 10. 依赖库与生成代码：需要掌握到什么程度

| 依赖/机制 | 在本项目中的角色 | 需要掌握吗 |
|-----------|----------------|-----------|
| Spring Boot 自动配置 | 启动时创建 Bean、注入依赖、绑定 yml | **需要**：理解 `@Component`、构造器注入、`@ConfigurationProperties` 三件事即可 |
| Spring MVC | 把 HTTP 请求映射到 `@PostMapping` 方法 | 了解：`@RequestBody` 取请求体、`ResponseEntity` 是响应就行 |
| Jackson | JSON 解析和序列化 | 了解：`JsonUtils` 那 4 个静态方法的作用即可，内部原理不用管 |
| slf4j / logback | 日志输出 | 了解：`log.info/warn/error` 三个级别的区别（debug < info < warn < error） |
| Java 8 特性 | `default` 接口方法、Lambda、`Base64` 工具类 | 了解：`default` 方法是什么、Base64 是二进制转文本 |
| Servlet API | `HttpServletResponse`（只用来 setHeader） | 不用深究：知道它是"HTTP 响应对象"就够 |
| Maven / pom.xml | 管理依赖和构建 | 不用：本阶段不需要改 |
| MyBatis / MySQL | 业务数据层 | 不用：招聘 MCP 实现时 `FileService` 是个接口，内部是 mock |
| 项目其他包 | controller / service / mapper / dto / vo / entity | 不用：与 MCP 协议无关 |

**一句话学习路径**：先搞懂 `McpTool` 接口 → `McpToolRegistry` 收集 → `McpProtocolHandler.dispatch` 分发 → `McpController` 包 HTTP 这 4 步，其余都是细节补充。

---

## 11. 收尾：把代码读薄的三个视角

1. **一切都是字符串进出**：`McpController` 收字符串、协议层解析字符串、最后再拼成字符串。中间的对象（`JsonNode`、`McpToolResult`、Map）只是临时的中间表示。
2. **一切都是分层委托**：Controller 不解析 JSON，ProtocolHandler 不管 HTTP，Tool 不管协议。每层只做自己的事，出问题时能快速定位是哪一层。
3. **一切错误都有归属**：协议错在 `handleSingle` 的 catch 里变成 JSON error；业务错在 `Tool.call()` 里变成 `isError=true`；启动期配置错直接抛异常阻止启动。三类错误、三个出口，不混在一起。

把这三句话记住，再看这份代码就会觉得"不过如此"。

