# 手机端书籍与音乐

参考 ugreen 的分类浏览、书籍详情/沉浸阅读、迷你播放器和独立后台媒体服务交互，使用 Vela 现有 Compose Material 3 主题。

## 入口与功能

- 首页、我的媒体和聚合库按 `CollectionType=books/music` 打开专用页面。书籍、音频、音乐专辑条目进入对应阅读/播放流程。
- 书籍：书架、作者、有声书、收藏、文件夹、搜索和分页；书籍详情、收藏、最近阅读；EPUB/PDF/CBZ/ZIP/TXT 本地阅读，目录/跳页、字号、夜间背景、图片缩放和续读。
- 音乐：歌曲、专辑、歌手、歌单、流派、收藏和文件夹；分页与搜索、播放全部/随机播放、队列切歌、拖动进度、循环、歌词、收藏、新建歌单、倍速和睡眠定时。
- 音乐播放器由 Media3 `MediaSessionService` 持有；页面外提供迷你播放器，系统媒体会话提供后台控制，通知可返回播放页面。打开视频时暂停音乐；切换账户、线路或退出登录时清空旧音乐会话。
- 原始音频通过 `/Audio/{id}/stream` 播放，遇到不支持的编码可手动选择服务器 MP3 兼容播放。开始、进度与停止报告使用固定账户的会话。

## API 与数据边界

- 浏览使用 `/Items`，同一请求保留父库、类型、搜索、分页、递归和收藏筛选；歌单内容使用 `/Playlists/{id}/Items` 保留顺序和重复曲目。
- 歌手/作者使用 `/Persons`，歌词使用 `/Audio/{id}/Lyrics`；歌词时间从 100ns ticks 转为毫秒。
- 阅读文件通过带鉴权头的 `/Items/{id}/File` 获取，写入私有缓存。读取取消/失败时删除临时文件，不把未完成文件当作缓存成功。
- 阅读位置按账户和条目隔离，仅保存在本机，尚不跨设备同步。缓存键包含条目 Etag。
- EPUB 不解压到文件系统；限制 ZIP 资源数量/大小，拒绝不安全路径与 XML 实体，WebView 禁脚本及外网访问。
- 单书上限 512 MB，单个归档资源上限 32 MB；TXT 上限 16 MB，支持 UTF-8/BOM UTF-16。暂不支持 CBR、MOBI、AZW/DRM 书籍。完整音乐队列上限 10000 项。

## 状态

按用户要求完成代码编辑后提交推送，不运行后续编译、自动化测试或模拟器验收。真实 Jellyfin 12 的登录后书库/音乐库、目录续读、音频解码、歌词、锁屏控制、账户切换、收藏与歌单写入仍待后续实机测试。

参考：[Jellyfin 12 发布说明](https://jellyfin.org/posts/jellyfin-release-12.0/)、[官方 AudioController](https://github.com/jellyfin/jellyfin/blob/v12.0/Jellyfin.Api/Controllers/AudioController.cs)、[官方 LyricsController](https://github.com/jellyfin/jellyfin/blob/v12.0/Jellyfin.Api/Controllers/LyricsController.cs)、[Media3 后台播放](https://developer.android.com/media/media3/session/background-playback)。
