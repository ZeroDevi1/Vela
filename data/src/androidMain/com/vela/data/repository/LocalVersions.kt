package com.vela.data.repository

import com.vela.data.model.BaseItemDto

/**
 * 校验服务端版本候选，保留真实条目身份，避免不同集或冲突的刮削身份混入。
 * @param item 当前电影或单集；无有效 ID 时无法确定选择项。
 * @param candidates 服务端按外部 ID 查询得到的候选条目，不接受媒体源伪造的条目。
 * @return 当前条目优先、按条目 ID 去重的版本列表；当前 ID 为空时返回空列表。
 */
fun matchingLocalVersions(item: BaseItemDto, candidates: List<BaseItemDto>): List<BaseItemDto> {
    if (item.id.isNullOrBlank()) return emptyList()

    // 将外部身份标准化，仅比较用于电影和单集检索的提供方。
    val currentIds = item.versionProviderIds()
    val matches = candidates.filter { candidate ->
        if (candidate.id.isNullOrBlank() || !candidate.type.equals(item.type, ignoreCase = true)) {
            return@filter false
        }
        val candidateIds = candidate.versionProviderIds()
        val sharedKeys = currentIds.keys.intersect(candidateIds.keys)
        if (sharedKeys.isEmpty() || sharedKeys.any { currentIds[it] != candidateIds[it] }) {
            return@filter false
        }

        // 剧集还必须匹配完整季集区间；缺少编号时不推断是同一集。
        if (item.type.equals("Episode", ignoreCase = true)) {
            item.parentIndexNumber != null && item.indexNumber != null &&
                candidate.parentIndexNumber == item.parentIndexNumber &&
                candidate.indexNumber == item.indexNumber &&
                (candidate.indexNumberEnd ?: candidate.indexNumber) == (item.indexNumberEnd ?: item.indexNumber)
        } else {
            item.type.equals("Movie", ignoreCase = true)
        }
    }

    // 保留当前详情的完整数据，不按路径合并不同条目或改写 ID。
    return (listOf(item) + matches).distinctBy { it.id }
}

/**
 * 提取接收条目的有效外部身份用于版本比对，无副作用。
 * @return 小写提供方名称到非空 ID 的映射；没有身份时返回空映射。
 */
private fun BaseItemDto.versionProviderIds(): Map<String, String> = providerIds.orEmpty()
    .mapKeys { (key, _) -> key.lowercase() }
    .filter { (key, value) -> key in setOf("imdb", "tmdb", "tvdb") && value.isNotBlank() }
