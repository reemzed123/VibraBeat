package com.reemzedek.vibrabeat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var levelText: TextView
    private lateinit var meter: View
    private lateinit var maxText: TextView
    private lateinit var sensitivityText: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null
    private var source = Source.INTERNAL
    private var maxIntensity = 85
    private var sensitivity = 55
    private lateinit var vibrator: Vibrator
    private val projectionRequest = 4102
    private val micRequest = 4103

    enum class Source { INTERNAL, MIC }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF0B1020.toInt()
        window.navigationBarColor = 0xFF0B1020.toInt()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setBackgroundColor(0xFF0B1020.toInt())
        }
        val title = text("VibraBeat", 30f, 0xFFFFFFFF.toInt(), true)
        root.addView(title)
        root.addView(text("מרגישים את הבס. רוטטים עם הקצב.", 14f, 0xFF9EADC5.toInt()))
        addGap(22)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(0xFF172238.toInt(), 18)
        }
        panel.addView(text("מקור שמע", 16f, 0xFFFFFFFF.toInt(), true))
        val sourceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val internal = button("שמע פנימי") { source = Source.INTERNAL; updateSourceButtons(internal, mic) }
        val mic = button("מיקרופון") { source = Source.MIC; updateSourceButtons(internal, mic) }
        sourceRow.addView(internal, weightParams())
        sourceRow.addView(mic, weightParams())
        panel.addView(sourceRow)
        updateSourceButtons(internal, mic)
        addGapTo(panel, 18)

        panel.addView(text("עוצמת רטט מרבית", 15f, 0xFFFFFFFF.toInt(), true))
        maxText = text("$maxIntensity%", 14f, 0xFF63D9FF.toInt())
        panel.addView(maxText)
        val maxSeek = SeekBar(this).apply {
            max = 100; progress = maxIntensity
            setOnSeekBarChangeListener(simpleSeek { value -> maxIntensity = value.coerceAtLeast(1); maxText.text = "$maxIntensity%" })
        }
        panel.addView(maxSeek)
        addGapTo(panel, 10)

        panel.addView(text("רגישות לזיהוי בס", 15f, 0xFFFFFFFF.toInt(), true))
        sensitivityText = text("$sensitivity%", 14f, 0xFF63D9FF.toInt())
        panel.addView(sensitivityText)
        val sensSeek = SeekBar(this).apply {
            max = 100; progress = sensitivity
            setOnSeekBarChangeListener(simpleSeek { value -> sensitivity = value.coerceAtLeast(1); sensitivityText.text = "$sensitivity%" })
        }
        panel.addView(sensSeek)
        addGapTo(panel, 14)

        panel.addView(text("עוצמת הבס בזמן אמת", 14f, 0xFFB8C6DC.toInt()))
        meter = View(this).apply { setBackgroundColor(0xFF263A56.toInt()) }
        panel.addView(meter, LinearLayout.LayoutParams(-1, dp(9)))
        levelText = text("ממתין להפעלת שמע", 13f, 0xFF9EADC5.toInt())
        panel.addView(levelText)
        root.addView(panel)
        addGap(20)

        val start = button("התחל לזהות ולהרטיט") { startListening() }
        start.setTextColor(0xFF07111F.toInt())
        start.background = rounded(0xFF63D9FF.toInt(), 14)
        root.addView(start, LinearLayout.LayoutParams(-1, dp(54)))
        addGap(10)
        val stop = button("עצור") { stopListening("הזיהוי הופסק") }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(48)))
        addGap(14)
        status = text("מוכן. בחר מקור שמע ולחץ התחל.", 13f, 0xFF9EADC5.toInt())
        root.addView(status)
        addGap(10)
        root.addView(text("הערה: בשלב זה האפליקציה פועלת כשהיא פתוחה על המסך. שמע פנימי זמין רק מאפליקציות שמאפשרות לכידת שמע.", 12f, 0xFF71839F.toInt()))
        setContentView(root)
    }

    private fun updateSourceButtons(internal: Button, mic: Button) {
        internal.background = rounded(if (source == Source.INTERNAL) 0xFF285C78.toInt() else 0xFF26334A.toInt(), 10)
        mic.background = rounded(if (source == Source.MIC) 0xFF285C78.toInt() else 0xFF26334A.toInt(), 10)
    }

    private fun startListening() {
        if (running) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micRequest)
            return
        }
        if (source == Source.INTERNAL) {
            status.text = "ממתין לאישור לכידת שמע פנימי…"
            @Suppress("DEPRECATION") startActivityForResult(projectionManager.createScreenCaptureIntent(), projectionRequest)
        } else {
            beginCapture(null)
        }
    }

    @Deprecated("Activity result used for MediaProjection consent")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                projection = projectionManager.getMediaProjection(resultCode, data)
                beginCapture(projection)
            } else status.text = "לא ניתן אישור ללכידת שמע פנימי. אפשר לבחור מיקרופון."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == micRequest && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else status.text = "נדרשת הרשאת מיקרופון/לכידת שמע כדי לנתח את המוזיקה."
    }

    private fun beginCapture(mediaProjection: MediaProjection?) {
        try {
            val sampleRate = 44100
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufferSize = max(minBuffer, 4096)
            recorder = if (mediaProjection != null) {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("מקליט השמע לא אותחל")
            recorder?.startRecording()
            running = true
            status.text = if (mediaProjection != null) "מנתח שמע פנימי ומזהה פעימות בס…" else "מקשיב דרך המיקרופון ומזהה פעימות בס…"
            worker = Thread { analyzeLoop() }.also { it.name = "VibraBeat-Audio"; it.start() }
        } catch (e: Exception) {
            stopListening("שגיאה בהפעלת מקור השמע: ${e.message ?: "לא ידועה"}")
        }
    }

    private fun analyzeLoop() {
        val audio = ShortArray(1024)
        var envelope = 0.0
        var noiseFloor = 0.004
        var lastBeat = 0L
        // Two simple one-pole filters create an approximate 45–180 Hz band-pass.
        var lowState = 0.0
        var highState = 0.0
        val dt = 1.0 / 44100.0
        val lowAlpha = 1.0 - kotlin.math.exp(-2.0 * Math.PI * 180.0 * dt)
        val highAlpha = 1.0 - kotlin.math.exp(-2.0 * Math.PI * 45.0 * dt)
        while (running) {
            val count = try { recorder?.read(audio, 0, audio.size) ?: -1 } catch (_: Exception) { -1 }
            if (count <= 0) { if (!running) break; continue }
            var sum = 0.0
            for (i in 0 until count) {
                val x = audio[i] / 32768.0
                lowState += lowAlpha * (x - lowState)
                highState += highAlpha * (lowState - highState)
                val band = lowState - highState
                sum += band * band
            }
            val rms = sqrt(sum / count)
            envelope = envelope * 0.55 + rms * 0.45
            noiseFloor = noiseFloor * 0.985 + min(envelope, noiseFloor * 1.8) * 0.015
            val sensitivityFactor = 2.15 - (sensitivity / 100.0 * 1.35)
            val threshold = max(0.006, noiseFloor * sensitivityFactor)
            val now = System.currentTimeMillis()
            val beat = envelope > threshold && envelope > 0.009 && now - lastBeat > 125
            if (beat) {
                lastBeat = now
                val normalized = ((envelope - threshold) / max(0.025, threshold * 3.2)).coerceIn(0.12, 1.0)
                val amplitude = (maxIntensity * normalized).toInt().coerceIn(1, maxIntensity).coerceIn(1, 255)
                val duration = (24 + normalized * 48).toLong()
                pulse(duration, amplitude)
            }
            val display = (envelope * 9000).toInt().coerceIn(0, 100)
            runOnUiThread {
                levelText.text = "עוצמת בס: $display%${if (beat) "  •  פעימה!" else ""}"
                meter.scaleX = (display / 100.0).coerceAtLeast(0.03).toFloat()
                meter.pivotX = 0f
                meter.setBackgroundColor(if (beat) 0xFF63D9FF.toInt() else 0xFF3987C9.toInt())
            }
        }
    }

    private fun pulse(duration: Long, amplitude: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude.coerceIn(1, 255)))
            else { @Suppress("DEPRECATION") vibrator.vibrate(duration) }
        } catch (_: Exception) { }
    }

    private fun stopListening(message: String) {
        running = false
        try { recorder?.stop() } catch (_: Exception) { }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        try { vibrator.cancel() } catch (_: Exception) { }
        status.text = message
        levelText.text = "ממתין להפעלת שמע"
    }

    override fun onDestroy() {
        stopListening("האפליקציה נסגרה")
        super.onDestroy()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; setTextColor(0xFFFFFFFF.toInt())
        background = rounded(0xFF26334A.toInt(), 10)
        setOnClickListener { action() }
    }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun addGap(value: Int) { root.addView(View(this), LinearLayout.LayoutParams(1, dp(value))) }
    private fun addGapTo(layout: LinearLayout, value: Int) { layout.addView(View(this), LinearLayout.LayoutParams(1, dp(value))) }
    private fun weightParams() = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) }
    private fun simpleSeek(onValue: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onValue(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
