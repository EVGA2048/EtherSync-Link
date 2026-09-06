# AGENTS.md

面向 AI 编码 agent（Codex、opencode、Claude Code、Cursor 等）的通用说明。人类贡献者请优先阅读 [README.md](README.md) / [README.en.md](README.en.md)。

Guidance for AI coding agents. Human contributors should read the READMEs first.

## 项目概览 / Overview

本仓库包含两个可独立运行的组件：

- **ESLink** — Java 21 的 Paper/Bukkit（Minecraft 1.21+）插件，用 Maven 构建。源码在 `src/main/java/com/etherstories/link/`。
- **market-server** — 独立的 Python 3 市场服务（仅用标准库，SQLite 存储），提供 Web 管理页和 JSON API。源码在 `market-server/`。

The repo has two runnable components: a Java 21 Paper plugin (built with Maven) and a standalone Python 3 market service (stdlib only).

## 环境要求 / Prerequisites

- **Java 21**（JDK，用于构建插件）
- **Maven 3.6+**（构建工具）
- **Python 3.10+**（运行市场服务；标准库即可，无需 pip 依赖）

## 构建插件 / Build the plugin

```bash
mvn -B -DskipTests package
```

产物 / Artifacts：

- `target/ESLink-<version>.jar`（shaded uber-jar）
- 同一文件会被复制到 `dist/ESLink-<version>.jar`

版本号以 `pom.xml` 与 `src/main/resources/plugin.yml` 为准。仓库没有单元测试，`package` 即完整构建。

## 运行市场服务 / Run the market service

```bash
cd market-server
python3 etherlink_market.py --name EtherMarket --port 8765 --no-tui
```

- 首次运行会创建 `market-server/data/`，写出 `data/market.json`（含访问令牌 token），并把令牌打印到终端。货单存放在 `data/market.sqlite`。
- Web 管理页与 JSON API 都在 `http://127.0.0.1:8765/`。`/v1/info` 无需鉴权；其他 `/v1/*` 接口需要 `Authorization: Bearer <token>`。
- 去掉 `--no-tui` 会进入交互式命令行界面。`--host` 默认 `0.0.0.0`，仅本机测试可用 `--host 127.0.0.1`。

The first run writes an access token to `data/market.json`. `/v1/info` is public; other `/v1/*` endpoints need `Authorization: Bearer <token>`.

## 运行插件 / Running the plugin

插件是 Minecraft 服务端插件，无法单独启动：需要一台 **Paper 1.21+ 服务器**，把构建好的 jar 放进其 `plugins/` 目录，并让所有互通的服务器连到**同一个 MySQL** 实例。可选依赖 Vault + 经济插件。详见 [README.en.md](README.en.md) 的 Requirements / Installation 部分。因此对插件的验证通常止步于「构建成功」，端到端运行需要一套 Paper + MySQL 环境。

The plugin cannot run on its own — it needs a Paper 1.21+ server (drop the jar into `plugins/`) and a shared MySQL instance. Validation is typically limited to a successful build unless a full server + DB is available.

## 约定 / Conventions

- 不要提交构建产物：`target/` 和 `dist/` 已在 `.gitignore` 中。
- 不要提交密钥：令牌、数据库密码、市场 URL 等一律不入库；`market-server/data/` 已被忽略。
- 提交信息沿用现有风格（常见为 `版本号: 简述` 或 `type: 简述`），每个逻辑改动一个 commit。

## Cursor Cloud 环境 / Cursor Cloud environment

`.cursor/environment.json` 是 Cursor Cloud Agent 专用的环境配置（安装 Maven+JDK21、构建插件、自动启动市场服务、暴露 8765 端口）。其他 agent 软件不会读取该文件，请以本 `AGENTS.md` 中的命令为准。

`.cursor/environment.json` is Cursor-specific; other agents should follow the commands in this file instead.
