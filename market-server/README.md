# EtherLink 市场服务

独立进程，自带 SQLite，不必再搭 MySQL。Minecraft 服上的 ESLink 用 HTTP 接入。聊天与运输箱仍走插件里的 MySQL。

## 启动

需要 Python 3.10+，无额外依赖。

```bash
cd market-server
python3 etherlink_market.py --name 以太货栈 --port 8765
```

首次启动会在 `data/market.json` 写入接入令牌，并在终端打印。也可预先指定：

```bash
python3 etherlink_market.py --name 以太货栈 --port 8765 --token 你的令牌 --data ./data
```

数据文件：`data/market.sqlite`。换目录即另一份市场。

## 插件接入

在 ESLink `config.yml`：

```yaml
markets:
  default: ether
  list:
    ether:
      name: 以太货栈
      url: http://127.0.0.1:8765
      token: "与市场服务相同的令牌"
```

或游戏内（需 `eslink.admin`）：

```
/link market add ether http://127.0.0.1:8765 令牌 以太货栈
/link market default ether
```

重启后仍保留。玩家在大厅 / 市场页点击切换当前市场。
