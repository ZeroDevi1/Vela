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


## 2026-09-09 界面与手势调整

继续使用 Vela 的 Material 3 配色，参考本机 `ZeroDevi1/ugreen` 源码中的漫画续读卡片、阅读方向设置与黑胶播放器。

- 书架：放大续读卡片、封面网格与列表切换、封面更多按钮、长按操作底部面板；书籍详情使用居中封面和独立阅读入口。
- 音乐：正在播放的歌曲高亮、悬浮圆角迷你播放器、旋转唱片和主题渐变背景；小高度播放器支持滚动。
- 书籍/音乐列表：下拉刷新；长按条目打开操作面板，提供打开、收藏以及音频的新建歌单入口。收藏与歌单仍写入当前 Jellyfin 账户。
- 迷你播放器标题区：左右滑动切歌，上滑打开播放器。
- 播放器唱片区：单击歌词，双击播放/暂停，左滑下一首、右滑上一首，上滑队列、下滑收起。上一首沿用播放服务行为：已播放超过 3 秒时先回到曲首。按钮入口保留；歌词纵向滚动和进度条不承载切歌手势。
- PDF/CBZ/ZIP：横向正序、横向反序、竖向翻页；单击切换菜单，双击在原尺寸与 2.5 倍间切换，双指缩放支持 1–5 倍。放大后拖动图片，恢复原尺寸后才能手势翻页。方向按当前书籍/账户保存；EPUB/TXT 保留正文滚动与目录/翻页按钮。
- 阅读进度滑块在松手后跳页，避免拖动过程中连续解码图片。

本轮 `:phone:compileDebugKotlin` 与 `git diff --check` 通过。设备检查：ADB 无已连接设备，SDK 没有可用 AVD。实际页面视觉、手势冲突、真实媒体阅读与播放仍需设备验收，不能以编译通过代替。

## 2026-09-11 启动与封面修复

- 首页预告片不再给 `TextureView` 设置背景 Drawable（透明色同样触发异常），首帧前仍通过 alpha 隐藏画面。连接手机上的原始崩溃为 `UnsupportedOperationException: TextureView doesn't support displaying a background drawable`。
- 书籍和音乐优先使用自身封面，再使用专辑、父级主图、缩略图和背景图；请求携带对应所有者的图片 tag，避免封面更新后持续显示旧图。
- 无封面的文件夹在前 50 个书籍/音乐子条目中选择封面来源，优先服务器已有图片，否则选择可生成首页的 PDF/CBZ/ZIP。查询结果按账户会话缓存，失败不缓存。
- 没有服务器图片的 PDF/CBZ/ZIP 生成首页缩略图，保存在账户隔离的私有缓存。漫画沿用现有 Range 读取和 CRC 校验；PDF 使用 [Android 代理文件回调](https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback) 提供随机读取，不为封面自动下载整本书。并发解码上限为 2，单次 PDF 封面最多读取 32 MB，分块内存缓存上限为 8 MB。
- 不支持 Range、归档目录无效、加密 PDF 或解码错误均显示失败图标并保留诊断原因；不将无封面的正常条目误判为网络失败。EPUB/TXT 仍使用服务器图片，不在此轮生成正文封面。

实机验收：使用原签名的 debug APK 覆盖安装到连接的 22081212C 手机，进入 Jellyfin 首页、书架、文件夹和音乐列表。PDF 首页、CBZ 子书籍封面以及阿衰 Online、爆笑校园、哆啦 A 梦、漫画派对等文件夹的补图已观察到；验收进程无新增崩溃。单独的“阿衰漫画”CBZ 仍返回“漫画没有有效的图片目录”，保留失败状态，未声称该文件已修复。

验证包含 `:data:testAndroidHostTest`（16 项）和 `:phone:testDebugUnitTest --tests '*RemoteComicArchiveTest'`（4 项）、debug APK 构建及 `git diff --check`。

随后执行 `:phone:assembleRelease` 成功，ARM64 APK 通过 `apksigner verify --verbose` 签名验证，覆盖安装后包标志不含 `DEBUGGABLE`。正式版实机进入 Jellyfin 首页、书架及音乐列表，并滚动到此前未展示的 PDF 验证首页封面生成；图片正常显示，验收进程无新增崩溃。当前手机已替换为 release 1.2.3（versionCode 8），保留原账户数据。
