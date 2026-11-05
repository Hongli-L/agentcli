# R0-3 检索质量与成本基准 · 实测结果

> 本文件由 `src/test/java/com/agentcli/bench/RagBenchTest.java` **自动生成**，跑出什么写什么。
> 未实测项一律标 `⏳ 待 key`，**不写占位数字、不引用他人示例值**。

## 测量环境

| 项 | 值 |
|---|---|
| 机器 | `<待填：CPU 型号 / 内存 / 网络环境>` |
| 操作系统 | Windows 11 10.0 / amd64 / 16 逻辑核 |
| JDK | 17.0.18 |
| embedding 模型 | `<待填：跑通后自动记录>` ⏳ 待 key |
| 对话模型 | `<待填：仅成本换算需要，本基准只统计 token 不折算金额>` |
| 样本量 | 10 条「问题 → 期望命中文件」 |
| 运行日期 | 2026-09-09 07:53（脚本自动记录；如需归档请另注 `<日期占位>`） |
| 检索范围 | `src/main/java/com/agentcli`（两条路径完全一致） |

## 结果总表

| 指标 | grep_code（关键词） | rag_search（语义 + 混合） |
|---|---|---|
| top-5 命中率 | 8/10 = 80.0% | ⏳ 待 key |
| 平均注入 tokens / 问 | 1336 | ⏳ 待 key |
| 备注 | engine=java | ⏳ rag_search 需 LLM key，已跳过 |

**检索准确率收益**：⏳ 待 key —— `rag_search` 一列跑通后才能给出对比差值，当前只有关键词基线可用。

## 上下文成本对比

| 方案 | 估算 token | 说明 |
|---|---|---|
| 整库全量塞入上下文 | 254872 | 198 个 `.java`，共 941810 字符 |
| 先检索 top-5 再注入 | 1336 | 每问平均，数据来源：grep_code（关键词） |

**单次提问上下文 token 降低 99.5%**（254872 → 1336）。

> token 估算口径：复用 `MemoryEntry.estimateTokens`（中文约 1.5 字/token，其余约 4 字符/token），**不折算金额**，避免把汇率与厂商定价写死进结论。

## 标注集与逐条明细

标注集固定 10 条，期望文件路径相对 `src/main/java/com/agentcli/`，均已核实真实存在。

### grep_code（关键词）

| # | 问题 | 期望命中文件 | 命中 | 实际 top-5 |
|---|---|---|---|---|
| 1 | ReAct 循环在哪实现 | `agent/ReActAgent.java` | ✅ | `agent/ReActAgent.java`, `agent/AgentBudget.java`, `plan/PlanExecuteAgent.java`, `agent/orchestration/AgentOrchestrator.java`, `agent/TurnUsage.java` |
| 2 | MCP 客户端怎么连 | `mcp/McpClient.java` | ❌ | `lsp/LspClient.java`, `cli/InteractiveSession.java`, `context/ContextProfile.java`, `rag/EmbeddingClientFactory.java`, `rag/OpenAiCompatibleEmbeddingClient.java` |
| 3 | SSRF 围栏怎么实现的 | `web/NetworkPolicy.java` | ✅ | `web/NetworkPolicy.java`, `tool/builtin/RagSearchTool.java`, `rag/CodeRetriever.java`, `web/WebFetcher.java`, `agent/orchestration/ReviewerAgent.java` |
| 4 | 审计日志装饰器 | `audit/AuditLog.java` | ✅ | `audit/OrchestrationTrace.java`, `audit/AuditLog.java`, `store/SqliteStore.java`, `cli/InteractiveSession.java`, `cli/Launcher.java` |
| 5 | 三层取消模型 | `agent/concurrent/CancellationToken.java` | ✅ | `cli/InteractiveSession.java`, `llm/OpenAiCompatibleClient.java`, `agent/concurrent/AgentTaskExecutor.java`, `agent/concurrent/CancellationToken.java`, `agent/ReActAgent.java` |
| 6 | RAG 向量索引 | `rag/CodeRetriever.java` | ✅ | `rag/CodeIndex.java`, `rag/CodeRetriever.java`, `rag/RagCommandHandler.java`, `rag/VectorStore.java`, `store/SqliteStore.java` |
| 7 | LSP 客户端 | `lsp/LspClient.java` | ✅ | `lsp/LspClient.java`, `cli/InteractiveSession.java`, `rag/EmbeddingClientFactory.java`, `rag/OpenAiCompatibleEmbeddingClient.java`, `web/WebFetcher.java` |
| 8 | SQLite 存储 | `store/SqliteStore.java` | ❌ | `cli/InteractiveSession.java`, `rag/VectorStore.java`, `rag/RagCommandHandler.java`, `audit/AuditLog.java`, `cli/Launcher.java` |
| 9 | Prompt 分层 | `agent/SystemPrompt.java` | ✅ | `agent/SystemPrompt.java`, `memory/HybridMemoryCompressor.java`, `prompt/PromptMode.java`, `agent/ReActAgent.java`, `agent/orchestration/ReviewerAgent.java` |
| 10 | 多 Agent 并行编排 | `agent/orchestration/AgentOrchestrator.java` | ✅ | `agent/orchestration/AgentOrchestrator.java`, `cli/InteractiveSession.java`, `agent/orchestration/WorkerAgent.java`, `agent/orchestration/OrchestrationConfig.java`, `agent/orchestration/WorkerPool.java` |

### rag_search（语义 + 混合）

⏳ rag_search 需 LLM key，已跳过

| # | 问题 | 期望命中文件 |
|---|---|---|
| 1 | ReAct 循环在哪实现 | `agent/ReActAgent.java` |
| 2 | MCP 客户端怎么连 | `mcp/McpClient.java` |
| 3 | SSRF 围栏怎么实现的 | `web/NetworkPolicy.java` |
| 4 | 审计日志装饰器 | `audit/AuditLog.java` |
| 5 | 三层取消模型 | `agent/concurrent/CancellationToken.java` |
| 6 | RAG 向量索引 | `rag/CodeRetriever.java` |
| 7 | LSP 客户端 | `lsp/LspClient.java` |
| 8 | SQLite 存储 | `store/SqliteStore.java` |
| 9 | Prompt 分层 | `agent/SystemPrompt.java` |
| 10 | 多 Agent 并行编排 | `agent/orchestration/AgentOrchestrator.java` |

## 复现方式

```bash
# 本项目 pom 默认 skipTests=true，必须显式关掉
mvn -q test -Dtest=RagBenchTest -DskipTests=false
```

- `grep_code` 一列**不需要任何 key**，任何人 clone 后都能跑出同样的基线。
- `rag_search` 一列需要 embedding key（`.env` 里的 `EMBEDDING_API_KEY` / `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL`，走既有 `EnvLoader` 机制）；
  无 key 时该列整段跳过并打印 `⏳ rag_search 需 LLM key，已跳过`，测试不会失败。
- 首次带 key 运行会先为 `src/main/java/com/agentcli` 建一次语义索引（产生真实 embedding 调用）；
  加 `-Dagentcli.bench.rag.index=false` 可禁止自动建索引。

## 口径声明

- 两条路径的检索范围、问题原文、top-5 判定口径完全一致，唯一差异是检索方式本身。
- 关键词路径的 query 由统一规则（标点切分 + 驼峰拆分 + 中文 bigram + 停用词过滤）自动从问题里切出，**没有为任何一条问题人工挑选「刚好能命中」的关键词**。
- 关键词路径的文件排名按「命中的不同关键词数 → 总命中条数 → 路径字典序」，排序完全确定，可复现。
- 语义路径多取一倍候选块后折叠为 top-5 个**不同文件**，与关键词路径对齐。
