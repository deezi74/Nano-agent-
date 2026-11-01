# Nano Agent privacy

Nano Agent runs on your phone. There is no Nano Agent server and no Nano Agent account.

- **Your chats, memory, files and settings stay on the phone** (in the app's private storage), unless you turn on a backup or sync yourself.
- **Your API keys** are stored on the phone in encrypted storage and are only sent to the provider they belong to (OpenAI, Anthropic, Google, xAI, OpenRouter, your own FreeLLMAPI or Ollama server, and so on).
- **What you send to a model** goes straight from the phone to the provider you picked, under that provider's privacy policy.
- **Community Relay** (optional, off unless you sign in): a relay server run by the nanoMuse project. If you sign in to it, your messages pass through that server to its models; read its policy at https://github.com/nano-muse/nanoMuse/blob/main/docs/privacy.md before using it. You can also point the app at a relay you host yourself.
- **Hands** (optional, off by default): when you turn on Nano Agent under Android Settings → Accessibility, the agent can read and tap the screen of other apps to do what you ask. It stops and asks before anything you could not undo (payments, sending, deleting). Turn it off at any time in the same place.
- **Update check**: the app asks GitHub for the latest release of this repository once a day. No personal data is sent.
- **Crash logs** stay on the phone; you choose whether to share one.

Questions: open an issue at https://github.com/deezi74/Nano-agent-/issues.
