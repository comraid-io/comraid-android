# Push payload contract

Machine-readable Reserved-key contract and golden Push payloads for ComRaid Notifications.

This folder is the single source of truth for the `cr_*` keys a Push payload may carry (Presentation hints and Attribution keys), plus worked-example goldens that record today's wire behaviour. Product code does not change to match this folder; the folder records what the server already writes and what the SDKs already parse.

## Vendoring (ADR 0017)

The contract lives in private `planning`. `server`, `comraid-android`, `comraid-ios`, `comraid-react-native`, and `docs` each commit a byte-for-byte copy of the files they need and test against that copy in their own CI. Each repo has a `sync` script that refreshes the copy from a sibling `planning` checkout (or an env-var path). CI never checks out `planning`.

`sync --check` compares the committed copy with the sibling checkout and fails on any difference. Run it locally when shipping a contract change. The contract's integer `version` makes a stale copy easy to spot.

See [ADR 0017](../../agent-docs/adr/0017-push-payload-contract-vendored-from-planning.md).

## Layout

| Path | Role |
|------|------|
| `contract.json` | Versioned Reserved-key list: kind, meaning, encoding, and presence per FCM data / APNs alert / APNs data-only |
| `goldens/` | One JSON file per worked example: `content`, `pushPayload`, `expectedEvent` |
| `README.md` | This file (folder purpose, vendoring, checklist) |

Do not restate the key list here — edit `contract.json`.

## Add or change a Reserved key

1. Edit `contract.json` and bump `version`.
2. Add or update goldens under `goldens/` so they still match wire behaviour (or intentionally document a behaviour change landed in the same change set).
3. In `server`, `comraid-android`, `comraid-ios`, `comraid-react-native`, and `docs`: run `sync`, then fix whatever fails (writers, parsers, reserved-set equality, docs table).
4. Run `sync --check` in every repo.
5. Open one draft → main PR per repo, as usual.
