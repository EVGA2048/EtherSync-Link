# EtherSync-Link（ESLink）

Paper / Bukkit 跨服互通插件。给多台生存服接大厅、市场、运输箱、红石和聊天，入口是 `/link`（也可用 `/eslink`、`/互通`）。

还在开发中，功能和默认配置都可能改。遇到问题、有想法、或想一起改，欢迎开 [Issue](https://github.com/EVGA2048/EtherSync-Link/issues) 或提交 [Pull Request](https://github.com/EVGA2048/EtherSync-Link/pulls)。

当前版本以 `plugin.yml` / `pom.xml` 为准。

---

## 起因

最初是 EtherStories 和它的姊妹服之间要用：模组差不多，产线、箱子、聊天却过不去。后来就按「多台生存服之间够用」接着做。

大厅里能看见对面在不在；货可以上架给另一边买；运输箱按节拍把物品送过去；红石灯能对端亮；聊天可以选听哪些服。它不是实时管道，也不是共用一份经济账户，更不是代理或 Bungee 传送。

---

## 两套数据，别混

| | 存在哪 | 干什么 |
|---|---|---|
| 插件连的 **MySQL** | 各服填同一份库 | 服心跳、聊天、运输箱、红石、上架通知 |
| **市场服务**（可选） | 一个 Python 进程 + 自带 SQLite | 只管货单：上架、列表、下架、买走 |

聊天和箱子一直走 MySQL。货单可以继续放在这份 MySQL 里（和以前一样）；也可以改走市场服务——别人想「只加入你们的市场」时，就不用再自己搭库。

没在配置里登记任何市场时，货单仍用 MySQL，行为与 0.2.0 之前接近。一旦登记了市场，上架和购买改走 HTTP，旧货单不会自动搬家，需要重新上架，或暂时先不登记、继续用库里的货。

钱始终在**买家所在那台服**的 Vault 上结算。卖家收到的是这台服账户里的钱，不是对面服打过来的。市场服务不记账本。

---

## 环境

各服都要：

- Java 21
- Paper 1.21+（也在 Youer、Arclight 这类核心上用过；聊天拦截跟原版签名有关，换核心后请先拿 `/link chat` 试一句）
- 一份各服都能连上的 **MySQL**

可选：

- **Vault** 以及一台经济插件：不装也能上架标价为 0 的货，有标价就买不了
- **ES2UniPlugin**：税账户没填时，会尝试读它的 `tax.sink-account`
- **Python 3.10+**：只有启用独立市场时才需要，无 pip 依赖

---

## 安装

1. 把 `ESLink-x.x.x.jar` 放进每台服的 `plugins/`，启动一次，生成 `plugins/ESLink/config.yml`。
2. 各服填**同一套** MySQL。
3. 每台服改 `server`：
   - `code`：内部代号，只能字母数字，必须唯一。牌子和配对用它，玩家界面不显示。
   - `name` / `blurb`：大厅里给人看的名字和一句简介。
   - `icon`：`TERRACOTTA` 或 `CONCRETE`。
   - `color`：大厅陶瓦颜色，也决定外服聊天前缀的颜色。
4. 重载：`/link reload`（需要 `eslink.admin`）。

新版本会自动补配置里缺的键，已经填过的值不会被覆盖。

大厅顶上一排能看见各服是否在线。心跳默认 5 秒一次，超过约 20 秒没跳就显示离线。

---

## 玩家怎么用

日常几乎只开 `/link` 大厅。对准箱子或红石灯再输入 `/link`，会直接进对应节点菜单。

游戏里有一本说明书：大厅下方，或 `/link help`。

### 市场

大厅点「跨服市场」。若登记了多个市场，顶栏可以点名称切换；当前市场的名字会写在界面上。换市场就是换一份货单，A 市场上的货不会出现在 B。

- 主手拿着物品，点上架，关掉界面后在聊天栏输入单价（`cancel` 取消）。
- 左键购买，右键看卖家。
- 「我的上架」左键下架（物品退回背包），右键改价。
- 本服自己上的货不能买，请去下架。
- 同一账号在**另一台服**上架的货可以「取回」：物品回到你手里，**货款不会打给你自己**，只收取回费（默认可开，有保底、冷却和每日次数，见下文「经济」）。
- 本服识别不了的模组物品买不了。对端缺的附魔会在物品上注明，带回原服一般可以恢复。

上架通知默认关，大厅里可以给自己打开。管理侧还有总闸（本服 / 外服广播）。

### 聊天

大厅点聊天，打开选台：

- **发言**：仅本服，或发到全部已加入聊天网的服。
- **接收**：不接收外服（默认）、全部，或按服勾选（陶瓦颜色即该服主题色）。

外服消息前缀是 `[服名]`，颜色跟大厅标识一致。发言太快时，本条不会传到其他服，本服仍看得到。

`/link msg 玩家 内容` 可以跨服私聊。聊天里输入 `[i]` 会带上主手物品。

`/link ignore player 名`、`/link ignore server 服名` 仍可用，选台是给「我想听谁」用的，屏蔽是另一层。

### 运输箱

看准箱子（大约 5 格），`/link chest` 或大厅里的入口。也可以 `/link stick` 拿调试棒点箱子，不必蹲下。

登记成 TX（发送）或 RX（接收），配对对面的节点。发送箱必须另绑一口回退箱：对面没有的物品、发失败的货会回到那里；回退箱满了，发送会停，不会把东西吞掉。

这是给产线用的节拍传输：默认大约 2 秒一轮，每轮几组，路上积压到上限就暂停。不是即时管道，漏斗对灌 TX/RX 会对不上节拍，不要那么接。

纸箱、潜影盒等带内含的会整包走，并多一段倒计时再发。TX 可以按物品或模组命名空间过滤，空手点过滤即可清掉。

牌子可以蹲下点开菜单。拆除要确认两次。只有箱子主人或管理能改、能拆。大厅「我的节点」能指向本服坐标。

### 红石

对准红石灯本身（不要对着旁边的方块），`/link io`。灯亮表示对端在线；离线变灰，故障变红。接收灯输出 0–15。对端掉线时输出固定为 0。

电平变化写进 MySQL 事件表，对端按时间戳回放，不是每 tick 写库。接收端可以切正常 / 反向 / 满信号。

---

## 市场服务

仓库里的 [`market-server/`](market-server/) 是一个独立进程，自带 SQLite，不用再为货单搭数据库。

```bash
cd market-server
python3 etherlink_market.py --name 以太货栈 --port 8765
```

第一次启动会在 `data/market.json` 写下接入令牌，并在终端打印出来。数据在 `data/market.sqlite`。换一个 `--data` 目录就是另一份市场。

插件侧（需要 `eslink.admin`）：

```
/link market add ether http://127.0.0.1:8765 令牌 以太货栈
/link market default ether
/link market
```

`add` / `remove` / `default` 会写回 `config.yml`，重启后还在。玩家只在 GUI 里点，不碰地址和令牌。

也可以直接改配置：

```yaml
markets:
  default: ether
  list:
    ether:
      name: 以太货栈
      url: http://127.0.0.1:8765
      token: "与市场服务相同的令牌"
```

多服连同一市场时，把 `url` 写成它们能访问到的地址（内网 IP 或域名），防火墙放行端口。令牌不要提交到 git。

市场服务**不管**聊天、箱子、红石，也**不扣款**。购买时它只负责「这件货还在不在」；扣钱、收税、把物品放进背包，都在买家那台 MC 服上完成。

更细的启动参数见 [market-server/README.md](market-server/README.md)。

---

## 经济

需要 Vault。税率 `trade.tax-rate` 由**买家所在服**收，卖家仍拿标价。税进 `trade.sink-account`；留空则尝试 ES2UniPlugin，再空则税被销毁。

跨服取回（同一 UUID、货在外服）：

- 费用 = `max(保底, 互通税 + 标价抽成)`，保底默认 64
- 冷却默认 20 秒，每天默认 12 次
- `trade.self-buy: false` 可整段关掉，玩家只能到上架那台服下架

不要把取回理解成「自己买自己」。税率为 0 时若把货款打回自己，等于白嫖跨服传物品，所以货款故意不入账。

---

## 物品怎么跨服

- 原版物品按 Bukkit/Paper 数据传。
- 模组物品看 namespaced key，两边都要有这个模组。
- 非原版优先走 NBT，避免不同服之间数字 ID 对不上。
- 纸箱、潜影盒等按内含拆开传；缺的子物品单独退回，其余照常到。
- 每一批有校验，失败会整批隔离，不会塞给玩家。
- 对面缺附魔/属性时，默认 `chest.unknown-extra: deliver`（送到并做标记）；改成 `refuse` 则整件退回。
- 白名单 `whitelist` 为空表示不限制；正式服建议写成 `minecraft:iron_ingot` 这种 key。
- 某类数据组件出问题，可用 `/link component block <id>` 应急拦截，物品会退回。
- 旧版留在物品上的占位标识：`/link cleanitem`。

容器（潜影盒、包裹等）默认 `chest.containers: auto`，启动时自检通过才收发。结果看 `/link diag`；`/link diag retry` 会重跑。

---

## 配置里常改的项

默认 `config.yml` 里每段都有注释。下面只提容易设错的：

| 键 | 含义 |
|---|---|
| `server.code` | 本服内部代号，全网唯一 |
| `heartbeat-seconds` / `offline-after-seconds` | 心跳与离线判定 |
| `chest.stacks-per-scan` | 每轮最多发几组 |
| `chest.queue-limit` | 路上积压上限，满了 TX 暂停 |
| `chest.batch-delay-seconds` | 发货倒计时；0 为立即 |
| `chest.heavy-delay-seconds` / `heavy-max-seconds` | 纸箱等额外等待 |
| `transport.enabled` | 全局运输；游戏里 `/link transport off` 可急停 |
| `io.enabled` / `io.stale-ms` | 红石总闸；对端多久没更新就视为过期 |
| `trade.*` | 交易、税率、取回 |
| `markets.*` | 独立市场列表，可空 |
| `chat.default` | 新玩家默认 `local`（不往外发、也不收外服） |
| `chat.fast-window-seconds` / `fast-count` | 互通发言过快拦截 |
| `super-admins` | 可删大厅里残留的错误服务器记录；也可给权限 `eslink.super` |

红石不要改成「每 tick 写 MySQL」。两边 TPS 不同，对不齐，只会把库打满。

---

## 指令

玩家：

| 指令 | 作用 |
|---|---|
| `/link` | 大厅；看准箱子/灯时直接进节点 |
| `/link chest` | 运输箱菜单 |
| `/link io` | 红石菜单 |
| `/link tx` / `/link rx` | 把准星上的箱子设为发送 / 接收 |
| `/link stick` | 调试棒 |
| `/link chat` | 聊天选台 |
| `/link chat local` / `all` | 发言范围 |
| `/link msg <玩家> <内容>` | 私聊 |
| `/link ignore` / `unignore` | 屏蔽 |
| `/link help` | 说明书 |
| `/link settings` | 设置（管理项仍要权限） |
| `/link cleanitem` | 清占位标识 |

管理（`eslink.admin`，默认 OP）：

| 指令 | 作用 |
|---|---|
| `/link reload` | 重载配置并重连 |
| `/link market` | 列出已登记市场 |
| `/link market add <代号> <地址> <令牌> [名称]` | 登记市场，写入配置 |
| `/link market remove <代号>` | 移除 |
| `/link market default <代号>` | 新玩家默认市场 |
| `/link transport on` / `off` | 运输急停 |
| `/link diag` / `diag retry` / `diag io` | 容器与红石诊断 |
| `/link component list` / `block` / `unblock` | 数据组件黑名单 |
| `/link log` / `log clear` / `log debug` | 日志 |
| `/link version` | 版本与本服代号 |

权限：`eslink.use`、`eslink.chest` 默认对所有人开放；`eslink.admin`、`eslink.super` 默认 OP。

---

## 构建

```bash
mvn -q package
```

得到 `target/ESLink-<version>.jar`，并复制一份到 `dist/`。日常请用打好的 jar，不要把 `target/` 里的中间文件丢进插件目录。

---

## 许可证

见 [LICENSE](LICENSE)。
