# Jarvis

Jarvis is a Java-based AI agent workspace with a Web UI, memory service, tool execution, scheduled tasks, skill injection, and optional channel integrations.

## Modules

- `common/`: shared infrastructure such as prompt loading and WebClient configuration.
- `memory-service/`: session persistence, memory extraction, retrieval, embeddings, and reranking.
- `jarvis/`: agent loop, tool registry, MCP integration, authentication, channels, cron jobs, worktree management, and SSE events.
- `jarvis-web/`: React/Vite web client for login, chat, sessions, permissions, and worktree views.
- `workspace/skills/`: committed skill descriptors loaded by the agent.

Runtime data such as sessions, cron state, worktrees, local databases, and private configuration is intentionally ignored by Git.

## Requirements

- JDK 21
- Maven wrapper from this repository
- Node.js 20 or newer
- Optional PostgreSQL if authentication or vector storage is enabled

## Configuration

Default configuration files are safe to commit and read secrets from environment variables.

For local development, copy the examples and fill in private values:

```bash
cp jarvis/src/main/resources/application-local.example.yaml jarvis/src/main/resources/application-local.yaml
cp memory-service/src/main/resources/application-local.example.yaml memory-service/src/main/resources/application-local.yaml
```

Common environment variables:

```bash
LLM_API_KEY=...
EMBEDDING_API_KEY=...
RERANK_API_KEY=...
PG_PASSWORD=...
JARVIS_AUTH_ENABLED=true
JARVIS_AUTH_BOOTSTRAP_PASSWORD=...
```

Do not commit `application-local.yaml`, `.env`, session files, or workspace runtime data.

## Development

Compile backend modules:

```bash
./mvnw compile
```

Run memory service:

```bash
./mvnw spring-boot:run -pl memory-service -Dspring-boot.run.profiles=local
```

Run Jarvis backend:

```bash
./mvnw spring-boot:run -pl jarvis -Dspring-boot.run.profiles=local
```

Run the web client:

```bash
cd jarvis-web
npm install
npm run dev
```

Build the web client:

```bash
cd jarvis-web
npm run build
```

## Security Notes

- Rotate any key that was ever committed to a public or shared remote.
- Keep authentication enabled when exposing Jarvis beyond localhost.
- Review tool permissions before enabling shell, Git, MCP, or external channel access for other users.
- Avoid committing generated data under `data/`, `sessions/`, `resume/`, or `workspace/` except approved `SKILL.md` files.

## License

This project is licensed under the MIT License. See `LICENSE` for details.
