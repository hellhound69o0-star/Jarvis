package com.example.selflearn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var engine: SelfLearningEngine
    private lateinit var router: CommandRouter
    private var lastCommand: String = ""

    companion object {
        private const val SPEECH_REQUEST_CODE = 100
        private const val MIC_PERMISSION_CODE = 200
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val modelFile = File(filesDir, "model.dat")
        engine = SelfLearningEngine(modelFile)
        router = CommandRouter(this, engine)
        router.bootstrapIfEmpty()

        val commandInput = findViewById<EditText>(R.id.commandInput)
        val resultView = findViewById<TextView>(R.id.resultView)
        val goButton = findViewById<Button>(R.id.goButton)
        val micButton = findViewById<Button>(R.id.micButton)
        val correctButton = findViewById<Button>(R.id.correctButton)
        val quickFiles = findViewById<Button>(R.id.quickFiles)
        val quickFirewall = findViewById<Button>(R.id.quickFirewall)

        correctButton.isEnabled = false
        resultView.text = "At your service. Try \"call mom\", \"open camera\", " +
            "\"pause music\", \"search pizza near me\", or \"show my files\"."

        goButton.setOnClickListener {
            val command = commandInput.text.toString().trim()
            if (command.isBlank()) return@setOnClickListener
            lastCommand = command
            val result = router.handle(command)
            resultView.text = result.message +
                (if (result.intent != null) "\n\n(understood as: ${result.intent})" else "")
            correctButton.isEnabled = result.intent != null
        }

        micButton.setOnClickListener { requestMicAndListen() }

        correctButton.setOnClickListener {
            if (lastCommand.isNotBlank()) showCorrectionDialog(lastCommand, resultView)
        }

        quickFiles.setOnClickListener { startActivity(Intent(this, FileBrowserActivity::class.java)) }
        quickFirewall.setOnClickListener { startActivity(Intent(this, FirewallSettingsActivity::class.java)) }

        val voiceModeSwitch = findViewById<Switch>(R.id.voiceModeSwitch)
        voiceModeSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE)
                    voiceModeSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                startService(Intent(this, VoiceService::class.java))
                Toast.makeText(this, "Voice mode on — say \"Jarvis\" followed by a command.", Toast.LENGTH_LONG).show()
            } else {
                val stopIntent = Intent(this, VoiceService::class.java).apply { action = VoiceService.ACTION_STOP }
                startService(stopIntent)
                Toast.makeText(this, "Voice mode off.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestMicAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_CODE)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command")
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "No speech recognizer available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestMicAndListen()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val heard = results?.firstOrNull() ?: return
            findViewById<EditText>(R.id.commandInput).setText(heard)
            findViewById<Button>(R.id.goButton).performClick()
        }
    }

    /** The self-correction flow: user says the guess was wrong, picks the right intent
     *  from a list, and that becomes a brand-new training example immediately — this is
     *  the "learns from its mistakes" behavior in practice. */
    private fun showCorrectionDialog(command: String, resultView: TextView) {
        val intents = router.allIntents()
        val labels = intents.map { it.name.replace("_", " ") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("What should \"$command\" have done?")
            .setItems(labels) { _, which ->
                val chosen = intents[which]
                router.correct(command, chosen)
                resultView.text = "Got it — learned that \"$command\" means $chosen. Try it again!"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
