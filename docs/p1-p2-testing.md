# P1 / P2 开发与用户验收

2026-09-08。本次沿用仓库版本 1.2.3（8），不改显示名、图标、包名和既有杜比设置。
TODO 的实现项与以下实机验收分开记录；用户负责 UI、播放及服务联调验收。

## 入口

- 应用级底栏「发现」：无服务器也可查看 TMDB 今日 / 本周趋势；已登录服务器的继续观看汇总在上方。
- 趋势海报、搜索目录结果：进入独立目录详情。匹配按媒体类型 + TMDB / IMDb 校验，活动服务器优先；播放沿用现有 PlayerActivity，剧集优先选择下一集。
- 「聚合」搜索，以及服务器搜索底部「选择搜索源」：多选服务器、TMDB、豆瓣，默认全部已登录服务器 + TMDB。服务器原搜索及 Seerr 入口保留。
- 底栏「日历」：今日 / 未来 / 过去一周。设置 → 连接 → 服务器订阅，配置 MoviePilot；禁用或失败回退 Bangumi。
- 目录详情「管理订阅」：读取 MoviePilot 的真实订阅，可按季订阅或移除；新增订阅会按 MoviePilot 自身下载规则处理。「同步移除订阅」默认开启，可在连接页关闭。
- 设置 → 连接 → Trakt：填入自己的 Trakt 应用 Client ID / Client Secret，使用设备授权码登录；之后可同步历史、未看完和在看。目录详情按可用数据显示评分。

## 数据与接口边界

- TMDB 使用中文 trending / search / details / collection / similar；目录请求限 2 个并发，超时 20 秒，错误支持页面重试。服务器聚合沿用最多 3 台并发。
- 豆瓣通过 MoviePilot `media/search?source=douban`、`douban/{id}` 获取，不解析 HTML。已有 TMDB 或 IMDb 时精确关联；无法关联时展示来源结果，由用户明确选择对应作品，不凭中文名自动赋予播放资源。
- MoviePilot 当前日历与其前端一致：读取 `subscribe/`，剧集读取 `tmdb/{tmdbid}/{season}`（保留 `episode_group`），电影读取 `media/tmdb:{id}`。不假设存在单独的 `/calendar` 路由。
- MoviePilot 订阅列表只读服务端真相源，客户端不另存本地订阅副本。用户名密码用于登录，密码不落盘，访问令牌加密保存；可输入 MFA 动态验证码。地址允许局域网 HTTP 或 HTTPS。
- Bangumi 公开日历只有星期安排，没有可依赖的 TMDB ID、个人订阅及历史季集进度。因此只显示本周今日及未来安排；过去一周不会伪造数据。未映射条目提供来源详情和手动选择目录入口。
- 只有库匹配返回有效集数与未看集数时，日历卡片才展示库内已看进度；播出季集标记与观看进度分开。
- Trakt 客户端凭据、access token、refresh token 使用 SecureSessionStore 加密存储；历史缓存不含令牌，退出时清除。支持令牌续期，授权轮询遵循 interval / expires_in / 429。
- Trakt start / pause / stop 与媒体服务器上报分离，失败不阻断播放；临时故障最多重试一次，长 Retry-After 交给错误提示，不无限重试。缺失电影 / 单集 ProviderIds 或时长时不伪造记录。退出后丢弃待发事件并取消正在上报的请求。
- Trakt 历史在连接页只读展示，不回写 Jellyfin / Emby 的已看状态。没有评分数据就不显示，不填 0.0。
- 原杜比实现保留：亮度增强默认开，DV7 转 DV8.1 默认关；效果与设备回退仍需 K50 固定片源验收。

接口依据：[TMDB trending](https://developer.themoviedb.org/reference/trending-all)、[MoviePilot 日历前端](https://github.com/jxxghp/MoviePilot-Frontend/blob/v2/src/views/subscribe/FullCalendarView.vue)、[MoviePilot 订阅 API](https://github.com/jxxghp/MoviePilot/blob/v2/app/api/endpoints/subscribe.py)、[Bangumi API](https://github.com/bangumi/api/blob/master/open-api/api.yml)、[Trakt OAuth](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/oauth/index.ts)、[Trakt scrobble](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/scrobble/index.ts)。

## 开发侧检查

- 目标单测：CatalogContractsTest 5 项、DolbyVisionMpvTest 5 项。
- 手机 Debug / Release 构建通过，Release 通过 apksigner 校验；1.2.3（8）arm64 APK 已用 `adb install -r` 覆盖安装到 K50 Ultra，返回 `Success`。
- 宿主只读连通检查：TMDB trending HTTP 200（20 条）、Bangumi calendar HTTP 200（7 个星期分组）。这不代表手机网络可用性验收。
- 未连接真实 MoviePilot / Trakt 账户进行写操作或播放联调，未自动打开实机应用、截图或执行 UI 操作。

## 用户验收清单

- [ ] 无服务器进入发现，趋势成功与失败重试状态正常。
- [ ] 有服务器出现继续观看，跨服打开详情、播放后刷新进度。
- [ ] 未入库目录禁用播放；入库后精确出现资源，检查跨服选择、线路、电影播放、剧集下一集。
- [ ] 检查详情折叠 / 展开、视频音频信息、收藏已看、合集、相似作品、评分。
- [ ] 搜索只选 TMDB、只选 NAS、多选与取消全选；断开单台服务器验证其它结果仍可用。
- [ ] 配置 MoviePilot，检查连通状态、订阅日程、季集和库内进度；验证订阅 / 移除与关闭同步移除的行为。
- [ ] 禁用 / 断开 MoviePilot，检查 Bangumi 回退和未映射条目选择流程。
- [ ] 登录 Trakt 后播放、暂停、继续、结束一集；网页验证记录，检查续期、同步历史及退出后不再上报。
- [ ] K50：SDR、HDR10、DV7、DV8 的 MPV / Exo 对照，验证杜比亮度增强、转换开关与回退。
