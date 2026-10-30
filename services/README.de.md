# Services

[English](README.md) · **Deutsch**

Programme, die neben VectolynCloud laufen und über deren API arbeiten. Noch gibt es keins davon; sie entstehen in Etappe 8.

| Ordner | Teil | Nötig |
|---|---|---|
| `dashboard/` | Web-Dashboard. Es verwaltet alles, was die Cloud kann: jede Einstellung und jede Aktion, die Dateien, Konsole und API bieten, auch die Ersteinrichtung. | immer |
| `website/` | Website des Netzwerks, gehostet vom Betreiber. | optional |
| `discord-bot/` | Discord-Bot mit dem eigenen Bot-Token des Betreibers. | optional |
| `store/` | Store mit dem eigenen Stripe-Konto des Betreibers. | optional |

Für jeden optionalen Teil bieten Einrichtungsassistent und Dashboard drei Möglichkeiten: die Vectolyn-Variante, eine eigene oder aus. Die Website steht anfangs auf der Vectolyn-Variante. Discord-Bot und Store sind anfangs aus, weil sie erst ein Token oder ein Konto brauchen.

Ordner und Build-Dateien kommen dazu, sobald die Arbeit am jeweiligen Service beginnt.
