# 市场服务

ESLink 用来存货单的小进程。自带 SQLite，不用另搭 MySQL。

插件用 HTTP 连过来。聊天、箱子、红石还在插件那份 MySQL 里，进不来。这里也不扣钱：买的时候只删掉那条货，扣款和给物品在买家那台服做。

Python 3.10+，标准库就够，不用 pip。

---

## 启动

在 `market-server` 目录：

```bash
python3 etherlink_market.py --name 以太货栈 --port 8765
```

第一次会建 `data/`，写出 `data/market.json`（名字、端口、令牌），终端里也会把令牌打出来。货单在 `data/market.sqlite`。`--data` 可以换目录。

已经有 `market.json` 的话，会接着用里面的设置。命令行参数会覆盖，并写回文件。

自己指定令牌，或者环境变量 `ETHERLINK_TOKEN`：

```bash
python3 etherlink_market.py --name 以太货栈 --port 8765 --token 你的令牌 --data ./data
```

换目录就是另一份市场。停掉进程，SQLite 里的货还在。

默认听 `0.0.0.0`。只本机试可以 `--host 127.0.0.1`。别的机器要连，放行端口就行，前面不用套 Nginx。

令牌只给要加入的服。`data/` 已经在 `.gitignore` 里。

---

## 接到插件

游戏里（`eslink.admin`）：

```
/link market add ether http://127.0.0.1:8765 令牌 以太货栈
/link market default ether
/link market
```

写进 `plugins/ESLink/config.yml`。好几台服都要登记；`url` 用那台服能访问到的地址，别的机器上不要写 `127.0.0.1`。

或者改配置：

```yaml
markets:
  default: ether
  list:
    ether:
      name: 以太货栈
      url: http://127.0.0.1:8765
      token: "跟市场服务同一个令牌"
```

大厅顶栏能看到市场名字和在不在线。玩家点一下切换货单。插件里一个市场都没填，货单还走 MySQL。

填了之后新上架不再写 MySQL。旧货还在库里的话，重新上，或者先别登记。

---

## 存什么

连进来的服（代号、名字、颜色、心跳），以及货单。

买就是按编号删那一行。删不掉说明被人买走了，插件会把钱退回去。

浏览器打开 `http://127.0.0.1:8765/v1/info` 能看到名字和件数，看不到货。货单接口要带令牌。

---

## 参数

| 参数 | 含义 |
|---|---|
| `--name` | 显示名 |
| `--port` | 端口，默认 8765 |
| `--host` | 监听地址，默认 `0.0.0.0` |
| `--token` | 令牌；不写就用文件或环境变量，再没有就生成一个 |
| `--data` | 数据目录，默认 `./data` |
