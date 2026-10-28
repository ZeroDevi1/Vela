# Peach 接入

Vela 安卓端把 [Peach](https://github.com/peach-mitao/peach) 作为第三种服务器类型（`ServerType.PEACH`），与 Jellyfin / Emby 并列。本文说明实现方式、证书处理、各功能对应的 Peach 接口，以及仍留在网页上的部分。接口路径与字段取自 Peach `main` 分支源码。

## 结构

安卓端已有 `MediaServerApi` 接口，首页、媒体库、详情、搜索、收藏与播放上报都经由它调用，所以不需要先做 Provider 抽象，而是用 Peach 接口实现这个接口：

| 层 | 位置 | 作用 |
|---|---|---|
| 协议 | `data/commonMain/peach/PeachClient.kt` | `/api/…` 请求；错误归类：未登录、仅本机、账本只读、冲突 |
| 模型 | `PeachModels.kt`、`PeachManageModels.kt`、`PeachBrowseModels.kt` | 宽松解析：0/1 与布尔通用，未知字段忽略，缺字段给 null |
| 映射 | `PeachMapper.kt`、`PeachIds.kt` | 作品、实体、关注帖子转为 `BaseItemDto`；ID 约定与图片地址 |
| 适配器 | `data/androidMain/peach/PeachMediaServerApi.kt` | 用 Peach 接口实现 `MediaServerApi` |
| 专属能力 | `PeachRepository.kt` | 互动、浏览、任务、订阅与关注、维护、整理、配置等 |
| 证书 | `data/androidMain/network/ServerTrust.kt` | 导入 CA 与首次信任（TOFU） |
| 界面 | `phone/…/screens/peach/` | Peach 浏览、Peach 管理、详情与人物页面板、网页入口 |

条目 ID：作品是数字；媒体库 `lib:<id>`；实体 `performer:<id>`、`studio:<id>` 等；虚拟列表 `view:later`、`view:unseen`、`view:flagged`、`view:jav`；「Peach 浏览」入口 `peach-hub:browse`；关注帖子 `follow:<帖子>[:<媒体序号>]`；库外作品预告片 `javpreview:<番号>`。

## 登录与鉴权

- 添加服务器时先按 Jellyfin / Emby 探测；失败后请求 `GET /api/client-updates`：开放访问时直接返回版本，设了口令时返回 401，再确认 `/login` 是 Peach 登录页。
- Peach 没有用户名。登录框先把输入当访问口令验证（`X-Token`），不通过再当访问密码走 `POST /login`（表单，`days=30`），从 303 响应的 `Set-Cookie` 取会话；留空表示开放访问。
- 凭据按 Saved Server 存在 `SecureSessionStore`，只放请求头（`X-Token` 或 `Cookie: peach_session=…`），不拼进 URL。图片加载器只把它发给 Peach 自己的主机。
- `userId` 固定为 Peach 的默认档案 `local-default`；`serverInstanceId` 在首次添加时生成。新增线路时校验新地址也是 Peach。

## 证书

Peach 源码部署用本机自签 CA（`<Peach 数据目录>/secrets/tls/peach-local-ca.crt`）签发 HTTPS 证书。系统不认这个 CA。Peach 只把叶子证书发给客户端，CA 证书不经网络分发；而且本机地址变化或证书临近到期时，Peach 会用同一个 CA 重签叶子证书并**换新密钥**（`certs.py` 的 `reissue` 每次 `-newkey`），CA 本身从不重建。

因此 Vela 提供两种信任方式，按顺序校验（系统信任链永远先跑）：

1. **导入 CA（推荐）**：把 `peach-local-ca.crt` 复制到手机，在连接时弹出的证书确认框里点「导入 Peach CA 证书」，或在「Peach 管理 → 证书」里导入。导入前显示证书的 SHA-256 与 SHA-1 指纹，可与电脑上 `openssl x509 -noout -fingerprint -sha256 -in peach-local-ca.crt` 的结果核对。导入后按完整证书链校验，Peach 重签叶子证书也不用再确认。导入的 CA 只在 Vela 内生效，不影响其他应用，也不需要在系统里安装证书。
2. **首次信任（TOFU）**：不方便复制文件时，可以直接核对并固定服务器证书的公钥指纹（按 `主机:端口`）。Peach 重签后指纹会变，Vela 会再次询问；如果新证书与旧证书的颁发者密钥标识（AKI）相同，提示里会说明是同一签发者的正常重签。

生效范围：

- API、图片与下载（OkHttp），以及 ExoPlayer 等使用 `HttpsURLConnection` 的请求；
- 应用内网页入口的 WebView（Android 10 起能取到证书，按同一判据决定是否继续）；
- mpv 播放器在 Vela 中本来就关闭了 TLS 校验（`tls-verify=no`），不受自签证书影响。

Windows 独立包使用 HTTP，不涉及证书。

## 功能对照

### 浏览与播放

| Vela 功能 | Peach 接口 |
|---|---|
| 媒体库与虚拟列表（稍后看、没看过、已标记、JAV） | `GET /api/libraries`；`/api/items` 的 `library` / `state` / `jav` |
| 海报墙、排序、筛选、搜索 | `GET /api/items`；Peach 浏览里的筛选面板读 `GET /api/facets`（年份、产地、画幅、来源、标签） |
| 首页「最近添加」「继续观看」「推荐」 | `sort=new`；`sort=played` 后按离开比例筛续播；`sort=daily` |
| 详情、相关推荐、分卷、其他版本 | `GET /api/item`、`/api/related`、`/api/parts`、`/api/editions` |
| 名册、热门实体、实体资料页、图集 | `GET /api/index`、`/api/tops`、`/api/entity`（跟随合并重定向）、`/api/photos`、`/api/photo-set` |
| 原目录浏览 | `GET /api/folders`；`/api/items?folder=&folder_recursive=` |
| 播放列表 | `GET /api/playlists`、`GET/POST /api/playlist`（新建、改名、删除、添加、移除） |
| 收藏的实体与保存的筛选 | `GET/POST /api/library-shortcuts` |
| 搜索补全与历史 | `GET /api/suggest`、`GET/POST /api/search-history`（与网页共享）；全文检索结果不再按标题二次过滤 |
| 图片 | `/cover`、`/poster`、`/entity-image`、`/follow-image`、`/follow-cover`、`/cover-image`、`/jav-image`、`/photo-thumb`、`/photo` |
| 选流与播放 | `GET /api/stream-plan?id=&session=`；ISO 原盘（415）与会话已取消（410）单独提示 |
| 进度 | `POST /api/play`、`POST /api/activity`（`delta` 取位置增量与墙钟的较小者，跳变计为拖动）、停止时 `POST /api/stream-cancel` |
| 字幕与记忆 | `GET /api/assets/{id}/subtitles` 作外挂 WebVTT；`/api/subtitles/memory` 恢复与写回所选字幕，已保存的字幕作为外挂轨 |
| 倍速记忆 | `/api/player/memory` 的 `rate`；起播恢复，用户主动调整时写回（长按与快捷倍速不写） |
| 在线字幕 | `GET /api/subtitles/search`、`/api/subtitles/subtitlecat/{id}[/{index}]`，存入字幕记忆 |
| 关注更新 | `GET /api/follow`；帖子在详情页与播放器中打开，走 `/follow-stream`，上报 `/api/follow/play` 与 `/api/follow/activity`；已看、忽略、保存进馆藏、切换帖子里的媒体 |
| JAV 探索 | `GET /api/jav/discoveries`、`/api/jav/detail`、`/api/jav/reviews`；预告片在播放器中播放 |
| 投屏、下载 | `POST /api/external-playback` 的限时票据地址 |

### 互动与整理（详情页 Peach 面板）

| 功能 | 接口 |
|---|---|
| 评分、已看、不喜欢、O 计数 | `POST /api/feedback`（切换语义以返回为准） |
| 稍后看、喜欢 | `POST /api/watch-later`、`POST /api/preference` |
| 找资源 → 选磁力 → 提交云下载 | `/api/jav/resources` → `/api/resources/search` → `POST /api/downloads`（`origin=vela`） |
| 编辑标签 | `POST /api/item-tag` |
| 想要高清版 | `POST /api/quality-goal` |
| 产地 | `POST /api/batch` `operation=region` |
| 加入播放列表 | `POST /api/playlist` |
| 对账所在目录 | `POST /api/purge-missing` |
| 删除 | `feedback kind=dispose`（移入回收站，可恢复） |
| 实体：订阅新作、收藏、别名与改名 | `/api/feeds/source`、`/api/library-shortcuts`、`/api/entity-alias`、`/api/entity-name` |

### Peach 管理

| 分区 | 接口 |
|---|---|
| 任务中心、刮削入库 | `/api/tasks`、`/api/library-processing` |
| 云下载 | `/api/downloads`、`/cancel`、`/postprocess` |
| 订阅源、新作发现、按名字订阅 | `/api/feeds*`、`/api/feeds/lookup` |
| 关注来源、计划、批量添加、作者别名 | `/api/follow*`、`/api/follow/resolve`、`/api/follow/authors`、`/api/follow/author-alias` |
| 想要清单 | `/api/wants` |
| 来源与凭据 | `/api/scraping*`、`/api/follow/credentials`、`/api/follow/credential` |
| 内容偏好 | `/api/settings`（只提交本次改动的键；含番号识别规则） |
| 人工复核 | `/api/review`、`/api/review/decision` |
| 文件整理 | `/api/organize`、`/example`、`/preview`、`/apply`、`/rollback`（预览 → 确认 → 执行，可回滚） |
| 标签管理 | `/api/tag-management`、`/preview`（预览 → 凭令牌执行） |
| 重复文件、高清版目标、垃圾文件 | `/api/duplicates`、`/api/quality-goals`、`/api/ads`，操作走 `/api/batch` |
| 数据管理 | `/api/resource-sync/*`、`/api/orphan-records*`、`/api/links*`、`/api/media-repair` |
| 自定义元数据来源 | `/api/custom-metadata` |
| 统计、口味分析 | `/api/stats`、`/api/taste` |
| 回收站 | `/api/items?state=trash`、`/api/batch restore`、`/api/trash/empty` |
| 服务器配置 | 见下节 |
| 证书 | 本机导入的 CA 列表 |

### 服务器配置

Peach 只允许运行它的电脑写配置；Docker 无桌面部署另外接受私有网段直连。其他情况返回 403，Vela 整页降级为说明并给出网页入口（网页同样受这个限制）。可写时提供：

- 媒体库与目录：改名、新增目录（含 SMB 与连通测试）、更改位置（按重定位保存）、移除目录；配置已被改动时回 409，刷新后重试；
- 下载渠道：CloudDrive2 地址与令牌（只写不回显）、各来源下载目录、PikPak 根目录与账号登录、检查；
- 索引器、CloudDrive 代理；
- 更新：检查、自动更新方式与间隔、下载更新、更新并重启（二次确认，期间轮询状态）；
- 存储：占用统计；
- 出网代理与站点入口；
- 媒体处理（仅元数据、硬件加速）与时间轴缩略图；
- 使用与错误报告、馆藏统计共享（先预览数据再确认）；
- 系统诊断、后台问题与入库问题（只读，可重试）。

访问密码、公网隧道、存储清理与迁移只读展示：修改它们可能让本应用断开连接。

## 留在网页上的部分

以下功能通过应用内网页入口使用，原因如下：

| 功能 | 原因 |
|---|---|
| 播放墙、沉浸浏览 | 网页专属的多路同屏与竖屏连播交互 |
| 封面裁切、头像挑选、人脸模型 | 需要在大图上框选，依赖网页的图像编辑器 |
| 目录规则（按目录归类创作者等） | 规则编辑与冲突预览依赖网页的整理视图 |
| 浏览器历史导入（口味来源） | 需要读取电脑上的浏览器数据 |
| PikPak 浏览器登录、选择文件夹、开机启动、卸载 | 依赖 Peach 主机的桌面环境 |
| 复核里的类别映射（genre） | 需要对照来源类别与本地标签词表 |
| 外观、侧栏顺序 | 网页界面设置，与 Vela 无关 |

## 降级规则

- 401：提示重新登录。
- 403「本机」：显示「此项需在运行 Peach 的电脑上设置」。
- 409 `ledger read-only`：显示服务端 `message`；配置页显示只读。
- 409 版本冲突：提示刷新后重试。
- 配置的 `section_errors` 单独列出，其余分区正常显示。

## 待验证

在真实部署上确认：

1. `POST /api/downloads` 的 `origin` 传 `vela` 是否被接受（源码未见白名单）。
2. 纯口令模式下网页入口用 `?t=` 建立会话：源码只在 `password` / `legacy` 访问模式下签发 Cookie。
3. Docker（私有网段可写配置）与桌面源码部署（仅本机）下，配置页分别为可写与降级说明。
4. 关注帖子在需要凭据或已失效的来源上的播放提示。

## 测试

```
./gradlew :data:testAndroidHostTest
```

`PeachMapperTest`、`PeachClientTest`、`PeachBrowseTest`（common）与 `PeachMediaServerApiTest`（Android host）覆盖解析、映射、ID 约定、错误归类、查询参数转换、切换接口的幂等处理、播放上报顺序与字幕记忆。fixture 按 Peach 源码中的返回结构编写，不含真实路径与番号。
