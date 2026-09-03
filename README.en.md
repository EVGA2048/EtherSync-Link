<div align="center">

# EtherSync

Cross-server items, redstone, chat, and marketplace

*Formerly ESLink · the in-game plugin name remains ESLink*

[中文](README.md) · [English](README.en.md)

[![Release](https://img.shields.io/github/v/release/EVGA2048/EtherSync-Link)](https://github.com/EVGA2048/EtherSync-Link/releases)
[![License](https://img.shields.io/github/license/EVGA2048/EtherSync-Link)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-1.21+-blue)](https://papermc.io/)

</div>

---

**EtherSync** is a Paper / Bukkit plugin for linking several Minecraft servers. Once every server shares the same MySQL database, players can open a lobby to see whether the other side is online, then trade, move items, pulse redstone, and talk across the network. The in-game command is `/link`, with aliases `/eslink` and `/互通`. Release artifacts are still named `ESLink-<version>.jar`, and `plugin.yml` still registers the plugin as **ESLink**, which is the name soft-dependents such as ES2UniPlugin look up.

The project continues to evolve. Please treat [`src/main/resources/plugin.yml`](src/main/resources/plugin.yml) and [GitHub Releases](https://github.com/EVGA2048/EtherSync-Link/releases) as the source of truth for the version number. Newer builds may add configuration keys; values you have already set are left untouched. Questions, issues, and improvements are welcome as an [Issue](https://github.com/EVGA2048/EtherSync-Link/issues) or a [Pull Request](https://github.com/EVGA2048/EtherSync-Link/pulls).

## Contents

- [Introduction](#introduction)
- [Features](#features)
- [Where data lives](#where-data-lives)
- [Requirements](#requirements)
- [Installation](#installation)
- [How to use](#how-to-use)
- [Standalone market service](#standalone-market-service)
- [Economy and wallet](#economy-and-wallet)
- [How items travel](#how-items-travel)
- [Common misunderstandings and configuration notes](#common-misunderstandings-and-configuration-notes)
- [Configuration keys](#configuration-keys)
- [Commands and permissions](#commands-and-permissions)
- [Building](#building)
- [License](#license)

---

## Introduction

EtherSync began on two survival servers whose mod lists were close enough that players moved between them, while chests and production lines could not. One of those servers was EtherStories. The plugin is therefore built around a lobby and a set of nodes: open `/link` on your own server to see the other side, then handle the market, transport, redstone, and chat.

Please read what it **does not** do, so it is not mistaken for a live pipe or a cross-server bank:

- **Transport chests are not a real-time pipe.** By default they scan about once every two seconds and send only a limited number of stacks per pass. When the in-flight queue is full, sending pauses; items are not discarded.
- **Money is settled only on the buyer’s server, through Vault.** The seller receives balance on that same server. Nothing is deposited into an account on the remote server.
- **The standalone market service stores listings only.** It does not keep a ledger or charge anyone. A purchase deletes the listing there; withdrawal and item delivery happen on the buyer’s Minecraft server.
- **Chat and chests always use the plugin’s MySQL connection**, whether or not a market service is enabled.

## Features

- **Lobby**: a top row of server status (heartbeat about every 5 seconds; roughly 20 seconds without a beat is treated as offline).
- **Cross-server market**: list, browse, buy, unlist, and change price. The same account may retrieve its own listing on another server (a retrieval fee applies).
- **Transport chests**: pair a send chest (TX), a receive chest (RX), and a fallback chest for periodic factory shipments.
- **Redstone**: bind a redstone lamp. Level changes are written to the database; the far side replays 0–15 by timestamp.
- **Chat tuning**: speak locally or to every linked server; receive nothing, everything, or a chosen subset.
- **Link wallet**: convert local currency into a shared balance at this server’s rate, then withdraw it on another server.
- **Claim codes**: stop impersonation on offline-mode servers, and allow someone else to pick up a listing on your behalf.

## Where data lives

| Component | Location | Purpose |
|---|---|---|
| Plugin **MySQL** | **The same database** on every server | Heartbeats, chat, transport chests, redstone, listing alerts; listings also live here until a market service is registered |
| **Market service** (optional) | A Python process with its own SQLite file | Listing, browsing, unlisting, and completing a sale |

Listings have two homes. Choose one deployment and do not expect an automatic move:

- When `markets.list` is empty, listings continue to write to the MySQL database above, as in earlier versions.
- **Once any market is registered, new listings go over HTTP.** Existing rows in the database are not migrated. Relist those items, or leave the market unregistered and keep using the old store.

A remote server that only wishes to join your market, without hosting its own database, may connect to the market service alone. Chat, chests, and redstone still require every server to reach the shared MySQL instance.

## Requirements

Each server that joins the network needs:

- **Java 21**
- **Paper 1.21+**. Youer and Arclight have also been used successfully. Chat depends on signed chat packets; after changing a platform, please run `/link chat` and confirm that local speech and remote receive both work.
- A **MySQL** instance that every server can reach

Optional:

- **Vault** and an economy plugin. Without them, free (price 0) listings still work; priced listings cannot be purchased.
- **ES2UniPlugin**: if `trade.sink-account` is blank, EtherSync will try that plugin’s `tax.sink-account`.
- **Python 3.10+**: required only for the standalone market. The standard library is enough; no pip packages are needed.

## Installation

1. Place the current release `ESLink-<version>.jar` in each server’s `plugins/` folder and start the server once. The plugin writes `plugins/ESLink/config.yml`.
2. Point every server at **the same** MySQL host, port, database, user, and password.
3. Edit the `server` section on each machine separately:
   - `code`: internal identifier, letters and digits only, **unique across the whole network**. Sign pairing uses this field.
   - `short`: short chat tag, such as `ES2` or `SNC`. If empty, `code` is used.
   - `name` / `blurb`: the name and one-line description shown in the lobby.
   - `icon`: `TERRACOTTA` or `CONCRETE`.
   - `color`: lobby block colour, also used for the remote chat prefix. Allowed values include `WHITE`, `LIGHT_GRAY`, `GRAY`, `BLACK`, `BROWN`, `RED`, `ORANGE`, `YELLOW`, `LIME`, `GREEN`, `CYAN`, `LIGHT_BLUE`, `BLUE`, `PURPLE`, `MAGENTA`, and `PINK`.
4. Run `/link reload` in game (`eslink.admin` required).

Missing keys are filled in on upgrade; values you already set are not overwritten. The row of blocks at the top of the lobby is each server’s online status.

## How to use

Open `/link` for the lobby. Aim at a chest or a redstone lamp and run `/link` again to open that node’s menu. The full guidebook is also at the bottom of the lobby, or via `/link help`.

### Market

Open “跨服市场” (cross-server market) from the lobby. If several markets are registered, click a name in the top bar to switch. **Switching markets switches listing books**; goods from different markets are never mixed.

To list: hold the item, click list, close the inventory, then type the **unit price** in chat. Type `cancel` to abort. Left-click a listing to buy; right-click to see the seller. Under “我的上架” (my listings), left-click unlist (the item returns to the inventory) and right-click to change the price.

Please note:

- You cannot buy a listing you created on **this** server; unlist it instead.
- A listing you created on **another** server can be retrieved here: the item comes to this server, the sale price is not paid to you, and only a retrieval fee is charged (this can be disabled; it has a floor, a cooldown, and a daily cap — see [Economy and wallet](#economy-and-wallet)).
- Items from mods that this server does not have cannot be purchased. Missing enchantments on the far side are delivered by default and noted on the item; they usually return when the item goes back to the original server.
- Listing alerts are off by default. Players may enable them in the lobby; administrators have a global switch as well.

### Chat

Open the chat page in the lobby:

- **Speaking**: this server only, or every server on the chat network.
- **Receiving**: no remote chat (default), all remote chat, or a chosen list of servers. Terracotta colour is that server’s theme colour.

Remote lines look like `[ES2] player: message`. The short tag comes from each server’s `server.short`, and the colour matches the lobby marker. If someone speaks too quickly, that line is not forwarded; it remains visible locally.

- `/link msg <player> <text>`: cross-server whisper.
- Type `[i]` in chat to attach the item in the main hand (`chat.item` must be enabled).
- `/link ignore player <name>` and `/link ignore server <name>`: mute. Ticking servers is “whom I wish to hear”; ignore is a separate rule. Both apply together.

If ES2UniPlugin is installed on the same server, the web terminal can write into the link chat store. The web preference “send to every server” is currently held in memory only: after a reload or restart, an offline player falls back to `chat.default` (default `local`).

### Transport chests

Aim at a chest (about five blocks is enough) and run `/link chest`, or enter from the lobby. `/link stick` gives a debug wand that may click a chest without sneaking.

Set the chest to **TX (send)** or **RX (receive)**, then pair it with the far side. A send chest must also bind a **fallback chest**: items the far side cannot accept, or that never left, return there. When the fallback chest is full, sending pauses and nothing is lost.

Transport is meant for factories: about one pass every two seconds, up to `chest.stacks-per-scan` stacks, pausing when the in-flight count reaches `chest.queue-limit`. Do not point hoppers from a TX into an RX and back.

Cartons and shulker boxes travel with their contents and wait a little longer than ordinary stacks. A TX may filter by item or by mod id; click the filter with an empty hand to clear it.

Sneak-click the sign to open the menu. Removal asks for a second confirmation. Only the owner and administrators may change or remove a node. “我的节点” (my nodes) in the lobby points at coordinates on this server.

### Redstone

Aim at the **lamp itself**, not a neighbouring block, then run `/link io`. A lit lamp means the far side is online; grey means offline; red means a fault. A receive lamp outputs 0–15. If the far side drops, the heartbeat goes stale, or the chunk is unloaded, the output is held at 0.

Writes happen only when the level changes. The far side replays by timestamp; the plugin does not write on every tick. A receive node may be set to normal, inverted, or full signal.

## Standalone market service

[`market-server/`](market-server/) is a small Python process with its own SQLite file, so listings need not share a second database. Chat, chests, and redstone never enter this process.

```bash
cd market-server
python3 etherlink_market.py --name 以太货栈 --port 8765
```

The first run writes a token to `data/market.json` and prints it in the terminal. Listings live in `data/market.sqlite`. `--data` selects another directory and therefore another market. Flags and the admin page are described in [market-server/README.md](market-server/README.md).

In game (`eslink.admin`):

```
/link market add ether http://127.0.0.1:8765 TOKEN 以太货栈
/link market default ether
/link market
```

These commands write `config.yml` and survive a restart. Players only see market names in the interface; they never see the URL or the token. You may also edit the file directly:

```yaml
markets:
  default: ether
  list:
    ether:
      name: 以太货栈
      url: http://127.0.0.1:8765
      token: "same token as the market process"
```

When several servers share one market, each `url` must be an address **that server can reach**. If the process is not on the same machine, do not use `127.0.0.1`. Opening the port is enough; do not commit the token to git.

## Economy and wallet

Priced trade requires Vault. Listings are kept in **link currency**. `trade.link-rate` means: one unit of local currency equals that many units of link currency. Players type a local price when listing; the plugin multiplies by the rate before writing the listing; the buyer’s interface converts back to local currency.

With a standalone market attached, each server’s rate defaults to automatic adjustment from recent **cross-server trades**: when others buy this server’s goods, the seller rate rises (local currency becomes dearer); when this server buys remote goods, it falls. The formula can be changed on the market web page or in its command line. Clicking ± in game pins this server to manual; the sunflower returns it to automatic. Without a market service, only the local config is used. The default is `1.0`. Administrators may adjust the rate in `/link settings` (click ±0.1, sneak ±0.01).

`trade.tax-rate` is collected on the **buyer’s server**. The seller still receives the converted list price without tax. Tax is deposited into `trade.sink-account`; if that is empty, ES2UniPlugin’s `tax.sink-account` is tried; if both are empty, the tax is discarded.

The **link wallet** (the gold ingot in the lobby, or `/link wallet`) moves value for the same UUID: a deposit multiplies by this server’s rate and writes a shared MySQL balance; a withdrawal on another server divides by that server’s rate and pays local currency. This path does not use the market service. Set `trade.wallet: false` to disable it.

The first deposit issues a 6-digit wallet code, which is required to withdraw. The wallet page can show the code again, and you may change it to a number that is easier to remember after confirming the current code. Codes from older builds that were shown only once, and never stored in the clear, cannot be recovered. If you still remember the old code, you may change it; otherwise ask an administrator to run `/link pinreset`.

On offline-mode servers (online-mode off), a UUID is often the player name, so another person may impersonate an account. For that reason `trade.claim-code: true` is the default: unlist and repricing are allowed only on the server where the item was listed. Claim codes are unique on the network. They can be viewed again under “我的上架” on the listing server, and they may be given to someone else. After typing the code, the claimant must click the seller’s head among five heads this server has seen. Too many unknown codes, or a wrong head, pauses claiming and notifies staff. Older listings without a code can only be unlisted on the original server. `/link pinreset <player>` clears a wallet code.

Retrieving your own listing from another server is called **self-retrieve**:

- The fee is `max(floor, tax + price surcharge)`. The default floor is 64.
- The default cooldown is 20 seconds, 12 times per UTC day (this is not necessarily the same day boundary as a local check-in clock).
- `trade.self-buy: false` turns the whole path off; you must travel to the listing server to unlist.

If tax is 0 and the sale price were paid back to you, items could move from A to B for free. The sale price is therefore withheld on retrieve; only the retrieval fee is charged.

## How items travel

Vanilla items use Bukkit / Paper data. Modded items use the namespaced key; **both servers must have that mod**. Non-vanilla items prefer NBT so numeric ids do not collide.

Cartons and shulker boxes are split by contents: missing pieces return, the rest arrive. Each batch is checked; a failed batch is held back rather than given to a player as broken data.

Enchantments or attributes the far side does not know are delivered by default (`chest.unknown-extra: deliver`) with a note on the item. Set `refuse` to return the whole stack. Those notes usually clear when the item returns to the original server. `/link cleanitem` removes leftover marks from older builds.

An empty `whitelist` means no restriction. A public server should list namespaced keys such as `minecraft:iron_ingot`. If a data component misbehaves, `/link component block <id>` will stop it.

Containers with contents (shulker boxes, packages) default to `chest.containers: auto`: they are sent and received only after a startup probe succeeds. See `/link diag`; if it failed, `/link diag retry`.

## Common misunderstandings and configuration notes

Please walk through these points before going live. Each item states the intended setup, then what goes wrong if it is ignored.

1. **`server.code` must be unique on the whole network.** Use distinct alphanumeric codes. Duplicates send sign pairing, lobby status, and transport to the wrong end.
2. **Every server must use the same MySQL database.** Different databases hide remote heartbeats; chat, chests, and redstone will not cross. Starting the plugin without filling in MySQL leaves the lobby usable and the cross-server features idle.
3. **With no market registered, listings stay in MySQL; after registration, new listings use HTTP.** Old rows are not moved. Relist unsold stock, or do not run `/link market add` yet.
4. **A remote server’s market `url` must be an address that server can reach.** If the process is on another machine, do not write `127.0.0.1`. A wrong URL shows the market as offline and listing or buying will fail.
5. **Keep `trade.claim-code: true` on offline-mode servers.** Turning it off lets anyone who knows a name unlist or reprice on another server.
6. **Do not mix 0.2.4 or earlier with 0.2.5+ and then turn on automatic rates.** Chat, chests, redstone, and heartbeats can share a network; extra columns are ignored by older builds. Older heartbeats do not write `link_rate`, so prices are read as 1:1 and the two sides disagree. Until every server is on 0.2.5 or newer, pin the newer servers at **1:1**.
7. **An empty `whitelist` allows every item.** A public server should list allowed namespaced keys. Spawners, creative items, and mods this server does not have can otherwise be shipped or listed. `/link transport off` is the emergency stop.
8. **Do not write redstone to MySQL on every tick.** Uneven TPS fills the database and the levels never line up. Keep “write on change only”.
9. **Do not hopper a TX into an RX and back.** Items queue both ways and look like loss or duplication. A line should run one way: production → TX, remote RX → consumption.
10. **When the fallback chest is full, sending pauses and items stay in the TX.** That is protection, not a fault. Empty the fallback chest and wait for the next scan.
11. **Items from mods this server does not have cannot be bought or delivered.** Unknown extras are delivered with a note by default; set `chest.unknown-extra` to `refuse` to return the whole stack.
12. **The web terminal’s “send to every server” flag is memory-only for now.** After a reload or restart, an offline player returns to `chat.default`. Once they join, player data is authoritative.
13. **Deploy only the jar that matches the current release** (for example `ESLink-0.2.9.jar`). The `dist/` folder may still hold older builds; do not copy them together.
14. **Do not commit tokens, database passwords, or market URLs to a public repository.** `market-server/data/` is already in `.gitignore`.

## Configuration keys

Each section of `config.yml` is commented. These keys are the ones most often set incorrectly:

| Key | Meaning |
|---|---|
| `server.code` | Internal id for this server; must be unique |
| `server.short` | Chat prefix; `code` is used if empty |
| `heartbeat-seconds` / `offline-after-seconds` | Heartbeat interval, and how long without a beat counts as offline |
| `chest.stacks-per-scan` | Maximum stacks sent each pass |
| `chest.queue-limit` | In-flight cap; a full queue pauses the TX |
| `chest.batch-delay-seconds` | Seconds to wait before a batch leaves; `0` sends at once |
| `chest.heavy-delay-seconds` / `heavy-max-seconds` | Extra wait for cartons and shulker boxes |
| `transport.enabled` | Master transport switch; `/link transport off` also stops it at once |
| `io.enabled` / `io.stale-ms` | Redstone master switch, and how long without an update is stale |
| `trade.*` | Trade, tax, wallet, retrieve, and claim codes |
| `markets.*` | Standalone markets; may be left empty |
| `chat.default` | New players default to `local`: no outbound speech, no remote receive |
| `chat.fast-window-seconds` / `fast-count` | Window and count that stop a burst from leaving this server |
| `super-admins` | May delete leftover bad server rows in the lobby; permission `eslink.super` also works |

## Commands and permissions

| Command | Purpose |
|---|---|
| `/link` | Lobby; opens the node menu when aimed at a chest or lamp |
| `/link chest` | Transport chests |
| `/link io` | Redstone |
| `/link tx` / `/link rx` | Mark the chest in front as send / receive |
| `/link stick` | Debug wand |
| `/link chat` | Chat tuner |
| `/link chat local` / `all` | Speaking range |
| `/link msg <player> <text>` | Whisper |
| `/link ignore` / `unignore` | Mute and unmute |
| `/link help` | Guidebook |
| `/link settings` | Admin settings |
| `/link wallet` | Link balance (same-account transfer) |
| `/link claim` | Enter a claim code (may be used by another player) |
| `/link pinreset <player>` | Clear a wallet code (admin) |
| `/link cleanitem` | Remove placeholder marks |
| `/link reload` | Reload configuration (admin) |
| `/link market …` | Register or list markets (admin) |
| `/link transport on` / `off` | Transport emergency stop |
| `/link diag` / `retry` / `io` | Diagnostics |
| `/link component …` | Block a data component |
| `/link log` | Logs |
| `/link version` | Version |

`eslink.use` and `eslink.chest` are granted to everyone by default. `eslink.admin` and `eslink.super` default to operators. `/link market add <id> <url> <token> [name]` writes the configuration and keeps it across restarts.

## Building

```bash
mvn -q package
```

The artifact is `target/ESLink-<version>.jar`, and a copy is placed in `dist/`. Deploy the file that matches the current version; do not mix older copies from that folder.

## License

This project is released under the [Apache License 2.0](LICENSE).
