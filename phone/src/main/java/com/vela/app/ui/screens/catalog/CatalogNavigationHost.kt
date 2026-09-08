package com.vela.app.ui.screens.catalog

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import com.vela.data.model.BaseItemDto
import com.vela.data.model.CatalogTitle
import com.vela.app.ui.activity.PlayerActivity

/** Catalog navigation retains identity separately from server-bound library navigation. */
@Composable
fun CatalogNavigationHost(onLibrary: (BaseItemDto) -> Unit, content: @Composable ((CatalogTitle) -> Unit) -> Unit) {
    var stack by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val context = LocalContext.current
    val stateHolder = rememberSaveableStateHolder()
    val seeds = remember { mutableStateMapOf<String, CatalogTitle>() }
    val open: (CatalogTitle) -> Unit = { title -> seeds[title.key] = title; stack = ArrayList(stack + title.key) }
    if (stack.isEmpty()) stateHolder.SaveableStateProvider("catalog-root") { content(open) }
    else {
        val parts = stack.last().split(':')
        stateHolder.SaveableStateProvider("catalog:${stack.size}:${stack.last()}") {
        CatalogDetailScreen(seeds[stack.last()] ?: CatalogTitle(id = parts[1].toInt(), mediaType = parts[0]),
            onBack = { stack = ArrayList(stack.dropLast(1)) }, onCatalog = open, onLibrary = onLibrary,
            onPlay = { item -> item.id?.let { PlayerActivity.start(context, it) } })
        }
    }
}
