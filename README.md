# Jarvis

Jarvis 是一个 Java 实现的个人 AI Agent 项目，提供 Web 聊天界面、工具调用、长期记忆、MCP 扩展、定时任务、Git 工作区管理和飞书机器人接入能力。项目目标是把 Agent 的“对话、执行、记忆、协作入口”拆成清晰可维护的工程模块，方便本地使用和二次开发。

## 核心能力

- **Agent Loop**：支持多轮推理、工具调用、SSE 流式输出和 Markdown 渲染。
- **工具系统**：内置文件读写、Shell 执行、Git、Cron、记忆检索、图片生成、飞书历史消息等工具。
- **MCP 扩展**：支持本地 `stdio` MCP Server，也支持外部 `sse` MCP Server。
- **Memory Service**：通过 JSONL 持久化会话，支持会话压缩、Working Memory 和长期记忆提取。
- **Web UI**：包含注册、登录、会话列表、聊天、工具确认、Worktree 管理等页面。
- **权限与确认**：使用 Sa-Token 做接口认证，对高风险工具调用提供人工确认机制。
- **Channel 接入**：支持 HTTP/Web 前端入口，也支持飞书机器人 WebSocket 长连接入口。

## 架构概览

```text
jarvis-web  ->  jarvis  ->  memory-service
   |             |              |
   |             |              +-- JSONL 会话 / 可选 PostgreSQL 向量存储
   |             +-- Agent Loop / Tools / MCP / Auth / Feishu / Cron
   +-- React + Vite
```

## 项目结构

```text
common/                  共享配置、Prompt、WebClient 等基础能力
jarvis/                  Agent 主服务，端口默认 8082
memory-service/          记忆与会话服务，端口默认 8081
jarvis-web/              React Web 客户端
workspace/skills/        可提交的技能描述文件
```

运行期数据默认不会提交到 Git，例如 `sessions/`、`data/`、`workspace/.tasks/`、`workspace/.worktrees/` 和本地密钥配置。

## 环境要求

- JDK 21+
- Maven Wrapper（仓库已包含 `./mvnw`）
- Node.js 20+
- 可选：PostgreSQL，用于认证用户表、Token 用量统计和向量存储

## 快速开始

1. 克隆仓库并进入目录。

```bash
git clone https://github.com/zhantianshuai-dev/Jarvis.git
cd Jarvis
```

2. 复制本地配置模板。

```bash
cp jarvis/src/main/resources/application-local.example.yaml jarvis/src/main/resources/application-local.yaml
cp memory-service/src/main/resources/application-local.example.yaml memory-service/src/main/resources/application-local.yaml
```

3. 填写必要环境变量或本地配置。

```bash
export LLM_API_KEY=your_llm_key
export JARVIS_AUTH_ENABLED=true
export JARVIS_AUTH_REGISTRATION_ENABLED=true
```

4. 启动 memory-service。

```bash
./mvnw spring-boot:run -pl memory-service -Dspring-boot.run.profiles=local
```

5. 启动 Jarvis 后端。

```bash
./mvnw spring-boot:run -pl jarvis -Dspring-boot.run.profiles=local
```

6. 启动前端。

```bash
cd jarvis-web
npm install
npm run dev
```

默认访问地址为 `http://127.0.0.1:5173`，后端 API 默认地址为 `http://localhost:8082`。

## 配置说明

推荐把私密配置写入 `application-local.yaml` 或环境变量，不要改动默认 `application.yaml` 中的安全默认值。

常用配置：

| 配置 | 说明 |
| --- | --- |
| `LLM_API_KEY` | Agent 和 memory-service 调用模型所需密钥 |
| `LLM_API_BASE` | 兼容 OpenAI Chat Completions 的模型服务地址 |
| `MEMORY_SERVICE_URL` | Jarvis 连接 memory-service 的地址 |
| `JARVIS_AUTH_ENABLED` | 是否启用登录认证 |
| `JARVIS_AUTH_ALLOWED_ORIGINS` | 前端跨域白名单，远程访问时需要加入对应地址 |
| `MEMORY_SERVICE_POSTGRES_ENABLED` | 是否启用 PostgreSQL 存储和向量检索 |
| `JARVIS_FEISHU_ENABLED` | 是否启用飞书 Channel |
| `ZHIPU_API_KEY` | 智谱 Web Search MCP 示例密钥 |

## MCP 配置示例

本地 MCP 使用 `stdio`，Jarvis 会启动子进程并通过标准输入/输出发送 MCP JSON-RPC 请求。

```yaml
jarvis:
  mcp:
    enabled: true
    external-servers:
      - name: filesystem
        transport: stdio
        command: npx
        args:
          - -y
          - "@modelcontextprotocol/server-filesystem"
          - ./workspace
```

外部 MCP 可使用 `sse`：

```yaml
jarvis:
  mcp:
    external-servers:
      - name: zhipu-web-search-sse
        transport: sse
        url: https://open.bigmodel.cn/api/mcp-broker/proxy/web-search/sse
        headers:
          Authorization: Bearer ${ZHIPU_API_KEY}
```

## 开发命令

```bash
./mvnw compile
./mvnw test
./mvnw spring-boot:run -pl memory-service -Dspring-boot.run.profiles=local
./mvnw spring-boot:run -pl jarvis -Dspring-boot.run.profiles=local
cd jarvis-web && npm run build
```

## 安全提示

- 不要提交 `application-local.yaml`、`.env`、API Key、Token、会话数据或本地运行日志。
- 如果密钥曾经推送到公开仓库，请立即在对应平台轮换密钥。
- 对外开放服务前，请启用 `JARVIS_AUTH_ENABLED=true` 并限制 `JARVIS_AUTH_ALLOWED_ORIGINS`。
- Shell、Git、MCP、飞书等工具具备真实执行能力，建议仅授予可信用户访问。

## 贡献

欢迎提交 Issue 和 Pull Request。提交前建议至少执行：

```bash
./mvnw test
cd jarvis-web && npm run build
```

请保持变更聚焦，并在 PR 中说明修改目的、影响范围、测试结果和必要截图。

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
