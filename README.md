# Jarvis (voice-activated edition)

Same app as the base Jarvis project, plus a **"Voice mode" switch** on the main screen.
Flip it on and it listens continuously for you to say **"Jarvis"** followed by a command
— no tapping the mic each time — and it **speaks its response back to you** using
Android's built-in text-to-speech.

Built to run comfortably on a **1GB RAM / 64GB storage** phone — no cloud AI, no heavy
ML framework, everything runs locally.

## How voice mode works

`VoiceService.kt` runs as a foreground service (you'll see a persistent "Jarvis is
listening" notification while it's on — required by Android for anything using the mic
in the background). It keeps a speech recognizer in a loop: listen → check if you said
"Jarvis" → if so, treat everything after that word as your command → run it → speak the
result → immediately start listening again.

Say something like: **"Jarvis, open camera"** or **"Jarvis, call mom"**.

**Honest limitations:**
- Android's speech recognizer typically needs an internet connection (unless your phone
  has an offline language pack) — leaving voice mode on will use some mobile data.
- This is not a true system-wide hotword like "Hey Google" — it only listens while the
  service is running (started from the app's Voice mode switch), not system-wide when
  the app is fully closed. Android's battery optimizations may eventually pause it if
  the phone is idle for a long time; reopening the app and flipping the switch again
  restarts it.
- Continuous listening does use more battery than the tap-to-speak version — turn it off
  when you're not actively using it.

## What it does

Say "Jarvis" + a command, or type/tap the mic manually like before, and it:
- **Plays / pauses music** — sends a real media-key event to whatever music app is active.
- **Opens apps** — "open camera", "launch spotify" — matches against your installed apps.
- **Sends messages** — opens your messaging app pre-filled with contact + text.
- **Calls people** — opens the dialer pre-filled (you tap call to confirm).
- **Searches Google** — "search pizza near me" opens a search / browser with your query.
- **Browses your phone's storage** — a lightweight file browser to look through files.
- **Manages a firewall** — pick which apps get network access at all.

## The "self-learning" part

There's no cloud model and no big neural net (see `SelfLearningEngine.kt`, carried over
from the base engine). Commands are classified into one of a fixed set of actions using
a tiny online logistic-regression model with a handful of seed examples per action.

**The important part — learning from mistakes**: when it gets a command wrong, tap
**"That's wrong — correct it"**, pick what it should have done from the list, and that
exact correction becomes a new training example immediately. There's no separate
"retrain" step — the very next time you say something similar, it uses the correction.
This is a real (if simple) feedback loop, not a scripted response.

## The firewall — how it actually works

`firewall/SimpleFirewallService.kt` uses Android's built-in `VpnService` API — the same
mechanism behind well-known no-root firewalls like NetGuard. No traffic-shaping tricks,
no root needed:

- Apps you leave **unchecked** in Firewall settings are excluded from the VPN tunnel
  (`addDisallowedApplication`), so their traffic goes straight to the internet, completely
  unaffected.
- Apps you **check** (block) get routed *into* the tunnel — and the service simply never
  forwards anything it reads from the tunnel anywhere. No forwarding = no network access
  for those apps. That's the entire blocking mechanism; it's simple by design so it stays
  cheap to run on a low-RAM device.

You'll get the standard Android "this app wants to set up a VPN connection" system prompt
the first time you start it — that's Android's normal safeguard for any VPN-based app, not
something specific to this one.

## Permissions used, and why

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | Voice commands via the mic button |
| `INTERNET` | Required to run a `VpnService` (even one that only blocks traffic) |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | The firewall runs as a foreground service with a visible "Firewall active" notification, as Android requires |
| `QUERY_ALL_PACKAGES` | Lets the app list installed apps for the launcher and the firewall's app list |
| `READ_EXTERNAL_STORAGE` (API ≤ 32 only) | The file browser |

Deliberately **not** requested: `SEND_SMS`, `CALL_PHONE`. Messaging and calling use
`ACTION_SENDTO` / `ACTION_DIAL`, which hand off to your default messaging/dialer app with
everything pre-filled — you just tap send/call. This avoids the most sensitive permissions
entirely and also avoids Play Store's tightest restricted-permission review process, in
case you ever want to distribute it.

## Files

```
app/src/main/java/com/example/selflearn/
  SelfLearningEngine.kt        the on-device learning model
  CommandRouter.kt             classifies commands, extracts a target, dispatches actions
  MainActivity.kt              main UI: command box, mic, correction flow, quick actions
  FileBrowserActivity.kt       storage browser
  FirewallSettingsActivity.kt  per-app allow/block list + start/stop
  firewall/SimpleFirewallService.kt   the VPN-based firewall itself
```

## Building it

1. Open the project folder in Android Studio.
2. Let Gradle sync — dependencies are just AndroidX/AppCompat, nothing heavy.
3. Run on a device or emulator (`minSdk 21`, covers essentially every phone still in use).
4. First launch: try "pause music", "open camera", "call mom", "search weather" to see it
   in action. If a guess is wrong, correct it — that's the point.

## Honest limitations

- Command understanding is simple pattern-matching, not a language model — it won't hold
  a conversation, only route short commands to actions.
- Target extraction (who to call, what to search) is a basic word-stripping heuristic, not
  full NLU — see `extractTarget()` in `CommandRouter.kt` if you want to improve parsing.
- The firewall blocks at the "no network at all" level per app — it doesn't do fine-grained
  per-domain filtering. That's a deliberate simplicity trade-off for low-RAM devices.
- This hasn't been compiled/run on a physical device as part of building it here — it
  follows standard, documented Android APIs throughout, but please test on your device
  and let me know if anything needs adjusting.
