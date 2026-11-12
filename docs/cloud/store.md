# Store: design for Stripe and Mollie

**Status: design, not built.** The website already has the shop pages ([`site/src/pages.mjs`](../../site/src/pages.mjs), page `shop`) in test mode. This document describes the server part that will sit behind them (planned module `services/store`). Live payments stay switched off until the owner approves them; see [docs/legal/zahlungsanbieter.md](../legal/zahlungsanbieter.md) for fees, terms and the checklist before going live.

## Goals

- Sell cosmetic items only: ranks, cosmetics, time-based purchases, digital products without a link to the game. Nothing that gives an advantage in the game (Minecraft EULA and the commercial usage guidelines; to be checked by a lawyer, see [legal README](../legal/README.md), question 17).
- Two providers, one flow: Stripe Checkout and Mollie Payments. The operator picks one or both in `store.yml`.
- Test mode works without any secret in the repository. Live mode needs an explicit switch and keys that only live in `secrets/vectolyn.env`.
- Every purchase is delivered in the game exactly once, also when the player is offline, and is taken back on a refund or chargeback.

## Flow

```
Player ── shop page ──► POST /store/checkout {item, minecraftName}
                          │ checks the name (Mojang API or Floodgate for Bedrock), the item, the price
                          │ creates an order (state "open") with an idempotency key
                          ├─► Stripe: Checkout Session (mode=payment, price from the catalogue)
                          └─► Mollie: Payment (amount, description, redirectUrl, webhookUrl)
                       ◄── redirect to the provider's hosted page (card data never touches our server)
Provider ── webhook ──► POST /store/webhook/{stripe|mollie}
                          │ Stripe: verify the Stripe-Signature header with the endpoint secret
                          │ Mollie: no signature; fetch the payment by id from the Mollie API and trust only that answer
                          │ order "paid" (once; duplicate events are ignored by event id)
                          └─► cloud channel: DeliverPurchase{orderId, uuid, item, expiresAt}
Vectolyn plugin ────────► grants the item, acknowledges with the order id; retried until acknowledged
```

Refunds and chargebacks (`charge.refunded`, `charge.dispute.created` on Stripe; status `refunded` or `charged_back` on Mollie) set the order to `refunded` and send `RevokePurchase`. Time-based purchases carry `expiresAt`; the plugin removes them at that moment, no renewal.

## Orders

| Field | Meaning |
|---|---|
| `id` | ULID, also the idempotency key towards the provider |
| `provider` | `stripe` or `mollie` |
| `providerRef` | Checkout Session id or Mollie payment id |
| `uuid`, `name` | player; Bedrock players through their Floodgate UUID |
| `item`, `price`, `currency`, `vat` | snapshot at the time of the order; prices are gross (PAngV) |
| `state` | `open`, `paid`, `delivered`, `refunded`, `failed`, `expired` |
| `events` | processed webhook event ids, so a repeated webhook does nothing |

Stored in the cloud database (MariaDB, MySQL or PostgreSQL), table `store_orders`. Personal data: Minecraft name and UUID, order data, the provider reference. No card or bank data. Retention follows tax law (to be confirmed by the tax adviser).

## Test mode and live mode

- `store.mode: test` is the default. The server only accepts keys that start with `sk_test_` (Stripe) or `test_` (Mollie) and refuses to start with a live key.
- `store.mode: live` additionally requires `store.live-approved: true`, set by the operator. Without it the server stays in test mode and logs why.
- Webhook secrets and API keys come from `secrets/vectolyn.env` (`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `MOLLIE_API_KEY`). Never in `store.yml`, never in the repository.
- The website shows a "test mode" notice as long as `shop.liveApproved` in `site/site.config.json` is false.

## Payment methods

Planned, all through the providers' hosted pages: credit and debit card, SEPA Direct Debit, PayPal, instant bank transfer and similar bank methods, Apple Pay, Google Pay. Which of them a provider offers depends on the operator's account and country.

## Customer account and invoices

- Account with e-mail and password (Argon2id, same rules as the panel accounts), e-mail confirmation, password reset. Shows orders and invoices.
- Invoice as PDF after payment, with a running number per operator. How VAT is shown (rate, OSS, small-business rule) is open and has to come from the tax adviser before the store goes live.

## Checkout rules (German law, to be checked by a lawyer)

- Order summary with item, duration, final price including VAT, and the button "zahlungspflichtig bestellen" (§ 312j BGB).
- Withdrawal policy for digital content and the consent to start delivery before the withdrawal period ends (§ 356 Abs. 5 BGB).
- Terms, privacy policy and imprint linked on the summary page.

## Open before building

- [ ] Owner: which provider first, or both.
- [ ] Lawyer: terms, withdrawal policy, EULA check of the item list.
- [ ] Tax adviser: VAT on invoices, OSS, numbering rules.
- [ ] Owner: approve live mode; until then the store runs in test mode only.
