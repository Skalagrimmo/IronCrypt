package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.example.R
import com.example.data.AudioSettings
import com.example.game.model.SoundCue
import java.util.EnumMap

/**
 * Lightweight SoundPool + MediaPlayer audio manager tuned for Exynos 7870.
 * Handles looping crypt ambience and low-latency combat/world SFX with lifecycle safety.
 */
class IronCryptSoundManager(private val context: Context) {

    private var soundPool: SoundPool? = null
    private val sampleIds = EnumMap<SoundCue, Int>(SoundCue::class.java)
    private var ambientPlayer: MediaPlayer? = null

    private var settings = AudioSettings()
    private var isAppPaused = false
    private var isMenuDucked = false
    private var released = false

    init {
        initAudio()
    }

    private fun initAudio() {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val pool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build()
            soundPool = pool

            sampleIds[SoundCue.SWING] = pool.load(context, R.raw.sfx_swing, 1)
            sampleIds[SoundCue.CLEAVE] = pool.load(context, R.raw.sfx_cleave, 1)
            sampleIds[SoundCue.HIT] = pool.load(context, R.raw.sfx_hit, 1)
            sampleIds[SoundCue.CRIT] = pool.load(context, R.raw.sfx_crit, 1)
            sampleIds[SoundCue.PLAYER_HIT] = pool.load(context, R.raw.sfx_player_hit, 1)
            sampleIds[SoundCue.DASH] = pool.load(context, R.raw.sfx_dash, 1)
            sampleIds[SoundCue.POTION] = pool.load(context, R.raw.sfx_potion, 1)
            sampleIds[SoundCue.CHEST] = pool.load(context, R.raw.sfx_chest, 1)
            sampleIds[SoundCue.LEVER] = pool.load(context, R.raw.sfx_lever, 1)
        }
        syncAmbientPlayback()
    }

    fun applySettings(newSettings: AudioSettings) {
        settings = newSettings
        syncAmbientPlayback()
    }

    fun setMenuDucked(ducked: Boolean) {
        if (isMenuDucked == ducked) return
        isMenuDucked = ducked
        updateAmbientVolume()
    }

    fun onAppResume() {
        isAppPaused = false
        syncAmbientPlayback()
    }

    fun onAppPause() {
        isAppPaused = true
        runCatching {
            if (ambientPlayer?.isPlaying == true) {
                ambientPlayer?.pause()
            }
        }
    }

    fun playCue(cue: SoundCue) {
        if (released || !settings.sfxEnabled || settings.sfxVolume <= 0.01f) return
        val pool = soundPool ?: return
        val sampleId = sampleIds[cue] ?: return
        val vol = settings.sfxVolume.coerceIn(0f, 1f)
        val priority = if (cue == SoundCue.CRIT || cue == SoundCue.CLEAVE || cue == SoundCue.LEVER) 2 else 1
        val rate = when (cue) {
            SoundCue.SWING, SoundCue.HIT -> 0.95f + (System.nanoTime() and 0x7) * 0.015f
            else -> 1.0f
        }
        runCatching {
            pool.play(sampleId, vol, vol, priority, 0, rate)
        }
    }

    fun playCues(cues: List<SoundCue>) {
        if (cues.isEmpty()) return
        for (i in cues.indices) {
            playCue(cues[i])
        }
    }

    private fun syncAmbientPlayback() {
        if (released) return
        if (!settings.ambientEnabled || settings.ambientVolume <= 0.01f || isAppPaused) {
            runCatching {
                if (ambientPlayer?.isPlaying == true) {
                    ambientPlayer?.pause()
                }
            }
            return
        }

        if (ambientPlayer == null) {
            runCatching {
                ambientPlayer = MediaPlayer.create(context, R.raw.amb_crypt_drone)?.apply {
                    isLooping = true
                }
            }
        }
        updateAmbientVolume()
        runCatching {
            val mp = ambientPlayer ?: return@runCatching
            if (!mp.isPlaying) {
                mp.start()
            }
        }
    }

    private fun updateAmbientVolume() {
        val duckFactor = if (isMenuDucked) 0.45f else 1.0f
        val vol = (settings.ambientVolume * duckFactor).coerceIn(0f, 1f)
        runCatching {
            ambientPlayer?.setVolume(vol, vol)
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching {
            ambientPlayer?.stop()
            ambientPlayer?.release()
        }
        ambientPlayer = null
        runCatching {
            soundPool?.release()
        }
        soundPool = null
        sampleIds.clear()
    }
}
