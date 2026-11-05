---
layout: home

hero:
  name: AgentCLI
  text: ReAct 命令行 AI 编程助手
  tagline: Java 17 从零手写的 ReAct 命令行 AI 编程助手，零新增依赖纪律
  actions:
    - theme: brand
      text: GitHub 仓库
      link: https://github.com/Hongli-L/agentcli
    - theme: alt
      text: fat-jar 下载
      link: /quickstart#获取-fat-jar
    - theme: alt
      text: 快速开始
      link: /quickstart

features:
  - title: MCP / LSP 双协议手写
    details: 不依赖官方 SDK、不依赖 lsp4j，手写 JSON-RPC 2.0 传输与会话层，同时驱动 MCP 与 LSP 两套客户端。
  - title: 单 SQLite 四类状态 + 崩溃恢复
    details: 审计与记忆落到同一个 SQLite 库，四类状态机统一收口，WAL 模式支撑进程崩溃后的状态恢复。
  - title: 多 Agent 并行编排 + 三层取消
    details: planner 拆解依赖 DAG，多 worker 上下文隔离并行执行，reviewer 统一复核；取消做到协作轮询 / 线程中断 / HTTP 句柄三层。
  - title: RAG 全链路降级
    details: 向量索引 + 关键词与语义混合检索 + 类关系图谱；任一环节失败自动降级到下一级检索，不让整条链路一起挂掉。
  - title: 614 单测 + 5 运行时依赖
    details: 零新增依赖纪律贯穿全项目，fat-jar 仅含 OkHttp / Jackson / sqlite-jdbc / JLine / Logback 五个运行时依赖。
---

::: info 关于本项目的「人味」声明
本仓库采用 **AI 辅助开发**：需求拆解与迭代以 **SDD（Spec-Driven Development）** 为主、Harness / 工程化编排仅部分落地的**混合方式**（非纯 Vibe Coding）。
但请务必看清边界——**614 条单元测试全部为人工设计**，协议层（JSON-RPC 2.0 / MCP / LSP）、并发与三层取消、状态机收口等核心逻辑均为**手写**，并非 AI 一键生成。
:::

## 这个项目是什么

**AgentCLI** 是一个基于 **ReAct**（Thought → Action → Observation）模式的轻量级命令行 AI Agent。
纯 Java 单机应用，无中间件依赖，聚焦**后端架构设计、并发编程与工程化交付**。

它要证明的不是"会调 API"，而是：协议层能自己手写、并发与取消能自己兜住、状态与崩溃恢复能自己收口。

## 项目亮点

| # | 亮点 | 工程增量 |
|---|------|---------|
| 1 | **MCP / LSP 双协议手写** | 不依赖官方 SDK / lsp4j，手写 JSON-RPC 2.0 实现 MCP 与 LSP 双协议客户端 |
| 2 | **单 SQLite 四类状态 + 崩溃恢复** | 审计 / 记忆落单库，四类状态机 + WAL 崩溃恢复 |
| 3 | **多 Agent 并行编排 + 三层取消** | planner / worker / reviewer 并行 + 协作轮询 / 线程中断 / HTTP 句柄级三层取消 |
| 4 | **RAG 全链路降级** | 向量索引 + 混合检索 + 类关系图谱，检索失败自动降级 |
| 5 | **614 单测 + 5 运行时依赖** | 零新增依赖纪律，fat-jar 仅含 OkHttp / Jackson / sqlite-jdbc / JLine / Logback |

## 设计决策

| # | 决策 | 取舍与理由 |
|---|------|-----------|
| 1 | **零新增依赖纪律** | 全项目仅 5 个运行时依赖（OkHttp / Jackson / sqlite-jdbc / JLine / Logback）。能手写落地的协议与工具就不引第三方库，fat-jar 才好控体积与安全面 |
| 2 | **SSRF 围栏下沉到传输层** | 网络围栏不放业务层，而是插入 `okhttp3.Dns` 在解析阶段就拒绝内网 / 环回 / 链路本地地址，从源头堵住重定向、IPv6 ULA 等绕过路径 |
| 3 | **核心协议全部手写** | MCP 与 LSP 不依赖官方 SDK / lsp4j，手写 JSON-RPC 2.0 传输与会话层；联网结果也自己把 HTML 规整为 Markdown，不嵌入重型渲染依赖 |
| 4 | **三层协作式取消模型** | 取消不只靠线程中断：协作轮询取消标志 → `Future.cancel(true)` 线程中断 → HTTP 句柄级 `Call.cancel()`，父子令牌级联，确保长链路能被真正掐断 |

## 量化数据

> 量化基准（R0-3 部分已实测）：检索式注入使单次提问上下文 token **降低 99.4%**（232694 → 1313）；`grep_code` 关键词检索 top-5 命中率 **80.0%（8/10）**。`rag_search` 语义检索列 ⏳ 待 embedding key。

## 核心能力速览

| 模块 | 说明 |
|------|------|
| ReAct 调度 | Thought → Action（工具调用）→ Observation 循环，带轮数 / Token / 死循环预算兜底 |
| 工具框架 | 可扩展 `Tool` 接口 + `ToolRegistry` 注册表，内置文件 / 命令 / 代码检索 / 联网搜索 |
| 并行工具 | 同一轮多个 `tool_call` 线程池并发执行（上限 4），批次超时 90s，结果按序回灌 |
| 多模型抽象 | `LlmClient` 统一接口 + `OpenAiCompatibleClient` 模板基类，支持 GLM / DeepSeek / 通用 OpenAI 兼容网关 |
| 流式输出 | SSE 流式解析，终端分区渲染 reasoning 与正文 |
| 对话记忆 | 短期对话历史 + 长期事实记忆 + Token 预算 + LLM 摘要压缩 |
| 安全围栏 | 路径沙箱、命令黑名单、写文件大小上限、命令超时与输出截断 |
| 并发任务底座 | `AgentTask` CAS 状态机（PENDING→RUNNING→{DONE\|FAILED\|CANCELLED\|TIMEOUT}）+ 固定线程池 + 单任务/全局双层超时 |
| 三层协作式取消 | ① 协作轮询取消标志 ② `Future.cancel(true)` 线程中断 ③ HTTP 句柄级 `Call.cancel()`，父子令牌级联 |
| 多 Agent 编排 | planner 拆解依赖 DAG → 多 worker 并行执行（上下文隔离）→ reviewer 一次复核 → 结果聚合，部分失败降级不丢已有产出 |

## 下一步

- 想看结构：[架构概览](/architecture)
- 想跑起来：[快速开始](/quickstart)
- 想看数据：[量化数据](/benchmarks)

---

::: info 开发方式诚实声明
本项目的需求拆解与迭代借助 **SDD / Harness Engineering** 等 AI 辅助手段完成，但 **614 条单元测试为人工设计**，
协议层、并发与取消、状态机收口等核心实现均为**手写**。量化收益数字（见 [量化数据](/benchmarks)）均来自本仓库可复跑的基准，**未实测项不显示数字**。
:::
