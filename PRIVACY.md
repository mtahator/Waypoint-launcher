# Waypoint privacy policy

- Waypoint's features work on your device. The one exception is **exchange rates**: only when you type a
  currency conversion in the drawer search (e.g. `1$ to £`) and the cached rates are older than 6 hours,
  Waypoint downloads public USD rates from `open.er-api.com`. The request is a fixed URL; **nothing you
  type is sent**. Turn it off or clear the cache in *Privacy & data*. (The upstream optional "Daily
  wallpaper" feature downloads wallpapers only if you turn it on.)
- **Ask (`?`)**: in *Gemini app* mode the question is handed to the Gemini app (or Gemini in your
  browser) only when you tap *Send*; Waypoint itself doesn't transmit it. In *Your API* mode, the
  conversation is sent **only to the base URL you configured**, only when you send a message. Chats are
  stored on the phone (`chats.db`, excluded from backup); the API key is encrypted with an Android
  Keystore key. Delete chats or forget the key in *Privacy & data*.
- **Space voice notes** are recorded only while you record (with a visible notification), saved on the
  phone (`space.db`, `files/space`, excluded from backup), and sent to your configured provider **only
  for transcription and summary**, and only if you set a transcription model. *About you* is sent with
  conversations only while its toggle is on.
- **No analytics, no ads, no crash-reporting SDKs.**
- **Notification capture** is off by default. When you enable it, Waypoint reads the title and text of
  notifications **only from the apps you select**, extracts amount / currency / merchant / direction /
  time, and stores **only those fields** in a private database on your phone. Raw notification text is
  never stored or logged.
- The ledger is excluded from Android backup and device-to-device transfer.
- **Export** happens only when you tap *Save* or *Share*. You choose the destination.
- You can delete the ledger at any time in *Settings → Waypoint → Privacy & data*,
  and revoke notification access in Android settings.
