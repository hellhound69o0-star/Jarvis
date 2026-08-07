package com.example.selflearn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.io.File

/**
 * Continuous "wake word" voice service — the "voice activated" version of Jarvis.
 *
 * How it works: it keeps a speech recognizer running in a loop (listen -> get result or
 * timeout -> immediately listen again). Every time it hears something, it checks whether
 * the word "jarvis" was said. If so, whatever comes AFTER "jarvis" in that sentence is
 * treated as the actual command and run through the same CommandRouter used by the
 * tap-to-speak version. It also speaks its response out loud using Android's built-in
 * text-to-speech, so it actually talks back — no extra libraries needed for either part.
 *
 * Honest notes:
 *  - Android's speech recognizer typically needs an internet connection (unless your
 *    device has an offline language pack installed) — this will use some mobile data if
 *    you leave it running on cellular.
 *  - This runs while the service is alive (started from the app, shown via a persistent
 *    "Jarvis is listening" notification) — it is not a true always-on system assistant
 *    like Google Assistant's hotword, which requires deeper system-level access.
 */
class VoiceService : Service(), RecognitionListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech
    private lateinit var router: CommandRouter
    private lateinit var engine: SelfLearningEngine
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    companion object {
        const val CHANNEL_ID = "voice_channel"
        const val NOTIF_ID = 77
        const val WAKE_WORD = "jarvis"
        const val ACTION_STOP = "com.example.selflearn.voice.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        val modelFile = File(filesDir, "model.dat")
        engine = SelfLearningEngine(modelFile)
        router = CommandRouter(this, engine)
        router.bootstrapIfEmpty()

        tts = TextToSpeech(this) { }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Listening for \"Jarvis\"..."))
        running = true
        startListeningOnce()
        return START_STICKY
    }

    private fun startListeningOnce() {
        if (!running) return
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
            relisten()
        }
    }

    private fun relisten() {
        handler.postDelayed({ if (running) startListeningOnce() }, 400)
    }

    override fun onResults(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (heard != null) handleHeard(heard)
        relisten()
    }

    private fun handleHeard(heard: String) {
        val lower = heard.lowercase()
        if (!lower.contains(WAKE_WORD)) return

        val command = lower.substringAfter(WAKE_WORD).trim()
        if (command.isBlank()) {
            speak("Yes?")
            updateNotification("Heard you — say a command after \"Jarvis\".")
            return
        }
        val result = router.handle(command)
        speak(result.message)
        updateNotification(result.message)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun stopListening() {
        running = false
        try { speechRecognizer.stopListening() } catch (e: Exception) { }
        stopForeground(true)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Jarvis Voice", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, VoiceService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running = false
        try { speechRecognizer.destroy() } catch (e: Exception) { }
        tts.shutdown()
        super.onDestroy()
    }

    // Required by RecognitionListener but not needed here
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onError(error: Int) { relisten() }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onBind(intent: Intent?): IBinder? = null
}
