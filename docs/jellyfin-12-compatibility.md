# Jellyfin 12 API 兼容

依据：[12.0 发布说明](https://jellyfin.org/posts/jellyfin-release-12.0/)及其链接的[鉴权规范](https://gist.github.com/nielsvanvelzen/ea047d9028f676185832e51ffaf12a6f)。

## 本次调整

- Jellyfin 资源 URL 使用 `ApiKey`；Emby 继续使用 `api_key`。公共流地址、投屏、字幕及聚合媒体库封面按服务器类型选用参数，不依赖版本字符串。
- 本地转码播放使用请求头时移除 URL 中的新旧 token 参数；投屏重写为当前账户的 token，避免重复或过期参数。
- MPV 和 Media3 共用关联字幕 URL 的鉴权逻辑，识别新旧 token 参数。
- 原文件下载、转码下载及 STRM 内容探测使用 `Authorization`，Jellyfin 使用 `MediaBrowser` scheme，Emby 使用 `Emby` scheme。

## 已核对的现有行为

- 登录已使用带 `Token` 的标准 `Authorization: MediaBrowser ...` 请求头。
- Quick Connect 初始化已使用 `POST /QuickConnect/Initiate`。
- 媒体库筛选与搜索传递 `recursive=true`，文件夹浏览明确传递 `false`，不依赖旧版筛选隐式递归行为。
- 预告片浏览已通过 Items 查询 `includeItemTypes=Trailer`；没有调用本次移除的 EasyPassword、CriticReviews 等端点。
- `HasPassword` 仅保留为响应兼容字段，不用于登录决策；没有限定 `10.x` 的版本判断。
- 使用手写 Ktor API/序列化模型，没有需要因 Swashbuckle 10 升级而重新生成的 SDK。
- 不自动移除用户配置的反向代理路径，也不自动迁移 NAS。若地址使用 Jellyfin 旧的 `/emby` 或 `/mediabrowser` 内置别名，升级后需改成真实服务地址；同名反向代理路径应按实际部署保留。

## 验证

目标回归入口：

```sh
./gradlew :data:testAndroidHostTest --tests '*Jellyfin12CompatibilityTest' :phone:compileDebugKotlin :tv:compileDebugKotlin
```

测试覆盖鉴权 scheme、新旧服务器 URL 参数、特殊字符编码、反向代理路径、Quick Connect HTTP 方法及显式递归参数。真实 Jellyfin 12 上的登录、浏览、起播、字幕、投屏和断点下载仍需联调；此文不代表已完成真机验收。
