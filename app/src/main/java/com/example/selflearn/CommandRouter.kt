package com.example.selflearn

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent

/**
 * Known things the assistant can do. Each has a set of BOOTSTRAP phrases used to seed
 * the learning engine on first run, so it isn't guessing from zero examples — but every
 * one of these bootstrap examples is just a normal training call, so your own usage and
 * corrections carry exactly the same weight and steadily reshape the model.
 */
enum class CommandIntent(val bootstrap: List<String>) {
    PLAY_MUSIC(listOf("play music", "resume song", "unpause music", "play the song", "continue playing")),
    PAUSE_MUSIC(listOf("pause music", "stop the song", "pause the song", "stop playing")),
    OPEN_APP(listOf("open spotify", "open camera", "launch instagram", "go to settings", "open the app")),
    SEND_MESSAGE(listOf("message mom", "text john", "send a message to sarah", "text dad hello")),
    CALL(listOf("call mom", "phone john", "dial sarah", "call dad")),
    SEARCH(listOf("search cats", "google weather today", "look up recipe", "search for news")),
    BROWSE_FILES(listOf("show my files", "open storage", "browse phone space", "look through my files")),
    FIREWALL(listOf("open firewall", "firewall settings", "network settings", "block apps"))
}

data class CommandResult(val message: String, val intent: CommandIntent?, val handled: Boolean)

class CommandRouter(private val context: Context, private val engine: SelfLearningEngine) {

    /** Call once, e.g. from Application/Activity onCreate, to seed the model if it's brand new. */
    fun bootstrapIfEmpty() {
        if (engine.knownLabels().isNotEmpty()) return
        for (intent in CommandIntent.values()) {
            for (phrase in intent.bootstrap) {
                engine.train(phrase, intent.name)
            }
        }
        engine.save()
    }

    /** Classify a raw command string and run it. */
    fun handle(rawCommand: String): CommandResult {
        val prediction = engine.predict(rawCommand)
        if (prediction == null) {
            return CommandResult("I don't know how to handle that yet.", null, false)
        }
        val intent = try { CommandIntent.valueOf(prediction.first) } catch (e: Exception) { null }
        if (intent == null) return CommandResult("I don't know how to handle that yet.", null, false)

        val target = extractTarget(rawCommand, intent)
        val message = execute(intent, target)
        return CommandResult(message, intent, true)
    }

    /** Called when the user says the guess was WRONG and picks the correct intent instead.
     *  This single call is the whole "learns from its mistakes" loop: the corrected
     *  (command, intent) pair becomes a real training example, same as any other. */
    fun correct(rawCommand: String, correctIntent: CommandIntent) {
        engine.train(rawCommand, correctIntent.name)
        engine.save()
    }

    /** All possible intents, for building a "what did you mean?" picker. */
    fun allIntents(): Array<CommandIntent> = CommandIntent.values()

    // ---------- Very simple target extraction ----------
    // Strips common trigger words and returns whatever's left as the "target"
    // (app name / contact / search query / etc). Deliberately simple — swap in
    // something smarter later if you want richer parsing.
    private fun extractTarget(raw: String, intent: CommandIntent): String {
        val triggerWords = setOf(
            "play", "resume", "unpause", "pause", "stop", "open", "launch", "go", "to",
            "message", "text", "send", "a", "call", "phone", "dial",
            "search", "google", "look", "up", "for", "show", "browse", "my", "the",
            "music", "song", "app", "files", "storage", "firewall", "settings"
        )
        val remaining = raw.lowercase().split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in triggerWords }
        return remaining.joinToString(" ").trim()
    }

    // ---------- Dispatch to actual phone actions ----------
    private fun execute(intent: CommandIntent, target: String): String {
        return when (intent) {
            CommandIntent.PLAY_MUSIC -> { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); "Sent play to your music app." }
            CommandIntent.PAUSE_MUSIC -> { dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); "Sent pause to your music app." }
            CommandIntent.OPEN_APP -> openApp(target)
            CommandIntent.SEND_MESSAGE -> sendMessage(target)
            CommandIntent.CALL -> callContact(target)
            CommandIntent.SEARCH -> search(target)
            CommandIntent.BROWSE_FILES -> {
                context.startActivity(newActivityIntent(FileBrowserActivity::class.java))
                "Opening file browser."
            }
            CommandIntent.FIREWALL -> {
                context.startActivity(newActivityIntent(FirewallSettingsActivity::class.java))
                "Opening firewall settings."
            }
        }
    }

    private fun newActivityIntent(cls: Class<*>): Intent {
        return Intent(context, cls).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun openApp(nameGuess: String): String {
        if (nameGuess.isBlank()) return "Tell me which app to open."
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, 0)
        val match = apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(nameGuess.lowercase())
        }
        if (match == null) return "Couldn't find an app matching \"$nameGuess\"."
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            return "Opening ${match.loadLabel(pm)}."
        }
        return "Found the app but couldn't launch it."
    }

    private fun sendMessage(target: String): String {
        if (target.isBlank()) return "Tell me who to message and what to say."
        val parts = target.split(" ", limit = 2)
        val contact = parts.getOrElse(0) { "" }
        val body = parts.getOrElse(1) { "" }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("address", contact)
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opening your messaging app to text $contact."
        } catch (e: Exception) {
            "No messaging app found to handle this."
        }
    }

    private fun callContact(target: String): String {
        if (target.isBlank()) return "Tell me who to call."
        // Uses ACTION_DIAL (opens the dialer pre-filled) rather than ACTION_CALL, so it
        // never needs the sensitive CALL_PHONE permission — you just tap the call button.
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${Uri.encode(target)}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opening dialer for $target — tap call to confirm."
        } catch (e: Exception) {
            "Couldn't open the dialer."
        }
    }

    private fun search(query: String): String {
        if (query.isBlank()) return "Tell me what to search for."
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Searching for \"$query\"."
        } catch (e: Exception) {
            // Fallback straight to a browser if no search app handles it.
            val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
            val fallback = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
            "Searching for \"$query\" in your browser."
        }
    }
}
