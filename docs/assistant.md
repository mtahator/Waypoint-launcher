# Assistant and Chat

Type `?` in the app search to open the question composer (multi-line). Where the question goes is chosen
in **Settings → Assistant & chats**:

| Mode | What happens |
|---|---|
| **Gemini app** (default) | The question is handed to the Gemini app (or Gemini in the browser). Waypoint sends nothing itself. |
| **Your API** | The question starts a conversation in **Chat**, Waypoint's own chat app (it appears in your app list). Replies stream in and keep streaming if you leave the screen. |

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

## Chat
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
