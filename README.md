# EtherSync-Link（ESLink）

Paper / Bukkit 跨服互通插件，面向 **EtherStories（ES2）** 与姊妹服（如 SNC）等多服网络。

## 起因

ES2 即 EtherStories。两台服务器模组大体相近，却不完全一致；不少玩家会在两边来回搭建机械动力产线。
与其让产线、物资、协作被服际边界切断，不如用插件把两服玩家联动起来——物品、红石、聊天与交易都能跨服对接，生产线也能「跨服接轨」。

## 能做什么

各服安装同一插件、共用 MySQL，靠心跳发现在线服务器。入口：`/link`（别名 `/eslink`、`/互通`）。

| 能力 | 说明 |
|------|------|
| **互通大厅** | GUI 查看各服状态；跨服上架 / 购买物品（可选 Vault 经济与税率） |
| **TX / RX 运输箱** | 配对发送箱与接收箱，按扫描周期经队列把物品运到对端服 |
| **跨服红石 IO** | 默认事件时间戳回放：电平变化写入 `link_io_events`，接收端按原间隔还原。状态轮询备份见 `IoNet.java.state_version` |
| **跨服聊天** | 本服 / 全服频道、私聊、屏蔽；可附带物品展示 |
| **通知与运维** | 上架广播、建箱提醒、`/link diag` 诊断、说明书、超级管理清理脏数据等 |

运输箱适合产线：每轮多送几组，路上积压满了会暂停，而不是实时管道。纸箱 / 潜影盒等重物可先倒计时再发。

## 环境要求

- Java 21
- Paper（或兼容实现）**1.21+**（`api-version: 1.21`）
- **MySQL**（各服指向同一库）
- 可选：`Vault`（经济）、`ES2UniPlugin`（如税账户等联动）

## 快速配置

1. 将构建产物放入各服 `plugins/`，启动一次生成 `plugins/ESLink/config.yml`。
2. 填写 MySQL，并为每服设置唯一 `server.code`（仅字母数字，玩家界面不显示）以及展示用 `name` / `blurb` / `icon` / `color`。
3. 重载：`/link reload`（需 `eslink.admin`）。

配置要点见默认 `config.yml` 注释，例如：

- `chest.*`：每轮组数、队列上限、重物延迟、容器收发策略
- `io.*`：红石总开关与过期判定
- `trade.*`：交易开关、税率、税入账账户
- `chat.*`：跨服聊天与刷屏提醒

新版本会自动补缺失配置键，不会覆盖你已填过的值。

## 常用指令

| 指令 | 作用 |
|------|------|
| `/link` | 打开互通大厅 |
| `/link chest` | 运输箱菜单 |
| `/link tx` / `/link rx` | 设置发送 / 接收箱 |
| `/link io` | 红石控制器菜单 |
| `/link settings` | 设置 |
| `/link chat` | 聊天相关 |
| `/link msg <玩家> <内容>` | 私聊 |
| `/link help` | 说明书 |
| `/link diag` | 容器/组件/快照诊断 |
| `/link diag retry` | 重跑容器自检 |
| `/link diag io` | 红石诊断 |
| `/link transport on\|off` | 全局运输急停 |
| `/link component block\|unblock\|list <id>` | 禁用/恢复数据组件 |
| `/link cleanitem` | 清除背包/末影箱里的 ESLink 占位标识 |
| `/link log clear` | 清空日志 |
| `/link reload` | 重载配置并重连 MySQL |

权限：`eslink.use`、`eslink.chest`（默认开放），`eslink.admin` / `eslink.super`（默认 OP）。
管理指令（`reload`、`transport`、`component`、`diag retry`）需 `eslink.admin`。

## 物品跨服说明

- 原版物品按 Bukkit/Paper 数据完整传输。
- 模组物品以 namespaced key 为准，两端需安装相同模组。
- 所有非原版物品优先使用 NBT 序列化，避免 `STREAM_CODEC` 注册表数字 ID 在不同服间错位。
- Create 纸箱、潜影盒等容器按内含拆包传输；对端缺失的子物品会单独退回，其余照常送达。
- 每批物品带 SHA-256 行级/批次级校验；校验失败会整批 quarantine，不会投递给玩家。
- 对端缺失附魔/属性时，默认 `deliver`：物品送达并打标记，带回原服可恢复；`chest.unknown-extra: refuse` 则直接退回。
- 旧版占位标识可用 `/link cleanitem` 清除。

## 构建

```bash
mvn -q package
```

产物：`target/ESLink-<version>.jar`（同时复制到 `dist/`）。

## 技术栈

- Paper API 1.21
- HikariCP + MySQL Connector/J
- 可选 VaultAPI

当前版本见 `pom.xml` / `plugin.yml`。

## 许可证

见仓库根目录 [LICENSE](LICENSE)。
