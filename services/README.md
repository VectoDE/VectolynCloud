# Services

**English** · [Deutsch](README.de.md)

Programs that run next to VectolynCloud and work through its API. None of them exists yet; they are built in stage 8.

| Folder | Part | Needed |
|---|---|---|
| `dashboard/` | Web dashboard. It manages everything the cloud can do: every setting and every action that the files, the console and the API offer, the first-time setup included. | always |
| `website/` | Website of the network, hosted by the operator. | optional |
| `discord-bot/` | Discord bot, runs with the operator's own bot token. | optional |
| `store/` | Store, runs with the operator's own Stripe account. | optional |

For each optional part the setup wizard and the dashboard offer three choices: the Vectolyn version, your own, or off. The website starts on the Vectolyn version. The Discord bot and the store start switched off, because they need a token or an account first.

A folder and its build files are added when work on that service starts.
