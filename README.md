# EtherSync-Link（ESLink）

Paper / Bukkit 插件，多台服之间传物品、红石、聊天，也能上架买卖。游戏里打 `/link`（`/eslink`、`/互通` 一样）。

还没做完，功能和配置都会改。踩到坑、有想法，或者直接改一版，丢 [Issue](https://github.com/EVGA2048/EtherSync-Link/issues) 或 [PR](https://github.com/EVGA2048/EtherSync-Link/pulls) 就行。

版本号看 `plugin.yml`。

---

## 起因

当时有两台生存服，模组差不太多。人两边跑，箱子和产线过不去。EtherStories 是其中一台。就写了这个。

现在能做的：大厅看对面在不在、市场上架、运输箱按周期送货、红石灯对端亮、聊天选听哪些服。箱子不是实时管道，钱也不会打到对面服的账户里。

---

## 数据放哪

| | 存在哪 | 干什么 |
|---|---|---|
| 插件连的 **MySQL** | 各服同一份库 | 心跳、聊天、运输箱、红石、上架通知 |
| **市场服务**（可选） | 一个 Python 进程，自带 SQLite | 货单：上架、列表、下架、买走 |

聊天和箱子一直走 MySQL。货单可以继续放这份库里，跟以前一样；也可以改连市场服务。别人只想进你们的市场，就不用自己再搭库。

配置里一个市场都没填的话，货单还是 MySQL。一旦填了，新上架走 HTTP，库里的旧货不会自动搬过去，要么重新上，要么先别填、继续用旧的。

钱在买家这台服的 Vault 上结。卖家拿到的是这台服账户里的钱。市场服务不记账。

---

## 环境

每台服：

- Java 21
- Paper 1.21+。Youer、Arclight 也跑过。聊天跟签名包有关，换核心先打一句 `/link chat` 试试
- 各服都能连上的 **MySQL**

可选：

- Vault + 经济插件。没有的话 0 元货还能上，标了价就买不了
- ES2UniPlugin：税账户空着时会去读它的 `tax.sink-account`
- Python 3.10+：只有开独立市场才要，不用装 pip 包

---

## 安装

1. jar 丢进各服 `plugins/`，启动一次，会出 `plugins/ESLink/config.yml`。
2. MySQL 填成同一套。
3. 每台服改自己的 `server`：
   - `code`：内部代号，字母数字，全网别重复。牌子配对用这个。
   - `short`：聊天前面那一小段，比如 ES2、SNC。空着就用 code。
   - `name` / `blurb`：大厅上显示的名字和一句介绍。
   - `icon`：`TERRACOTTA` 或 `CONCRETE`。
   - `color`：大厅那块陶瓦的颜色，外服聊天前缀也用这个色。
4. `/link reload`（要 `eslink.admin`）。

缺的配置键新版本会自己补，你填过的不会被改掉。

大厅顶上一排是各服在线状态。默认 5 秒跳一次心，大约 20 秒没跳就显示离线。

---

## 玩家怎么用

平时开 `/link` 就行。准星对着箱子或红石灯再打 `/link`，会进那个节点的菜单。

说明书在大厅最下面，或者 `/link help`。

### 市场

大厅点「跨服市场」。登记了好几个市场的话，顶栏点名字切换。换市场就是换一份货单，两边的货不会混在一起。

主手拿着东西点上架，关掉界面，聊天栏打单价。`cancel` 取消。左键买，右键看卖家。「我的上架」左键下架（东西回背包），右键改价。

本服自己上的货不能买，去下架。同一个号在另一台服上的货可以取回：东西回来，钱不会打给你自己，只收一笔取回费（能关，有保底、冷却和每天次数，下面「经济」）。

本服没有的模组物品买不了。对面缺的附魔会写在物品上，带回原服一般还能回来。

上架通知默认关，大厅里自己开。管理那边还有总开关。

### 聊天

大厅点聊天：

- 发言：只本服，或发到聊天网上的所有服
- 接收：不收外服（默认）、全收，或按服勾选。陶瓦颜色就是那台服的主题色

外服消息长这样：`[ES2] 玩家: 内容`。短标签在各服 `server.short` 里配，颜色跟大厅标识一致。说太快，这一句传不过去，本服还能看见。

`/link msg 玩家 内容` 私聊。聊天里打 `[i]` 会带上主手里的东西。

`/link ignore player 名`、`/link ignore server 服名` 还在。勾选是「我想听谁」，屏蔽是另一回事。

### 运输箱

看准箱子（大概 5 格），`/link chest`，或者大厅那个入口。`/link stick` 拿调试棒点箱子也行，不用蹲。

TX 发送，RX 接收，再跟对面配对。发送箱要另绑一口回退箱：对面没有的、没发出去的，回到那里。回退箱满了发送会停，东西不会丢。

给产线用的，大概 2 秒一轮、每轮几组，路上堆满了就停。别拿漏斗把 TX 和 RX 对着灌。

纸箱、潜影盒连里面的一起走，会多等一会儿再发。TX 能按物品或模组名过滤，空手点一下过滤就清掉。

蹲下点牌子打开菜单。拆要点两次。只有主人和管理能改、能拆。大厅「我的节点」能指向本服坐标。

### 红石

对准灯，别对着旁边的方块。`/link io`。亮着就是对面在线，离线变灰，出问题变红。接收灯输出 0–15，对面掉线就是 0。

电平变了才写进 MySQL，对端按时间戳回放，不是每 tick 写一次。接收可以切正常 / 反向 / 满信号。

---

## 市场服务

[`market-server/`](market-server/) 里有个 Python 脚本，自己带 SQLite，货单不用另搭库。

```bash
cd market-server
python3 etherlink_market.py --name 以太货栈 --port 8765
```

第一次会在 `data/market.json` 写下令牌，终端也会打出来。数据在 `data/market.sqlite`。`--data` 换个目录就是另一份市场。

游戏里（`eslink.admin`）：

```
/link market add ether http://127.0.0.1:8765 令牌 以太货栈
/link market default ether
/link market
```

会写进 `config.yml`，重启还在。玩家只在界面里点，看不到地址和令牌。

也可以直接改配置：

```yaml
markets:
  default: ether
  list:
    ether:
      name: 以太货栈
      url: http://127.0.0.1:8765
      token: "跟市场服务同一个令牌"
```

好几台服连同一个市场，`url` 写成它们能访问到的地址。另一台机器上别写 `127.0.0.1`。端口放行就行，令牌别提交到 git。

这个进程不管聊天、箱子、红石，也不扣钱。买的时候它只删掉那条货单；扣钱和给物品都在买家那台 MC 服上做。

参数见 [market-server/README.md](market-server/README.md)。

---

## 经济

要 Vault。货单按**互通货币**记账。`trade.link-rate`：1 本服货币 = 该数值互通。玩家上架输入本服价格，插件乘汇率后写入货单。买家界面换算成本服货币。

接了独立货栈时，各服汇率默认由货栈按近窗**跨服成交**自动调：外服买走的多，卖方汇率升高（本币更贵）；买外服货多则回落。公式在货栈 Web / 命令行能改。游戏里点汇率 ± 会钉成本服手动；再点向日葵可切回自动。没接货栈时仍只看本服配置。默认 `1.0`。

`trade.tax-rate` 在买家这台服收，卖家拿换算后的标价（不含税）。税进 `trade.sink-account`，空着就试 ES2UniPlugin，再空税就没了。

管理可在 `/link settings` 用按钮调汇率（点击 ±0.1，潜行 ±0.01）。接货栈时向日葵切换自动/手动。

**给自己跨服转账**：大厅金锭或 `/link wallet`。本服存入（× 本服汇率 → 互通余额），到另一台服取出（÷ 当地汇率 → 当地货币）。同一 UUID，走共享 MySQL，不经过市场服务。`trade.wallet: false` 可关。首次存入会给 6 位钱包码，取出必须输入。钱包页随时能再看，也可以改成更好记的 6 位数字（需先核对现在的码）。较早只显示过一次的码无法再取回，记得旧码即可改掉，或请管理 `/link pinreset`。

离线服（没开正版验证）UUID 等于玩家名，别人可以冒名。因此默认 `trade.claim-code: true`：只能在上架那台服下架/改价。取件码全服唯一，上架服「我的上架」随时能再看，也可发给别人代领。领取时输入码后要点出货主头像（五个本服见过的头）。不存在的码连输 5 次、或点错头像，会暂停取件并通知管理。旧货没有取件码的，只能回上架服下架。管理可用 `/link pinreset <玩家>` 清钱包码。

同一 UUID、货在外服，叫取回：

- 费用 `max(保底, 税 + 标价抽成)`，保底默认 64
- 冷却 20 秒，每天 12 次
- `trade.self-buy: false` 整段关掉，只能去上架那台服下架

税率为 0 时如果把货款打回自己，等于免费把物品从 A 搬到 B，所以货款故意不入账。

---

## 物品跨服

原版按 Bukkit/Paper 数据传。模组看 namespaced key，两边都得有这个模组。非原版尽量走 NBT，免得数字 ID 对不上。

纸箱、潜影盒按里面的东西拆开传，缺的那几件退回，其余照常到。每批会校验，失败整批扣下，不会塞给玩家。

对面缺附魔默认还是送到（`chest.unknown-extra: deliver`），物品上会做标记；改成 `refuse` 就整件退回。

`whitelist` 空着不限制。正式服写成 `minecraft:iron_ingot` 这种。某个数据组件有问题，`/link component block <id>` 先拦住。旧版留在物品上的标记用 `/link cleanitem`。

潜影盒、包裹默认 `chest.containers: auto`，启动自检过了才收发。看 `/link diag`，不行就 `/link diag retry`。

---

## 配置

`config.yml` 里每段都有注释。容易设错的：

| 键 | 含义 |
|---|---|
| `server.code` | 本服内部代号，别跟别人重复 |
| `server.short` | 聊天前缀，空则用 code |
| `heartbeat-seconds` / `offline-after-seconds` | 心跳、多久算离线 |
| `chest.stacks-per-scan` | 每轮最多发几组 |
| `chest.queue-limit` | 路上能堆多少，满了 TX 停 |
| `chest.batch-delay-seconds` | 发货前等几秒，0 马上发 |
| `chest.heavy-delay-seconds` / `heavy-max-seconds` | 纸箱那些再多等一会儿 |
| `transport.enabled` | 运输总开关；游戏里 `/link transport off` 也能停 |
| `io.enabled` / `io.stale-ms` | 红石；对面多久没更新算过期 |
| `trade.*` | 买卖、税率、取回 |
| `markets.*` | 市场列表，可以空着 |
| `chat.default` | 新玩家默认 `local`：不往外说，也不收外服 |
| `chat.fast-window-seconds` / `fast-count` | 说太快就拦住 |
| `super-admins` | 能删大厅里残留的错误服务器；权限 `eslink.super` 也行 |

红石别改成每 tick 写 MySQL。两边 TPS 对不齐，库会被打满。

---

## 指令

| 指令 | 作用 |
|---|---|
| `/link` | 大厅；对着箱子/灯会进节点 |
| `/link chest` | 运输箱 |
| `/link io` | 红石 |
| `/link tx` / `/link rx` | 把眼前的箱子设成发送 / 接收 |
| `/link stick` | 调试棒 |
| `/link chat` | 聊天选台 |
| `/link chat local` / `all` | 发言范围 |
| `/link msg <玩家> <内容>` | 私聊 |
| `/link ignore` / `unignore` | 屏蔽 |
| `/link help` | 说明书 |
| `/link settings` | 设置 |
| `/link wallet` | 互通余额（给自己跨服转账） |
| `/link claim` | 输入取件码领取（可代领） |
| `/link pinreset <玩家>` | 清除钱包码（管理） |
| `/link cleanitem` | 清占位标记 |
| `/link reload` | 重载（管理） |
| `/link market …` | 登记市场（管理） |
| `/link transport on` / `off` | 运输急停 |
| `/link diag` / `retry` / `io` | 诊断 |
| `/link component …` | 禁用某个数据组件 |
| `/link log` | 日志 |
| `/link version` | 版本 |

`eslink.use`、`eslink.chest` 默认所有人有。`eslink.admin`、`eslink.super` 默认 OP。`/link market add <代号> <地址> <令牌> [名称]` 会写配置。

---

## 构建

```bash
mvn -q package
```

jar 在 `target/ESLink-<version>.jar`，会再拷一份到 `dist/`。

---

## 许可证

[LICENSE](LICENSE)
