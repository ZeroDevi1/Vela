package com.vela.app.player.mpv

import android.content.Context
import android.util.Log
import com.vela.player.preferences.PlayerPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object MpvWarmPool {
    private const val TAG = "MpvWarmPool"

    private val lock = Any()
    private var warmedPlayer: WarmedPlayer? = null
    private var warmingConfig: MpvWarmConfig? = null
    private var liveController: MpvPlayerController? = null
    private var creating = false

    suspend fun warmIfPreferred(context: Context) {
        val appContext = context.applicationContext
        val preferences = PlayerPreferences(appContext)
        val engineIsMpv = preferences.getPlayerEngine() == PlayerPreferences.PLAYER_ENGINE_MPV
        if (!engineIsMpv) {
            release()
            return
        }

        val config = MpvWarmConfig.from(preferences)
        var replacedPlayer: MpvPlayerController? = null
        val shouldStartWarmup = synchronized(lock) {
            val decision = decideMpvWarmup(
                engineIsMpv = true,
                hasLiveController = liveController != null || creating,
                alreadyWarmOrWarming = warmedPlayer?.config == config || warmingConfig == config
            )
            if (decision != MpvWarmDecision.StartWarmup) {
                false
            } else {
                replacedPlayer = warmedPlayer?.controller
                warmedPlayer = null
                warmingConfig = config
                creating = true
                true
            }
        }
        if (!shouldStartWarmup) {
            return
        }
        replacedPlayer?.release()

        val controller = try {
            withContext(Dispatchers.IO) {
                MPVLib.preload()
            }
            withContext(MpvPlayerController.LifecycleDispatcher) {
                MpvPlayerController(
                    context = appContext,
                    hardwareDecoding = config.hardwareDecoding,
                    videoOutput = config.videoOutput,
                    audioOutput = config.audioOutput,
                    listener = NoopListener
                )
            }
        } catch (error: CancellationException) {
            synchronized(lock) {
                if (warmingConfig == config) warmingConfig = null
                creating = false
            }
            throw error
        } catch (error: Throwable) {
            synchronized(lock) {
                if (warmingConfig == config) warmingConfig = null
                creating = false
            }
            Log.e(TAG, "MPV warmup skipped", error)
            return
        }

        val shouldKeep = synchronized(lock) {
            creating = false
            val currentConfig = MpvWarmConfig.from(PlayerPreferences(appContext))
            val canKeep = warmingConfig == config &&
                currentConfig == config &&
                liveController == null &&
                PlayerPreferences(appContext).getPlayerEngine() == PlayerPreferences.PLAYER_ENGINE_MPV
            warmingConfig = null
            if (canKeep) {
                warmedPlayer = WarmedPlayer(config, controller)
            }
            canKeep
        }

        if (!shouldKeep) {
            val liveExists = synchronized(lock) { liveController != null }
            if (!liveExists) {
                controller.release()
            }
        }
    }

    suspend fun obtain(
        context: Context,
        listener: MpvPlayerController.Listener
    ): MpvPlayerController {
        val appContext = context.applicationContext
        val config = MpvWarmConfig.from(PlayerPreferences(appContext))
        while (true) {
            var stalePlayer: MpvPlayerController? = null
            val action = synchronized(lock) {
                when {
                    creating -> ObtainAction.Wait
                    liveController != null -> ObtainAction.Use(liveController!!)
                    else -> {
                        val warmed = warmedPlayer
                        if (warmed != null) {
                            warmedPlayer = null
                            if (warmed.config == config) {
                                liveController = warmed.controller
                                ObtainAction.Use(warmed.controller)
                            } else {
                                stalePlayer = warmed.controller
                                ObtainAction.Wait
                            }
                        } else {
                            creating = true
                            ObtainAction.Create
                        }
                    }
                }
            }
            stalePlayer?.release()
            when (action) {
                ObtainAction.Wait -> delay(16)
                ObtainAction.Create -> break
                is ObtainAction.Use -> {
                    action.controller.setListener(listener)
                    return action.controller
                }
            }
        }

        try {
            val created = withContext(MpvPlayerController.LifecycleDispatcher) {
                MpvPlayerController(
                    context = appContext,
                    hardwareDecoding = config.hardwareDecoding,
                    videoOutput = config.videoOutput,
                    audioOutput = config.audioOutput,
                    listener = listener
                )
            }
            synchronized(lock) {
                creating = false
                liveController = created
            }
            return created
        } catch (error: CancellationException) {
            synchronized(lock) { creating = false }
            throw error
        } catch (error: Throwable) {
            synchronized(lock) { creating = false }
            throw error
        }
    }

    fun notifyReleased(controller: MpvPlayerController) {
        synchronized(lock) {
            if (liveController === controller) {
                liveController = null
            }
            if (warmedPlayer?.controller === controller) {
                warmedPlayer = null
            }
        }
    }

    fun release() {
        val player = synchronized(lock) {
            warmingConfig = null
            warmedPlayer?.controller.also {
                warmedPlayer = null
            }
        }
        player?.release()
    }

    private sealed class ObtainAction {
        data class Use(val controller: MpvPlayerController) : ObtainAction()
        data object Wait : ObtainAction()
        data object Create : ObtainAction()
    }

    private data class WarmedPlayer(
        val config: MpvWarmConfig,
        val controller: MpvPlayerController
    )

    private data class MpvWarmConfig(
        val hardwareDecoding: String,
        val videoOutput: String,
        val audioOutput: String,
        val cacheSizeMb: Int,
        val cacheTimeSeconds: Int,
        val subtitleTextSize: String,
        val subtitleScale: Float,
        val subtitleTextColor: String,
        val subtitleBackgroundColor: String,
        val subtitleEdgeType: String,
        val subtitleTextOpacityPercent: Int,
        val subtitlePosition: Int,
        val subtitleAssCompatible: Boolean,
        val upscaleFilter: String,
        val downscaleFilter: String,
        val toneMapping: String,
        val smoothMotion: Boolean,
        val deband: Boolean,
        val dynamicPeak: Boolean,
        val hdrToSdrTonemapping: Boolean
    ) {
        companion object {
            fun from(preferences: PlayerPreferences): MpvWarmConfig {
                return MpvWarmConfig(
                    hardwareDecoding = preferences.getMpvHardwareDecoding(),
                    videoOutput = preferences.getMpvVideoOutput(),
                    audioOutput = preferences.getMpvAudioOutput(),
                    cacheSizeMb = preferences.getPlayerCacheSizeMb(),
                    cacheTimeSeconds = preferences.getPlayerCacheTimeSeconds(),
                    subtitleTextSize = preferences.getSubtitleTextSize(),
                    subtitleScale = preferences.getSubtitleScale(),
                    subtitleTextColor = preferences.getSubtitleTextColor(),
                    subtitleBackgroundColor = preferences.getSubtitleBackgroundColor(),
                    subtitleEdgeType = preferences.getSubtitleEdgeType(),
                    subtitleTextOpacityPercent = preferences.getSubtitleTextOpacityPercent(),
                    subtitlePosition = preferences.getSubtitlePosition(),
                    subtitleAssCompatible = preferences.isSubtitleAssCompatible(),
                    upscaleFilter = preferences.getMpvUpscaleFilter(),
                    downscaleFilter = preferences.getMpvDownscaleFilter(),
                    toneMapping = preferences.getMpvToneMapping(),
                    smoothMotion = preferences.getMpvSmoothMotion(),
                    deband = preferences.getMpvDeband(),
                    dynamicPeak = preferences.getMpvDynamicPeak(),
                    hdrToSdrTonemapping = preferences.getMpvHdrToSdrTonemapping()
                )
            }
        }
    }

    private object NoopListener : MpvPlayerController.Listener {
        override fun onBuffering() = Unit
        override fun onReady() = Unit
        override fun onEnded() = Unit
    }
}
