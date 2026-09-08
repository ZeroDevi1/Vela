# P1 / P2 开发与用户验收

2026-09-08。本次沿用仓库版本 1.2.3（8），不改显示名、图标、包名和既有杜比设置。
TODO 的实现项与以下实机验收分开记录；用户负责 UI、播放及服务联调验收。

## 入口

- 应用级底栏「发现」：无服务器也可查看 TMDB 今日 / 本周趋势；已登录服务器的继续观看汇总在上方。
- 趋势海报、搜索目录结果：进入独立目录详情。匹配按媒体类型 + TMDB / IMDb 校验，活动服务器优先；播放沿用现有 PlayerActivity，剧集优先选择下一集。
- 「聚合」搜索，以及服务器搜索底部「选择搜索源」：多选服务器、TMDB、豆瓣，默认全部已登录服务器 + TMDB。服务器原搜索及 Seerr 入口保留。
- 底栏「订阅」：我的订阅 / 更新日历 / 待看更新；日历支持今日、未来 7 / 30 天、过去一周及星期切换。连接配置仍位于设置 → 连接 → 服务器订阅；未配置或失败时提供独立标注的 Bangumi 公共放送。
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

## 发现与订阅内容扩充（2026-09-08）

- 发现新增推荐 / 电影 / 剧集 / 动漫分类，共 16 个 TMDB 分区（含今日 / 本周趋势），以及 Bangumi 放送、跨服继续观看和最近入库。分区按可见内容加载，独立刷新 / 重试，失败保留本次会话已有内容及上次更新时间；分区顺序和显隐可保存、重置。
- 「查看全部」使用分页网格，返回详情后保留滚动位置；类型入口直接进入对应筛选。Discover 列表支持类型、年份、制作地区和排序，高分排序至少 200 人评价。趋势及中国大陆上映榜保留服务端榜单定义，不显示不支持的筛选。
- 「近一年高分」「经典电影」为明确条件的自动筛选：至少 200 人评价、评分至少 7 分；经典范围为上映至少 20 年。「本季新剧」按当前自然季度至今天的首播日期筛选；今日 / 未来七天播出使用单集播出日期范围，不代表已入库。
- 我的订阅直接使用 MoviePilot 完整列表，未定档、无近期日程或缺少 TMDB 身份的订阅仍可展示。支持标题、媒体类型、服务端状态、库内可用状态筛选，以及添加时间 / 更新时间 / 下次播出排序。
- 待看更新只读取精确匹配服务器的真实季集，不切换活动会话；跨服同集和多集文件去重，任一服务器明确已看则不重复列为待看。缺失已看字段、缺少季集编号、服务器失败或未连接时显示未知 / 部分结果。自定义剧集分组暂不推断与媒体库编号一致。
- 单条移除按 MoviePilot 订阅记录 ID 请求 `DELETE subscribe/{id}`；目录详情先按媒体类型、TMDB ID 和季精确定位，遇到多条同季订阅则要求使用总览逐条管理。新增前重新读取订阅，避免重复添加；写入成功后重新同步服务端。
- 海报库内 / 订阅徽章、全季历史日程和暂停 / 恢复仍在 TODO 未勾选项中，不计为已完成。

接口参考：[TMDB 电影筛选](https://developer.themoviedb.org/reference/discover-movie)、[TMDB 剧集筛选](https://developer.themoviedb.org/reference/discover-tv)、[MoviePilot 订阅接口](https://github.com/jxxghp/MoviePilot/blob/v2/app/api/endpoints/subscribe.py)。MoviePilot 以实际部署版本联调结果为准。

扩充开发侧检查：

- 最终 `:phone:testDebugUnitTest --tests 'com.vela.app.catalog.*' :phone:assembleRelease` 通过；arm64 Release APK 通过 `apksigner verify`，已覆盖安装到 K50 Ultra（`adb install -r` 返回 `Success`）。未自动启动应用或执行 UI / 服务端写入验收。
- 相关目标单测 14 项通过：原有 `CatalogContractsTest` 5 项及新增 `CatalogExpansionTest` 9 项，覆盖分页元数据、类型 / 季身份、动画筛选、跨服去重、多集文件和未知进度。
- 宿主只读抽查中国大陆上映、动画剧集、高分电影第二页、未来七天播出、本周趋势第二页，5 组请求均 HTTP 200、各返回 20 条。此结果不等于手机网络与页面验收。

扩充实机验收：

- [ ] 切换四类发现内容，打开类型入口和查看全部，加载多页、筛选、返回详情，确认位置及条件正确。
- [ ] 调整分区显隐 / 顺序后重进页面，确认设置保留；断网刷新单分区，确认已有内容保留且显示错误。
- [ ] 使用含电影、未定档剧集、多季和未知来源的真实 MoviePilot 订阅列表，检查总览、筛选、日历与待看更新。
- [ ] 测试跨服同集、多集文件、已看 / 未看 / 未知状态与单服不可达；确认继续观看 / 下一集进入所选服务器的正确条目。
- [ ] 按季添加及逐条移除订阅，验证关闭同步移除、服务端拒绝、登录过期和重复提交；返回目录详情确认状态同步。
