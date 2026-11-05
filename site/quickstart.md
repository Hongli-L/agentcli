# 快速开始

三步跑起来。环境要求：**JDK 17+**、**Maven 3.9+**。

## 1. 配置 API Key

```bash
cp .env.example .env
# 编辑 .env，至少填写一个 provider 的 Key
```

也支持 `~/.agentcli/config.json` 持久化配置。

常用变量：

| 变量 | 说明 |
|------|------|
| `GLM_API_KEY` | 智谱 GLM API Key（默认 provider） |
| `DEEPSEEK_API_KEY` | DeepSeek API Key |
| `OPENAI_API_KEY` | 通用 OpenAI 兼容网关 Key |
| `OPENAI_BASE_URL` / `OPENAI_MODEL` | 自定义兼容网关地址与模型 |
| `SERPAPI_KEY` / `SEARXNG_URL` | 联网搜索 Provider |

## 2. 构建

```bash
mvn clean package -DskipTests=false
# 或使用脚本
./scripts/package.sh        # Linux / macOS
./scripts/package.ps1       # Windows
```

### 获取 fat-jar

构建完成后产物为 `target/agentcli-1.0.0.jar`（Maven shade 打包，含全部运行时依赖）。

也可以从仓库的 [Releases 页面](https://github.com/Hongli-L/agentcli/releases) 直接下载已发布的 fat-jar，
下载后跳过第 2 步，直接进入第 3 步运行。

## 3. 运行

```bash
# 交互模式
java -jar target/agentcli-1.0.0.jar

# 单次提问
java -jar target/agentcli-1.0.0.jar -p "列出当前目录下的 Java 文件"

# 指定 provider 与项目根目录
java -jar target/agentcli-1.0.0.jar --provider deepseek -C /path/to/project
```

## Demo C · 多 Agent 并行编排

::: warning Demo C 录屏（asciinema / GIF）即将补充
录屏尚未录制，此处不放任何示意图或第三方素材。补齐后会替换为真实终端录屏。
:::

进入交互模式后，用 `/team` 触发多 Agent 并行协作：

```text
/team 为 tool 包补齐单元测试并整理一份覆盖率报告
```

执行过程：planner 拆解依赖 DAG → 多个 worker **上下文相互隔离**并行执行 → reviewer 一次复核 → 结果聚合。
部分子任务失败会降级为局部结果，不丢已完成产出。

配套命令：

| 命令 | 作用 |
|------|------|
| `/plan <目标>` | 单 Agent 先规划再分步顺序执行（共享一份对话历史） |
| `/team <目标>` | 多 Agent 并行协作完成目标并复核结果（各 worker 历史隔离） |
| `/tasks` | 查看最近一轮编排的任务快照（状态 / 角色 / 耗时 / 错误摘要） |
| `/cancel [任务ID]` | 取消全部 / 指定编排任务（三层取消同时下手） |
| `/help` | 显示帮助 |
| `/exit` | 退出 |

> `/plan` 与 `/team` 共用同一个 `Planner` 做任务拆解，区别只在执行阶段：`/plan` 由**单个** Agent
> 顺序跑完全部子任务、上下文连续；`/team` 把子任务分发给**多个隔离的 worker** 并行跑、末尾统一复核。
> 子任务彼此独立时 `/team` 更快，需要连续上下文推理时 `/plan` 更稳。

## 开发与测试

```bash
# 编译
mvn compile

# 全量测试（Windows 内存不足时可加 -DforkCount=0）
mvn test -DskipTests=false -DforkCount=0
```

当前测试规模：**614 个单元测试用例**。
