# TrueUUID — DimensionWorks 1.0.9 自动继承版

本 fork 基于社区版 1.0.9，仅发布 Forge 1.20.1 / Java 17 的
1.0.9-dimensionworks.2，保留原认证协议与旧 MSL 白名单配置。
认证成功后默认自动备份、继承并归档同名离线数据；不同的已有目标
进度会阻止自动继承，双方数据均保留。

- 配置：auth.autoMigrateOfflineData，默认 true。关闭后恢复原客户端确认流程。
- 管理命令：/trueuuid migrateuuid <name> 默认不覆盖不同进度。
- 明确覆盖：/trueuuid migrateuuid <name> overwrite，仅管理员权限 4；先备份双方。
- 保留目标：/trueuuid cleanupuuid <name>，只有每份旧数据都有目标副本才允许归档。
- 命令运行时新旧账号都必须离线；完整冷备份仍是升级前的必要准备。
- 正式产物由本 fork 的 GitHub Actions 构建发布；不使用上游 1.3.x 客户端。
- 鉴权连接自动使用 TLS 1.2，连接超时 5 秒、单次请求超时 8 秒；连接中断只重试一次。
- 网络错误保留原因并显示认证服务连接失败；HTTP 204 等无效会话不重试、不按网络错误处理。
- 连接策略只作用于本模组，不需要面板额外填写 IPv4 或 authlib-injector 排除参数；证书与主机名校验保持开启。

实现边界、备份恢复和实际验证记录见 [自动继承说明](docs/automatic-offline-migration.md) 与 [鉴权连接说明](docs/authentication-network.md)。
保留上游 GNU LGPL 3.0 许可、作者与社区贡献署名。

下面保留上游 1.0.9 的功能说明，其中迁移确认说明适用于关闭自动继承；
命令的安全规则与备份路径以本 fork 上方说明为准。

---

# TrueUUID

English | [简体中文](README_zh-CN.md)

TrueUUID is a login-phase authentication mod for offline-mode Minecraft servers.

It lets an offline-mode server verify premium Mojang accounts, and supported Yggdrasil/authlib-injector skin-site accounts, without ever receiving the player's access token.

Both the client and the server must install this mod. The server must run with:

```properties
online-mode=false
```

## Features

- Privacy-preserving authentication: the player's access token is only used locally on the client.
- Premium/Yggdrasil UUID support on offline-mode servers.
- Correct username casing after successful verification.
- Signed skin texture injection during login.
- Player info refresh after joining, helping skins update correctly.
- Clear join feedback for premium, skin-site, and offline fallback states.
- Offline-to-verified player data migration with confirmation and backups.
- Protection against known verified players rejoining with the same name in offline mode.

## Why

Offline-mode servers normally cannot trust player UUIDs. TrueUUID improves identity integrity while keeping the server in offline mode.

Verified players can keep their official Mojang or Yggdrasil UUID and skin data, while the server never sees their access token.

This is useful for modpacks, LAN-style servers, private offline-mode communities, and servers that want better identity consistency without enabling Mojang online-mode directly.

## How It Works

1. The server runs in offline mode.
2. During login, the server sends a custom login query with a nonce.
3. The modded client receives the query and locally calls `joinServer` with the player's profile, token, and nonce. The token never leaves the client.
4. The client replies with the authentication result and the selected authentication source.
5. The server verifies the nonce through Mojang Session Server or a supported Yggdrasil `hasJoined` endpoint.
6. If verification succeeds:
   - The pending login profile is replaced with the verified UUID.
   - Username casing is corrected.
   - Signed skin texture properties are injected.
   - The authentication source is recorded.
   - Player info is refreshed after joining.
7. If verification fails or times out:
   - Behavior is controlled by the config.
   - Known verified names can be prevented from falling back to offline mode.
   - Unknown names may still be allowed to use offline fallback if configured.

## Offline Data Migration

TrueUUID 1.0.9 adds a safer migration flow for players who used to play offline and later switch to a premium or skin-site account with the same name.

When a verified login detects matching offline UUID data, the player will see a confirmation screen. Migration only happens after confirmation.

Before migration, TrueUUID backs up both the old offline data and any existing target verified UUID data.

Supported migration targets include:

- Vanilla `playerdata`
- Vanilla `playerdata_old`
- Advancements
- Stats
- Cosmetic Armor `.cosarmor` data
- Open Parties and Claims
- FTB Chunks
- FTB Essentials
- FTB Teams
- FTB Quests
- FTB Ranks
- CustomNPCs playerdata

## Requirements

Forge build:

- Minecraft: 1.20.1
- Forge: 47.x
- Java: 17

NeoForge build:

- Minecraft: 1.21.1
- NeoForge: 21.1.x
- Java: 21

Client and server must both install TrueUUID.

## Installation

Server:

1. Set `online-mode=false` in `server.properties`.
2. Place the matching TrueUUID jar in the server's `mods` folder.

Client:

1. Place the matching TrueUUID jar in the client's `mods` folder.

If the client does not have this mod installed, the server will not receive the expected login query response. Depending on configuration, the player may be kicked or allowed to fall back to offline mode.

## Configuration

After the first run, the config file is generated at:

```text
config/trueuuid-common.toml
```

Important options:

```toml
auth.timeoutMs = 30000
```

Login-phase wait time in milliseconds.

```toml
auth.allowOfflineOnTimeout = false
```

`false`: kick on timeout.

`true`: allow offline fallback on timeout.

```toml
auth.allowOfflineOnFailure = true
```

`true`: allow offline fallback for normal verification failures.

`false`: disconnect on verification failure.

```toml
auth.knownPremiumDenyOffline = true
```

If a name has already been verified as premium or Yggdrasil, deny later offline fallback for that name.

```toml
auth.allowOfflineForUnknownOnly = true
```

Only allow offline fallback for names that have never been verified before.

```toml
auth.recentIpGrace.enabled = true
auth.recentIpGrace.ttlSeconds = 10
```

Allows a short same-IP reconnect grace period after a verified player disconnects. This grace is not used when the client explicitly rejects authentication or logs in as offline.

```text
/trueuuid cleanupuuid <name>
```

Admin-only command, permission level 4. It backs up and removes the duplicate offline UUID data for a player name without touching the verified UUID data.

```text
/trueuuid migrateuuid <name>
```

Admin-only command, permission level 4. It approves inheriting the same-name offline UUID data by migrating it to the verified Mojang/Yggdrasil UUID with backups.

```toml
auth.nomojang.enabled = false
```

Disables Mojang session verification when enabled. This is usually not recommended.

```toml
auth.yggdrasil.apiRootWhitelist = []
```

Whitelist for Yggdrasil/authlib-injector `hasJoined` URLs. An empty list trusts the endpoint reported by the client. Add entries such as `"littleskin.cn"` to restrict accepted skin-site sources.

NeoForge 1.21.1 also provides:

```toml
auth.mojangReverseProxy = "https://sessionserver.mojang.com"
```

Mojang Session Server endpoint. This can be changed to a reverse proxy if needed.

## Compatibility Notes

- Proxies: Mojang's `hasJoined` IP parameter is optional. Verification can still work when the real client IP is hidden by a proxy.
- Skins: TrueUUID injects signed skin properties during login and refreshes player info after joining. If a client still shows stale skins, rejoining or clearing the skin cache may help.
- Offline fallback: Offline fallback is configurable. In the recommended setup, previously verified names cannot be reused by offline clients.
- Registry: TrueUUID stores known verified names in `trueuuid-registry.json`. If this file is cleared, the server forgets previous premium/Yggdrasil bindings.

## Building

Windows:

```powershell
.\gradlew.bat build
```

macOS/Linux:

```bash
./gradlew build
```

The built mod is written to `build/libs/`.

## Privacy

The player's access token is never sent to the server.

The client uses the token locally for `joinServer`. The server only receives the authentication result and verifies the nonce through Mojang Session Server or a supported Yggdrasil endpoint.

## License

GNU LGPL 3.0

## Credits

- Mojang authlib and session API
- Sponge Mixin
- ForgeGradle
- NeoForge / ModDevGradle

---

Maintained by [@YuWan-030](https://github.com/YuWan-030).
