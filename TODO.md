# Vela 手机端开发计划

ZeroDevi1/Vela fork。本文件是已确认的实现顺序，不是愿望清单。
本轮状态（2026-09-08）：P1 / P2 功能代码已接入，清单勾选表示实现完成，不代表实机验收通过。
内容扩充（2026-09-08）：Phase 2 / Phase 5 已接入发现分类榜单、分页筛选、订阅总览与待看更新。已勾选项仅表示代码完成，扩充验收仍由用户执行；资源 / 订阅海报徽章及服务端能力相关项见未勾选清单。
实机验收由用户执行；开发侧只进行目标单测、构建与覆盖安装，不启动应用或操作屏幕。
接口边界与验收入口见 `docs/p1-p2-testing.md`。

上一轮（手势 / overlay / 多服务器 / 聚合搜索 / 线路）已归档到 `TODO.archive.md`。

TV：本轮以手机为准。目录、搜索、日历、Trakt 能下沉 `data` 的共用；日历 / 发现 UI 不跟。杜比设置能共用 `PlayerPreferences` 的跟。

参考图（Hills `com.mountains.hills`）：发现首页、目录详情、搜索源、日历、服务器订阅、画面/杜比。对齐布局和信息结构，不引入 Hills 紫粉硬编码，跟现有深色 token。

验收机：**Redmi K50 Ultra**。播放 / 杜比必须以 K50 实测为准。

---

## 已确认分叉

- **发现 ≠ 现有 Discover**：现有 `Discover.kt` 是当前服务器的 Jellyfin 推荐。新「发现」是目录流（TMDB / 豆瓣热门 + 跨服继续观看）。不要把 Jellyfin 推荐页改名冒充。
- **目录条目不是库条目**：点热门影片先开目录详情（图 3 / 图 4）。有库内匹配才出现播放 / 资源；没有则按钮为「未加载到相关资源」，不要假播放。
- **搜索源多选**：Emby/Jellyfin 各台 Saved Server + TMDB + 豆瓣。沿用现有并发降级，单源失败不让整次搜索挂掉。
- **日历**：订阅的是播出日程，不是 Jellyfin 收藏。优先对接已部署的 MoviePilot；未配置时用 Bangumi 公开日历，不在客户端爬网页。
- **豆瓣**：没有稳定官方公开 API。热门榜主源是 TMDB（`language=zh-CN`）；豆瓣用于搜索补全和评分。能走 MoviePilot 聚合就走，禁止解析豆瓣 HTML。
- **品牌**：这一期仍不改显示名、icon、包名。
- **不对齐**：Hills 悬浮胶囊底栏造型、Memoji 头像 tab、AI 搜索。

---

## 优先级

1. **P0** GitHub Action 自动构建并发布 Release ✅
2. **P1** 发现 tab（TMDB / 豆瓣热门 + 继续观看）
3. **P1** 目录详情（图 3 折叠 / 图 4 展开）
4. **P1** 多源搜索（Emby/Jellyfin + TMDB + 豆瓣）
5. **P2** 日历与订阅（MoviePilot / Bangumi）
6. **P2** 杜比（设置 + MPV 播放）
7. **P2** Trakt 跟踪记录

基础功能后的补充顺序：先丰富发现内容源与「查看全部」，再补订阅总览 / 待看更新，最后补筛选、专题与偏好设置。具体清单见 Phase 2 / Phase 5 的内容扩充。

---

## Phase 1 — GitHub Release（P0）✅

目标：打 `v*` tag 或手动 `workflow_dispatch` 后，CI 产出已签名 APK，并创建 GitHub Release（带附件），而不是只上传 artifact。

### 现状

- `.github/workflows/release.yml`：`:phone:assembleRelease` / `:tv:assembleRelease` 成功后 `softprops/action-gh-release` 创建 Release，附 phone / tv APK
- Release 正文来自 `docs/release-notes.md` 对应版本章节（简介 + 要点），标题 `Vela v{version}`，不用 GitHub 自动 changelog
- tag 与 `appVersionName` 不一致直接 fail；缺 `VELA_STORE_FILE_BASE64` / `VELA_STORE_PASSWORD` / `VELA_KEY_PASSWORD` 时列出缺项并拒绝 unsigned
- 构建后 `apksigner verify`，未签名不发 Release
- 版本在根 `build.gradle`：`appVersionName` / `appVersionCode`（当前 `1.2.3` / `8`）
- APK 名：`vela-phone-release-{version}-{abi}.apk`、`vela-tv-release-{version}-{abi}.apk`

### 做法

- [x] tag `v1.0.0` 时校验 tag 与 `appVersionName` 一致，不一致直接 fail（避免发错号）
- [x] 构建成功后 `softprops/action-gh-release`（或 `gh release create`）创建 Release
- [x] 附上 phone / tv 的 release APK；正文用 `docs/release-notes.md`，不用自动 changelog
- [x] 无签名 secrets 时失败并写清楚缺哪个 secret，不要产出 unsigned 当正式包
- [x] README 补一节：所需 secrets、本地 `./gradlew :phone:assembleRelease`、如何打 tag

不做：自动 bump 版本、Play 上架、改包名。

### 锚点

- `.github/workflows/release.yml`
- `docs/release-notes.md`
- `scripts/release-notes.sh`
- `build.gradle`（`appVersionName` / `appVersionCode`）
- `phone/build.gradle` / `tv/build.gradle`（output 文件名）

### 验收

推 `v*` tag 后，GitHub Releases 出现对应版本，可下载 phone / tv APK；Actions 失败时没有半成品 Release。

---

## Phase 2 — 发现 tab（P1，对齐图 1）

目标：底栏增加「发现」。热门来自 TMDB（及豆瓣评分装饰），继续观看来自已登录服务器。

### 开发前基线

- 应用级底栏 `AppHomeTab`：服务器 / 聚合 / 设置（`AppHomeContainer`）
- 进服务器后 `DashboardDestination`：首页 / MyMedia(Discover) / 搜索 / 收藏 / 设置
- `TmdbApi` 只有 title 详情 / 图 / extras，没有 trending / search
- Seerr 有 trending，但依赖 Jellyseerr 连接，不能当发现主源
- 现有 `Discover.kt` 是 Jellyfin 电影推荐，保留，不改成目录流

### 计划

- [x] 应用级底栏增加「发现」（可进、不强制先选服务器）
- [x] 区块：继续观看（跨已登录服务器，宽卡 + 进度 +「看到 mm:ss」+ 相对时间）
- [x] 今日趋势 / 本周趋势：TMDB trending `day` / `week`，中文标题 + 年份 + 类型
- [ ] 可选：Awards 精选横幅（本轮无可靠数据，未接入）
- [x] 点海报 → 目录详情（Phase 3），带 `tmdbId` + media type
- [x] 继续观看点条目 → 现有库详情 / 播放（`FederatedSessionNavigator` 切到对应 Saved Server）
- [x] TMDB 请求限流、失败显示可重试空态；不要因为没服务器就整页空白（趋势仍可看）

TV 本阶段不做发现 tab。

### 内容扩充 — 从趋势列表到选片入口

目标：无服务器时也有足够的电影、剧集和动漫可浏览；连接服务器后，能从选片自然进入资源与追更。下列勾选记录新增实现，实机验收另列。

- [x] 顶部分类：推荐 / 电影 / 剧集 / 动漫；分类切换保留各自滚动位置。「推荐」混排精选区块，其余分类只展示对应内容。
- [x] 今日趋势 / 本周趋势保留，卡片增加可用评分、媒体类型；同一分区按 `mediaType + tmdbId` 去重，推荐页不同区块尽量避免首屏重复。
- [x] 电影分区：热门电影、高分电影、正在上映、即将上映；上映相关区块显示适用地区，日期缺失时不伪造上映时间。
- [x] 剧集分区：热门剧集、高分剧集、今日播出、近期播出；播出安排与库内已更新分开，不把「播出」标成「可以播放」。
- [x] 动漫分区：动画电影、动画剧集、Bangumi 本周放送；保留各来源身份，无可靠映射的 Bangumi 条目走来源详情 / 手动关联，不按同名自动合并。
- [x] 类型探索：科幻、悬疑、动作、喜剧、爱情、纪录片等快捷入口，进入对应类型的目录列表；电影与剧集类型分别映射。
- [x] 专题入口：先提供可复现筛选的「近一年高分」「经典电影」「本季新剧」；写明年份、评分与最低评价人数条件，不把自动筛选包装成编辑推荐。Awards 仍等待可靠数据。
- [x] 跨服继续观看保留；新增「最近入库」区块，展示来源服务器与真实入库时间，点条目进入库详情。仅连接服务器时展示，单服失败不阻断目录内容。
- [x] 每个内容分区支持「查看全部」进入分页网格；可按类型、年份、地区筛选，并选择热度 / 评分 / 日期排序。仅展示当前数据源支持的选项，高分排序配最低评价人数条件。
- [x] 海报信息展示标题、年份、媒体类型和有数据的 TMDB 评分；明确标注评分来源。
- [ ] 发现海报补充豆瓣评分、「库内可用」/「已订阅」徽章：待增加按可见条目批量查询与刷新，未知状态不显示为未入库。
- [x] 区块顺序与显隐可保存、可恢复默认；首屏先加载可见内容，其余按需请求，刷新与失败重试按区块独立处理，避免所有新分区一起请求。

数据边界：优先扩展现有 TMDB 目录客户端，电影 / 剧集各榜单、类型与筛选能力在实现前核对目标接口；Bangumi 只承担公开放送内容。豆瓣仍按现有 MoviePilot 聚合能力补评分 / 搜索，不新增网页抓取。缓存键包含来源、分类、地区、语言及筛选条件，展示缓存时标记更新时间；失败保留已有内容并提示重试，不用旧数据冒充刷新成功。

### 扩充验收

- [ ] 无服务器时，能从电影 / 剧集 / 动漫进入各自内容；有数据的分类至少提供三个不同主题的分区，数据不足时如实展示，不复制同一榜单凑数。
- [ ] 「查看全部」能加载后续页；切换筛选重置分页，返回保留位置；重复条目、空结果、末页及单分区失败都有正确表现。
- [ ] 连接服务器后继续观看 / 最近入库可进入正确服务器；目录影片是否能播放仍以精确资源匹配为准。

### 锚点

- `phone/.../home/AppHomeContainer.kt`（`AppHomeTab`）
- `phone/.../dashboard/DashboardContainer.kt`（若发现要同时出现在服务器内底栏，只加 tab，不改 Home）
- `data/.../api/TmdbApi.kt`（补 `trending` / `search`）
- `data/.../repository/FederatedMediaRepository.kt`（继续观看聚合）

### 验收

无服务器时能刷 TMDB 趋势；有服务器时顶部出现继续观看；点热门进目录详情，点继续观看进库内播放。

---

## Phase 3 — 目录详情（P1，对齐图 3 / 图 4）

目标：目录条目的详情页。有库匹配时能播；没有时明确说没有资源。展开媒体信息对齐图 4。

### 开发前基线

- `DetailContent.kt` 绑定 `BaseItemDto`（Jellyfin/Emby 库条目）
- 已有简介、演职员、Seerr 请求、codec badge、Cast
- 没有「目录身份 → 多服务器匹配资源」这一层
- 评分主要是服务器 `CommunityRating`，没有豆瓣 / Trakt / RT 一行

### 计划

- [x] 新目录详情（或现有详情加「无 itemId、有 tmdbId」模式），不要复制一套平行播放栈
- [x] 图 3 折叠：返回、日期·类型、主按钮、操作行（收藏 / 已看待有库条目才启用）、简介、评分徽章、系列、章节、播放资源、展开媒体信息
- [x] 主按钮：匹配到库资源 →「播放」并走现有 `onNavigateToPlayer`；否则「未加载到相关资源」，禁用，不 toast 假成功
- [x] 匹配：各 Saved Server 用 `ProviderIds.Tmdb`（及 imdb 兜底）查；多命中按当前活动服务器优先，列出资源卡（服务器名 + 绿勾）
- [x] 播放资源卡：SDR/HDR/DV、体积、码率、线路（复用已有线路，不新做负载均衡）
- [x] 图 4 展开：视频 / 音频技术卡、团队（圆头像）、分类 pill、相似作品
- [x] 评分行：TMDB 必有；豆瓣有则显示；Trakt 放到 Phase 7。没有的源留空，不写 0.0
- [x] 系列 / 相似：TMDB collection / similar；点条目仍进目录详情

### 锚点

- `phone/.../detail/DetailContent.kt`
- `data/.../model/BaseItemDto.kt`（`providerIds`）
- `data/.../api/TmdbApi.kt`
- `data/.../api/MediaServerApiClient.kt`（`AnyProviderIdEquals`）

### 验收

未入库影片能打开详情且主按钮为未加载；入库后同页出现播放和资源卡；展开后能看到视频/音频规格和演职员。

---

## Phase 4 — 多源搜索（P1，对齐搜索源弹层）

目标：搜索可勾选源。库内结果可播；TMDB / 豆瓣结果进目录详情。

### 开发前基线

- 服务器内 `SearchViewModel`：当前服 + 可选 Seerr
- 应用级 `FederatedSearchViewModel`：所有 Saved Server，chips 筛服，无 TMDB / 豆瓣
- 无「选择搜索源」多选弹层

### 计划

- [x] 搜索页增加源选择（多选）：每个已登录 Saved Server、TMDB、豆瓣
- [x] 全选 / 取消全选
- [x] 结果一列，带源徽章；chips 可按源筛
- [x] 点库结果：切到该 Saved Server 再进详情 / 播放（现有 `FederatedSessionNavigator`）
- [x] 点 TMDB / 豆瓣结果：进 Phase 3 目录详情；豆瓣优先按 TMDB / IMDb 映射，缺失时由用户选择对应作品，不用中文名自动认定身份
- [x] 单源失败：条级提示，其它源照出
- [x] Seerr 保持可选附加，不占默认源槽；默认源 = 全部 Saved Server + TMDB
- [x] 限并发，避免 K50 上同时打爆多台服 + 两个目录 API

### 锚点

- `phone/.../search/FederatedSearchViewModel.kt` / `FederatedSearchScreen.kt`
- `phone/.../search/SearchViewModel.kt` / `SearchContainer.kt`
- `data/.../repository/FederatedMediaRepository.kt`
- `data/.../api/TmdbApi.kt`

### 验收

只勾 TMDB 能搜到未入库片并打开目录详情；勾某台 NAS 能搜到库内片并播放；取消某源后结果不再出现该源。

---

## Phase 5 — 日历与订阅（P2）

目标：按播出日查看已订阅剧集。数据优先 MoviePilot，否则 Bangumi。

### 开发前基线

- 无日历、无订阅模型、无 MoviePilot 客户端
- 连接设置只有 Seerr（`ConnectionsSettingsScreen`）
- 收藏是 Jellyfin 收藏，不能表达「追番」

### 计划

- [x] 应用级底栏原「日历」入口扩展为「订阅」，内含更新日历
- [x] 按日分组的时间线：今日 / 未来 / 过去一周；空日显示「今日无剧更新」
- [x] 卡片：海报、标题、季集进度（有库匹配才显示）、播出状态（更新 / 完结）
- [x] 设置里「服务器订阅」（对齐参考图）：开关、MoviePilot 地址、是否登录、连通性、用户名/密码
- [x] 连通成功则使用 MoviePilot 前端同款 `subscribe/` + `tmdb/{id}/{season}` 日程接口；关掉或失败则 Bangumi 公开日历
- [x] 点卡片：有库匹配 → 库详情；否则 → 目录详情。Bangumi 无可靠 TMDB 映射时保留来源详情及手动选择入口
- [x] 同步移除订阅跟 MoviePilot 开关走，默认开；本地不另做第二份订阅真相源

不做：在 App 里做完整 MoviePilot 下载/整理；不做弹幕。

### 内容扩充 — 订阅总览与追更

目标：订阅板块能回答「订了什么、今天播什么、库里有什么还没看」。沿用现有底栏位置，入口显示「订阅」，在页面内提供「我的订阅 / 更新日历 / 待看更新」，连接配置放在独立的「管理连接」入口。

- [x] 我的订阅：完整展示 MoviePilot 返回的电影与剧集订阅，包括暂未定档、当前日期范围无更新的条目，不能只从日历事件反推订阅列表。
- [x] 总览摘要：订阅数量、今日计划播出数、库内待看集数；分别标注单位与来源。库内进度未加载、部分服务器不可达时显示未知 / 部分结果，不默认记为零。
- [x] 订阅卡片：海报、标题、媒体类型、订阅季、服务端订阅状态、下一次播出日期、最近同步时间；无日程显示「暂无播出安排」，无资源显示「暂未匹配到库内资源」。
- [x] 状态分开显示：MoviePilot 订阅状态、计划播出进度、库内可用集数、已看进度各有标签；不把本季最后一集播出推断为全剧完结，也不把下载完成当作已入库或已看。
- [x] 筛选与排序：电影 / 剧集、订阅状态、有库资源 / 未匹配、标题搜索；按最近添加、最近更新、下次播出排序，缺失排序字段的条目置后，不补造时间。动漫筛选仅在有可靠分类时提供。
- [x] 更新日历：今日、未来 7 天、未来 30 天、过去 7 天，增加星期切换与回到今天；同日按作品 / 季合并多集事件，展开查看集号与集名。日期仅精确到天时不显示虚构播出时刻。
- [x] 待看更新：基于已订阅作品与服务器真实季集数据，列出已入库但未看的集数，优先继续上次观看或播放下一集；多个服务器命中时提供来源选择，不重复累计同一季集。
- [x] 订阅详情展示当前季、服务端总集数、当前日历范围内的逐集日程、库内资源与逐集观看状态；电影使用上映日期与库内可用状态。
- [ ] 订阅详情补全日历窗口外的全季播出历史与已播集数；自定义 `episode_group` 需验证库内编号映射后再统计进度，目前明确提示未知。
- [x] 新增入口：从「我的订阅」搜索目录，或从发现详情按电影 / 剧集季添加。提交前展示作品与季，说明将使用 MoviePilot 的下载规则；已有订阅禁用重复添加，成功后重新读取服务端列表。
- [x] 管理入口：按 MoviePilot 订阅记录 ID 单条移除，确认作品与季，遵循「同步移除订阅」开关；请求失败保留原状态并允许重试。目录详情移除也先精确定位记录，避免电影 / 剧集 TMDB ID 碰撞。
- [ ] 暂停 / 恢复：待核对已部署 MoviePilot 版本的状态接口后开放，当前只展示与筛选服务端状态。
- [x] 页面间同步：添加、移除及播放返回后刷新相关订阅 / 库内进度；同一作品不同季分别管理，目录详情与订阅总览状态一致。
- [x] 未配置 MoviePilot：显示连接引导，同时提供独立的「Bangumi 本周放送」浏览区；公共放送不计入「我的订阅」，不显示个人已看或下载进度。
- [x] MoviePilot 失联或登录失效：显示原因、上次成功同步时间及重试 / 重新登录入口；Bangumi 作为明确标注的公共浏览回退，不能把服务端故障显示成「暂无订阅」。

数据边界：MoviePilot 是订阅真相源，媒体服务器是库内资源与观看进度真相源；本地只保存视图偏好及可过期的只读缓存，不维护另一份可编辑订阅。MoviePilot 已结束订阅若不再返回，只能在有可靠历史接口时提供历史列表，不能承诺从当前列表恢复所有已完成订阅。新增状态字段与管理能力先核对已部署版本，缺失字段按未知处理。

### 扩充验收

- [ ] 配置 MoviePilot 后，未定档、近期无更新、电影及多季订阅均能在总览找到；日历无事件不影响总览展示。
- [ ] 同一剧集不同季的新增 / 移除互不影响；服务端失败、重复提交及关闭同步移除时，页面不出现假成功，返回发现详情状态一致。
- [ ] 「已播但未入库」「已入库未看」「已看」三类状态可区分；跨服同集不重复计数，播放返回后待看进度更新。
- [ ] 未配置、订阅为空、部分源失败、登录过期分别显示对应状态；Bangumi 回退始终明确为公开放送。

### 锚点

- `phone/.../home/AppHomeContainer.kt`
- `phone/.../settings/ConnectionsSettingsScreen.kt`（旁路加 MoviePilot，不要塞进 Seerr）
- 新：`data/.../api/MoviePilotApi.kt`、`data/.../api/BangumiApi.kt`
- 新：`data/.../repository/SubscriptionRepository.kt`

### 验收

配好 MoviePilot 后日历出现已订阅番剧；未配置时 Bangumi 仍能列出当季；点条目能进详情或播放。

---

## Phase 6 — 杜比（P2）

目标：杜比视界能播、能关、能降级，设置语义对齐参考「画面」页。K50 实测。

### 开发前基线

- Exo：`HdrCapabilityManager` / `DolbyVisionCompatibleRenderersFactory`，DV → HEVC 回退
- MPV：`target-prim` / `target-trc` / `hdr-compute-peak` / HDR→SDR 开关；无 DV Profile 7→8.1，无「杜比亮度增强」
- 详情已能显示 Dolby Vision / Atmos badge
- 播放设置有色彩、亮度、填充，无上述两个杜比开关

### 计划

- [x] 设置「画面」增加：
  - 杜比亮度增强（默认开；说明可能过曝）
  - 杜比 DV7 转 DV8.1（默认关；说明异常可关）
- [x] MPV：DV 片源按开关设置 `vf` / `vd-lavc-o`（Profile 7 双层 → 8.1）；亮度增强只作用于 DV，不影响普通 HDR10
- [x] 设备不支持 DV 时走现有 HDR10 / SDR 回退，日志标明路径，不黑屏
- [ ] K50 固定片源：SDR、HDR10、DV Profile 7、DV Profile 8；MPV vs Exo 截屏对比
- [x] 开关即时生效或明确「需重开当前片」，不要静默忽略

jniLibs 规则仍见归档 Phase 0：整套 so、指定 tag，不跟 AAR 混装。

### 锚点

- `core/.../PlayerPreferences.kt`
- `phone/.../settings/PlayerSettingsScreen.kt`（及 TV 同名页，能共用偏好的跟）
- `phone/.../mpv/MpvPlayerController.kt`
- `core/.../video/HdrCapabilityManager.kt`

### 验收

K50 上 DV 片可播、不发灰不过曝；关 DV7 转换后异常片可退回；关亮度增强后过曝消失。无 DV 片源的机器不崩溃。

---

## Phase 7 — Trakt 跟踪（P2）

目标：登录 Trakt 后，播放进度上报，历史 / 在看可同步。发现详情可显示 Trakt 评分。

### 开发前基线

- 进度只报给 Emby/Jellyfin（`MediaRepository` session reporting）
- 无 Trakt OAuth、无 scrobble

### 计划

- [x] 连接设置增加 Trakt：OAuth 登录、显示用户名、退出
- [x] 播放 start / pause / stop 向 Trakt scrobble；失败重试一次，不阻断本地进度上报
- [x] 同步 watched / watching 到本地展示（继续观看、日历进度可只读用），Jellyfin UserData 仍是库内已看的真相源
- [x] 目录详情评分行加 Trakt（有则显示）
- [x] Token 走 `SecureSessionStore` 一类安全存储，不进普通 DataStore 明文

不做：Trakt 社交、评论、自定义列表编辑。

### 锚点

- `phone/.../settings/ConnectionsSettingsScreen.kt`
- `phone/.../player/PlayerViewModel.kt`（session 上报旁路 scrobble）
- 新：`data/.../api/TraktApi.kt`、`data/.../repository/TraktRepository.kt`

### 验收

登录 Trakt 后播放一集，Trakt 网页历史出现对应记录；退出后不再上报；未登录时播放不受影响。

---

## 实现约束

- 目录身份用 TMDB id（+ movie/tv），豆瓣 id 只作附属。库匹配靠 `ProviderIds`，不要只靠中文名模糊碰。
- MoviePilot / Trakt / 豆瓣密钥与 token 不得写进仓库。
- 中文字符串进 `values-zh-rCN` 和 `values-zh`。
- 真机验证：CI / 搜索 / 发现可用 12T；杜比 / MPV 色彩用 K50。
- 提交信息：`<type>(<scope>): <subject>`，type 限定 feat/fix/docs/style/refactor/perf/test/build/ci/chore/revert。
