# Wayfinder: assistant, chats and Space

Type `?` in the app search to open the question composer (multi-line). Where the question goes is chosen
in **Settings → Assistant & chats**:

| Mode | What happens |
|---|---|
| **Gemini app** (default) | The question is handed to the Gemini app (or Gemini in the browser). Waypoint sends nothing itself. |
| **Your API** | The question starts a conversation in **Wayfinder**, Waypoint's own assistant app (it appears in your app list as *Wayfinder*). Replies stream in and keep streaming if you leave the screen. |

## API setup
- **Provider presets**: OpenAI, Gemini API (Google's OpenAI-compatible endpoint), Anthropic (Claude),
  OpenRouter, Groq, Ollama (local). Or set a custom base URL.
- **API format**: OpenAI-compatible (`POST {base}/chat/completions`, `Authorization: Bearer`) or
  Anthropic Messages (`POST {base}/messages`, `x-api-key`, `anthropic-version`). Both stream with
  server-sent events; a non-streaming JSON reply is also accepted.
- **Model**: any model name your provider accepts.
- **API key**: encrypted at rest with AES-GCM under a non-exportable Android Keystore key. It is only
  sent to the base URL you set. *Privacy & data → Forget API key* removes it.
- **System prompt**: optional, sent with every conversation.
- **About you**: facts about you (name, work, preferences). Sent as background with each conversation
  with the instruction to use it only when relevant; toggle *Use “About you”* to stop sending it.
- **Test connection** sends a one-line request and shows the reply.

## Image generation
- **Image model** (Assistant settings → Image generation) uses the same provider, URL and key as chat.
  Presets fill a default: OpenRouter `google/gemini-2.5-flash-image-preview`, OpenAI `gpt-image-1`,
  Gemini API `imagen-3.0-generate-002`. Leave it empty to turn images off.
- **Image API**: *Auto* (chat style for OpenRouter, Images endpoint elsewhere), *Chat* (`/chat/completions`
  with `modalities: ["image","text"]`; images come back as data URLs) or *Images endpoint*
  (`/images/generations`, `b64_json`/`url`). *Size* applies to the Images endpoint.
- In Chat, tap **▣** (image mode) or start a message with `/image …` (also `/img`, `/draw`). The picture is
  saved in app storage (`files/chat_images`) with the conversation; tap it to **Share** or **Save to
  Photos** (Pictures/Waypoint, Android 10+). Image turns aren't sent as context to the text model.
- `?/image a red fox` in the app search works too (API mode).

## Web search (Perplexity-style)
*Setup → Web search*: **Auto** (default: Wayfinder decides), **Always**, or **Off**. The globe in the composer
forces a search for one message.
1. **Plan**: the chat model gets the latest message, recent turns and (if set) *About you*, and replies with
   JSON: whether to search, 1–3 specific queries (resolving "there" from earlier in the conversation), and
   whether *About you* is relevant. *About you* is then sent to the answer only if it matters.
2. **Search** on the phone: **DuckDuckGo**'s HTML endpoint (no key, like small open-source clients such as
   `duckduckgo-search`), or **Brave Search** with your key, or **OpenRouter web** (the provider searches;
   citations come back as annotations).
3. **Read**: the top 4 pages are fetched in parallel (6 s each, 1.5 MB cap) and reduced to readable text.
4. **Answer** with numbered sources; `[n]` citations become small red links, and source cards appear under
   the answer. Sources are saved with the message.

Only the search queries go to the search engine; pages are fetched directly from their sites.

## Space: notes, voice, screenshots, to-dos, goals, reminders
Space (in Wayfinder) keeps everything in one list with filters (All · Voice · Notes · Screens · To-do ·
Goals) and automatic **categories** (Work, Personal, Ideas, Shopping, Health, Finance, Travel, Learning,
Home, Other):
- **Categorising** happens on the phone (keyword classifier, English + Persian). With *AI title &
  summary* on, the chat model's JSON analysis refines category, title and summary.
- **Reminders from what you say or write**: "remind me to call mom tomorrow at 5pm", "remember to pay rent
  on Friday", "don't forget… tonight", "in 2 hours", Persian "یادم بنداز فردا ساعت ۵ …" become to-dos with
  a reminder, parsed on the phone (the AI adds more when enabled). Reminders are notifications with
  *Done* and *Snooze 10 min*, exact when allowed, re-armed after reboot.
- **Written notes** with title, text and images (photo picker, no permission).
- **Screenshots**: add any image, or turn on *Add screenshots automatically* (photo access); text is read on
  the phone (ML Kit, Latin script); with AI on, the image is also described by your model.
- **To-dos**: tap the circle to finish, tap to edit date/time/reminder; typing "call mom tomorrow at 5" as a
  title sets the time too.
- **Goals**: "3 times a week", "every day", "12 in total" with dot progress, streaks and +1 check-ins.
- The Home pin opens Space ready to record (long-press starts recording at once).

## Space (voice notes)
Like Nothing's Essential Space: tap the red button in **Wayfinder → Space** (or long-press the Wayfinder pin on
Home) to record. Recording runs in a foreground service with a notification and a Stop button, so it
continues with the screen off.
- Audio is saved as 16 kHz mono WAV in app storage (`files/space`).
- **Speech to text — On this phone (default)**: Android's on-device speech recognizer reads the saved
  recording through a pipe (Android 13+, `EXTRA_AUDIO_SOURCE`, segmented session for long notes). The
  audio never leaves the phone and it works offline. Pick the language and download its model once in
  Wayfinder Setup → Space.
- **Speech to text — Cloud (optional)**: your provider instead. *Auto* uses an audio chat model on
  OpenRouter and the Gemini API (`input_audio`, e.g. `google/gemini-2.5-flash`) and the Whisper endpoint
  (`/audio/transcriptions`) elsewhere; long recordings go in 5-minute parts.
- **AI title & summary** (on by default, can be turned off): after transcription the chat model writes a
  title, a short summary and action items. This sends the transcript text, never the audio. Off = Space
  stays fully on the phone.
- Each note: play/seek, summary, selectable transcript, Copy, Share, **Ask** (starts a conversation with
  the note as context), rename, transcribe again, delete.
- Stored in `space.db` + `files/space`, excluded from backup. *Privacy & data → Delete all voice notes*.

## Home shortcut
A Wayfinder button in a bottom corner of Home (left by default; right or off in settings). Tap: open
Wayfinder. Long-press: start recording a voice note.

## Chats
- Inside a conversation, **⋯** offers Rename, Delete and New chat.
- Conversation list, newest first: tap to continue, long-press to rename or delete.
- Messages render basic Markdown (code blocks, `code`, **bold**, *italic*, headings, lists).
  Long-press a message to copy it.
- The send button turns into **Stop** while a reply streams. *Regenerate* / *Retry* replaces the last reply.
- The last 40 turns of a conversation are sent as context.
- Conversations live in `chats.db` on the phone, excluded from cloud backup and device transfer.
  *Privacy & data → Delete all chats* removes them.

## For later (agent)
The pieces are separated so the chat can grow into an agent: `ChatProtocol` (pure request/response and
SSE handling, tested), `ChatClient` (HTTP streaming), `ChatEngine` (runs turns in the app scope and
persists them), `ChatRepository` (storage). Tool calling would slot into `ChatProtocol` + `ChatEngine`.

## Live check in CI (optional)
To verify a provider end to end on GitHub's runners, add a repository secret **`WAYFINDER_LIVE_KEY`**
(Settings → Secrets and variables → Actions) and, optionally, variables `WAYFINDER_LIVE_URL`
(default `https://api.gapgpt.app/v1`) and `WAYFINDER_LIVE_MODEL` (default `gpt-4o-mini`). Each CI run then
executes `LiveApiTest` with the app's own code: streaming chat → search planner → DuckDuckGo search → page
reading → cited answer, and writes the result to the job summary. The key is read from the secret only
(never stored in the repo or printed). Without the secret the check is skipped.
