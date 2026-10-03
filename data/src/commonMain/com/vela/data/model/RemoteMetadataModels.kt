package com.vela.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 元数据识别与远程图片的接口模型。Emby 与 Jellyfin 的这组接口路径和字段一致：
 * - `POST Items/RemoteSearch/{Type}` 按名称/年份/外部 ID 搜索候选；
 * - `POST Items/RemoteSearch/Apply/{ItemId}` 把选中的候选应用到条目并刷新元数据；
 * - `GET Items/{ItemId}/RemoteImages` 列出各提供方的可用图片；
 * - `POST Items/{ItemId}/RemoteImages/Download` 下载选中的图片替换当前图片。
 * 均需要管理员权限。
 */

/** 识别搜索条件。[providerIds] 的键是服务端提供方名，例如 `Tmdb`、`Imdb`、`Tvdb`。 */
@Serializable
data class RemoteSearchInfo(
    @SerialName("Name")
    val name: String? = null,
    @SerialName("Year")
    val year: Int? = null,
    @SerialName("ProviderIds")
    val providerIds: Map<String, String>? = null
)

@Serializable
data class RemoteSearchQuery(
    @SerialName("ItemId")
    val itemId: String,
    @SerialName("SearchInfo")
    val searchInfo: RemoteSearchInfo,
    /** 只查指定提供方；null 时查询该类型启用的全部提供方。 */
    @SerialName("SearchProviderName")
    val searchProviderName: String? = null,
    @SerialName("IncludeDisabledProviders")
    val includeDisabledProviders: Boolean = false
)

/**
 * 识别候选。应用时原样回传给服务端，因此保留服务端用到的全部字段。
 */
@Serializable
data class RemoteSearchResult(
    @SerialName("Name")
    val name: String? = null,
    @SerialName("ProviderIds")
    val providerIds: Map<String, String>? = null,
    @SerialName("ProductionYear")
    val productionYear: Int? = null,
    @SerialName("IndexNumber")
    val indexNumber: Int? = null,
    @SerialName("IndexNumberEnd")
    val indexNumberEnd: Int? = null,
    @SerialName("ParentIndexNumber")
    val parentIndexNumber: Int? = null,
    @SerialName("PremiereDate")
    val premiereDate: String? = null,
    @SerialName("ImageUrl")
    val imageUrl: String? = null,
    @SerialName("SearchProviderName")
    val searchProviderName: String? = null,
    @SerialName("Overview")
    val overview: String? = null
)

@Serializable
data class RemoteImageInfo(
    @SerialName("ProviderName")
    val providerName: String? = null,
    @SerialName("Url")
    val url: String? = null,
    @SerialName("ThumbnailUrl")
    val thumbnailUrl: String? = null,
    @SerialName("Height")
    val height: Int? = null,
    @SerialName("Width")
    val width: Int? = null,
    @SerialName("CommunityRating")
    val communityRating: Double? = null,
    @SerialName("VoteCount")
    val voteCount: Int? = null,
    @SerialName("Language")
    val language: String? = null,
    @SerialName("Type")
    val type: String? = null
)

@Serializable
data class RemoteImageResult(
    @SerialName("Images")
    val images: List<RemoteImageInfo>? = null,
    @SerialName("TotalRecordCount")
    val totalRecordCount: Int? = null,
    @SerialName("Providers")
    val providers: List<String>? = null
)

/**
 * 条目可编辑的图片类型，对应服务端 ImageType。
 */
enum class RemoteImageType(val apiValue: String) {
    Primary("Primary"),
    Backdrop("Backdrop"),
    Logo("Logo"),
    Thumb("Thumb"),
    Banner("Banner"),
    Art("Art"),
    Disc("Disc")
}

/**
 * 服务端支持“识别”的条目类型（RemoteSearch 的路径段）。季、集没有独立的识别接口，
 * 由调用方改为识别所属剧集。返回 null 表示该类型不支持识别。
 */
fun remoteSearchTypeFor(itemType: String?): String? = when (itemType?.lowercase()) {
    "movie" -> "Movie"
    "series", "season", "episode" -> "Series"
    "boxset" -> "BoxSet"
    "musicalbum" -> "MusicAlbum"
    "musicartist" -> "MusicArtist"
    "musicvideo" -> "MusicVideo"
    "person" -> "Person"
    "book" -> "Book"
    "trailer" -> "Trailer"
    else -> null
}

/**
 * 条目支持的外部 ID（`GET Items/{Id}/ExternalIdInfos`）。识别表单按它动态生成输入框，
 * 因此 MetaTube 等插件提供方也能按 ID 搜索。
 */
@Serializable
data class ExternalIdInfo(
    @SerialName("Name")
    val name: String? = null,
    /** 提供方键，即 ProviderIds 中的键。 */
    @SerialName("Key")
    val key: String? = null,
    /** Jellyfin：ID 所属的实体类型（如 Movie、Series），用于区分同名提供方。 */
    @SerialName("Type")
    val type: String? = null,
    /** Emby：为 false 时该 ID 不能用于识别。 */
    @SerialName("IsSupportedAsIdentifier")
    val isSupportedAsIdentifier: Boolean? = null
)

/** 条目当前已有的图片（`GET Items/{Id}/Images`）。 */
@Serializable
data class ItemImageInfo(
    @SerialName("ImageType")
    val imageType: String? = null,
    @SerialName("ImageIndex")
    val imageIndex: Int? = null,
    @SerialName("ImageTag")
    val imageTag: String? = null,
    @SerialName("Path")
    val path: String? = null,
    @SerialName("Width")
    val width: Int? = null,
    @SerialName("Height")
    val height: Int? = null,
    @SerialName("Size")
    val size: Long? = null
)

/** 远程字幕候选（`GET Items/{Id}/RemoteSearch/Subtitles/{Language}`）。 */
@Serializable
data class RemoteSubtitleInfo(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("ProviderName")
    val providerName: String? = null,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("Format")
    val format: String? = null,
    @SerialName("Author")
    val author: String? = null,
    @SerialName("Comment")
    val comment: String? = null,
    @SerialName("DateCreated")
    val dateCreated: String? = null,
    @SerialName("CommunityRating")
    val communityRating: Double? = null,
    @SerialName("DownloadCount")
    val downloadCount: Int? = null,
    @SerialName("IsHashMatch")
    val isHashMatch: Boolean? = null,
    @SerialName("ThreeLetterISOLanguageName")
    val threeLetterIsoLanguageName: String? = null
)

/** 服务端语言列表（`GET Localization/Cultures`），字幕搜索用三字母代码。 */
@Serializable
data class CultureInfo(
    @SerialName("Name")
    val name: String? = null,
    @SerialName("DisplayName")
    val displayName: String? = null,
    @SerialName("TwoLetterISOLanguageName")
    val twoLetterIsoLanguageName: String? = null,
    @SerialName("ThreeLetterISOLanguageName")
    val threeLetterIsoLanguageName: String? = null
)
