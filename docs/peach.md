# Peach 接入

Vela 安卓端把 [Peach](https://github.com/peach-mitao/peach) 作为第三种服务器类型（`ServerType.PEACH`），与 Jellyfin / Emby 并列。本文说明实现方式、各功能对应的 Peach 接口，以及需要在真实部署上确认的事项。接口路径与字段取自 Peach `main` 分支源码。

## 结构

安卓端已有 `MediaServerApi` 接口，媒体库、首页、详情、搜索、收藏与播放上报都经由它调用，因此不需要像 iOS 那样先做 Provider 抽象：

| 层 | 文件 | 作用 |
|---|---|---|
| 协议 | `data/commonMain/peach/PeachClient.kt` | `/api/…` 请求、错误归类（未登录、仅本机、账本只读、冲突） |
| 模型 | `PeachModels.kt`、`PeachManageModels.kt` | 宽松解析：0/1 与布尔通用，未知字段忽略，缺字段给 null |
| 映射 | `PeachMapper.kt`、`PeachIds.kt` | 作品 → `BaseItemDto`；媒体库 `lib:<id>`、实体 `performer:<id>` 等 ID 约定；图片地址 |
| 适配器 | `data/androidMain/peach/PeachMediaServerApi.kt` | 用 Peach 接口实现 `MediaServerApi` |
| 专属能力 | `PeachRepository.kt` | 互动、找资源与云下载、任务、订阅与关注、想要清单、来源与凭据、偏好、配置 |
| 证书 | `data/androidMain/network/ServerTrust.kt` | 自签证书首次信任（TOFU） |
| 界面 | `phone/…/screens/peach/` | Peach 管理、详情页互动面板、人物页订阅、应用内网页入口 |

## 登录与鉴权

- 添加服务器时先按 Jellyfin / Emby 探测；失败后请求 `GET /api/client-updates`：开放访问时直接返回版本，设了口令时返回 401，再确认 `/login` 是 Peach 登录页。
- Peach 没有用户名。登录框里填的内容先当访问口令验证（`X-Token`），不通过再当访问密码走 `POST /login`（表单，`days=30`），从 303 响应的 `Set-Cookie` 取会话；留空表示开放访问。
- 凭据按 Saved Server 存在 `SecureSessionStore`，只放请求头（`X-Token` 或 `Cookie: peach_session=…`），不拼进 URL。图片加载器只把它发给 Peach 自己的主机。
- `userId` 固定为 Peach 的默认档案 `local-default`；`serverInstanceId` 在首次添加时生成。新增线路时会校验新地址也是 Peach。

## 自签证书

源码部署用本机自签 CA 签发证书。系统校验失败时，Vela 只做一次不带凭据的握手读取证书，显示服务器公钥 SHA-256 指纹，用户核对后按 `主机:端口` 固定。之后：

- OkHttp（API、图片、下载）与 `HttpsURLConnection` 默认工厂（ExoPlayer 的 DefaultHttpDataSource）都会接受这张证书；
- 应用内网页入口的 WebView 只在指纹一致时继续（Android 10 起）；
- mpv 走 FFmpeg 自己的 TLS，不认固定证书。自签部署请使用默认播放器，或在系统中安装 `peach-local-ca.crt`。

证书变化（例如重新 `peach init`）时会再次弹出核对，标题为「服务器证书已变更」。

## 功能对照

| Vela 功能 | Peach 接口 |
|---|---|
| 媒体库列表 | `GET /api/libraries` |
| 海报墙、筛选、排序、搜索 | `GET /api/items`（`library`、`tag`、`performer` 等、`state`、`sort`/`dir`、`q`、`limit`/`offset`） |
| 首页「最近添加」「继续观看」「推荐」 | `/api/items` 的 `sort=new`、`sort=played`（客户端按离开比例筛续播）、`sort=daily` |
| 详情、相关推荐、分卷 | `GET /api/item`、`/api/related`、`/api/parts` |
| 人物、名册、标签 | `GET /api/entity`（跟随合并重定向）、`/api/index` |
| 图片 | `/cover?code=&thumb=`、`/poster?id=&c=4`、`/entity-image?kind=&id=` |
| 选流与播放 | `GET /api/stream-plan?id=&session=`；range 直链或 HLS；415（ISO 原盘）与 410（会话已取消）有单独提示 |
| 字幕 | `GET /api/assets/{id}/subtitles`，可播放的轨道作为外挂 WebVTT |
| 进度 | `POST /api/play`（起播）、`POST /api/activity`（`delta` 取位置增量与墙钟的较小者，跳变计为拖动）、停止时 `POST /api/stream-cancel` |
| 收藏 / 已看 | 稍后看 `POST /api/watch-later`、`POST /api/feedback kind=seen`；切换语义，先读状态再决定是否切 |
| 删除 | `feedback kind=dispose`（移入回收站） |
| 投屏、下载 | `POST /api/external-playback` 的限时票据地址 |
| 评分、不喜欢、O、喜欢、找资源 | 详情页 Peach 面板：`/api/feedback`、`/api/preference`、`/api/jav/resources` → `/api/resources/search` → `POST /api/downloads` |
| 播放列表 | `/api/playlist`（`create` / `add` 带 `asset_ids`） |
| Peach 管理 | 任务 `/api/tasks`、`/api/library-processing`；云下载 `/api/downloads*`；订阅源 `/api/feeds*`；关注 `/api/follow*`；想要清单 `/api/wants`；来源 `/api/scraping*`；凭据 `/api/follow/credentials`、`/api/follow/credential`；偏好 `/api/settings`；配置 `/api/configuration`；回收站 `/api/trash/empty` |
| 其余网页功能 | 应用内网页入口（人工复核、重复文件、JAV 探索、统计等） |

Peach 没有的能力（剧集结构、元数据编辑、服务器管理面板、Quick Connect、歌词等）由适配器返回 501 或空结果，界面按已有的失败路径处理。

## 降级规则

- 401：提示重新登录。
- 403「本机」：显示「此项需在运行 Peach 的电脑上设置」。服务器配置页整页降级为说明，并附网页入口（网页同样受本机限制）。
- 409 `ledger read-only`：显示服务端 `message`。
- 服务器配置的 `section_errors` 单独列出，其余分区正常显示；访问密码、公网入口、存储清理与迁移只读展示，不在应用里提供写入。

## 待验证

在真实部署上确认后再移除：

1. 重新 `peach init` 后 CA 是否重建（决定指纹变化的提示频率）。
2. `POST /api/downloads` 的 `origin` 传 `vela` 是否被接受（源码未见白名单）。
3. 口令模式下网页入口用 `?t=` 建立会话：源码中仅在 `password` / `legacy` 访问模式下签发 Cookie，纯口令模式需确认网页内后续请求能否通过。
4. Docker（私有网段可写配置）与桌面源码部署（仅本机）下，服务器配置页分别为可读和降级说明。

## 测试

```
./gradlew :data:testAndroidHostTest
```

`PeachMapperTest`、`PeachClientTest`（common）与 `PeachMediaServerApiTest`（Android host）覆盖解析、映射、错误归类、查询参数转换、切换接口的幂等处理与播放上报顺序。fixture 按 Peach 源码中的返回结构编写，不含真实路径与番号。
