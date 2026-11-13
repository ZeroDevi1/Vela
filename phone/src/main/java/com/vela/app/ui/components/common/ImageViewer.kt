package com.vela.app.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Scale
import com.vela.shared.R

@Composable
internal fun ImageViewer(url: String, description: String?, onDismiss: () -> Unit) {
    ImageViewer(pageCount = 1, description = description, onDismiss = onDismiss) { url }
}

@Composable
internal fun ImageViewer(
    pageCount: Int,
    description: String?,
    onDismiss: () -> Unit,
    initialPage: Int = 0,
    imageUrl: @Composable (Int) -> String?
) {
    if (pageCount == 0) return
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, pageCount - 1)) { pageCount }
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = pageCount > 1 && zoomedPage != pager.currentPage
            ) { page ->
                val url = imageUrl(page)
                key(url) {
                    ZoomableImage(url, description) { zoomed ->
                        if (zoomed) zoomedPage = page
                        else if (zoomedPage == page) zoomedPage = null
                    }
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.settings_close), tint = Color.White)
            }
            if (pageCount > 1) {
                Text(
                    text = stringResource(R.string.image_viewer_position, pager.currentPage + 1, pageCount),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String?, description: String?, onZoomChanged: (Boolean) -> Unit) {
    val context = LocalContext.current
    val request = remember(context, url) {
        ImageRequest.Builder(context).data(url).size(2560).scale(Scale.FIT).build()
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var translation by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(Size.Zero) }
    var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    fun transform(centroid: Offset, zoom: Float, pan: Offset) {
        if (!imageSize.isSpecified || imageSize.isEmpty() || viewport == IntSize.Zero) return
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val pivot = centroid.takeIf { it.isSpecified } ?: center
        val ratio = nextScale / scale
        val nextTranslation = (translation + center - pivot) * ratio + pivot - center + pan
        val fit = minOf(viewport.width / imageSize.width, viewport.height / imageSize.height)
        val maxX = ((imageSize.width * fit * nextScale - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((imageSize.height * fit * nextScale - viewport.height) / 2).coerceAtLeast(0f)
        translation = Offset(nextTranslation.x.coerceIn(-maxX, maxX), nextTranslation.y.coerceIn(-maxY, maxY))
        scale = nextScale
        onZoomChanged(scale > 1f)
    }

    val gestures = rememberTransformableState { centroid, zoom, pan, _ -> transform(centroid, zoom, pan) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                viewport = it
                transform(Offset.Unspecified, 1f, Offset.Zero)
            }
            .pointerInput(url) {
                detectTapGestures(onDoubleTap = { tap ->
                    val nextScale = if (scale > 1f) 1f else 2.5f
                    transform(tap, nextScale / scale, Offset.Zero)
                })
            }
            .transformable(gestures, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center
    ) {
        if (url != null) {
            AsyncImage(
                model = request,
                contentDescription = description,
                contentScale = ContentScale.Fit,
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Success) imageSize = state.painter.intrinsicSize
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = translation.x
                    translationY = translation.y
                }
            )
        }
        when {
            imageState is AsyncImagePainter.State.Error ->
                Text(stringResource(R.string.image_viewer_load_failed), color = Color.White)
            imageState !is AsyncImagePainter.State.Success -> CircularProgressIndicator(color = Color.White)
        }
    }
}
