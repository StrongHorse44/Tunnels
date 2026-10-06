# Breach list for Linx (B11-T)

Tunnels' fourth network module. `tunnels/breaches` downloads the public Have I Been Pwned breach list when you tap
**Fetch**, holds it in memory and hands it to Linx (the Tunnels family's account-safety app) on the same phone. Linx has no
network permission, so this is how it gets the list. The file format, the matching rules and the hand-off are a contract
shared with Linx: the spec is `specs/B11-linx.md` sections 9 and 10 in the program repo (`fieldwork`), and this file points
at it rather than restating it.

## What it does and does not do

- **One request, on a tap.** Home console, "breaches ›", then Fetch. `GET https://haveibeenpwned.com/api/v3/breaches` with a
  `User-Agent: Tunnels-breaches` header and nothing else of ours: no key, no account, no address, no domain. The site sees the
  phone's IP address, once. Redirects are refused, only a 200 answer with a JSON content type is read, at most 16 MiB, with
  15 s and 30 s timeouts. The Network permission must be on for this; turn it off afterwards.
- **Reduced, not copied.** Descriptions, logos and every other field are dropped. What is kept per breach: name, title,
  domain, breach date, date added, count of accounts, flags and data class names (`core/breaches`, `HibpCatalogue`). An
  entry that cannot be represented is skipped and counted, never repaired.
- **Held in memory only.** The reduced file lives in `BreachHolder` for ten minutes or until it has been served three times,
  then its bytes are zeroed. Nothing is written to storage (rules 2, 3 and 5 are untouched). The only thing stored is one
  line in the encrypted settings, `breaches.last_fetch` = `<time>;count=<n>`; it is not exported.
- **Handed over without a file.** **Send to Linx** starts an `ACTION_SEND` to Linx's package alone (never a chooser) with a
  one-address read grant. `BreachShareProvider` (not exported, `grantUriPermissions`) serves exactly
  `content://<applicationId>.breaches/catalogue/<128-bit token>`, a new token per fetch, through a reliable pipe, as
  `application/vnd.fieldwork.breaches`. Any other address, mode or token is a `FileNotFoundException`. If Tunnels' process dies
  first, Linx's read fails and Linx says "Send it again from Tunnels". Linx must be installed in the same profile; the
  button says so when it is not.

## Licence and attribution

Have I Been Pwned's breach data is offered under Creative Commons Attribution 4.0 International (RJ accepted it on 2026-10-06). The attribution travels in the file's header and is shown on this screen and in Linx:

> Breach data from Have I Been Pwned by Troy Hunt (haveibeenpwned.com), CC BY 4.0. Reduced by Tunnels to names, titles, domains, dates, counts, flags and data classes.

The facts about the source (that the endpoint needs no key, answers a JSON array with the fields `HibpCatalogue` reads, and
the licence wording) were written from the spec and not from the live service when this module was built; see the pull
request that added it for what was and was not confirmed.

## Where things are

| Piece | Where |
|---|---|
| Source, JSON reader, HIBP reduction, file writer and reader, holder, address check | `core/breaches` (plain Kotlin, JVM tests) |
| HTTP client, screen, share provider, send intent | `tunnels/breaches` |
| Entry | "breaches ›" on the home console (`WellHome`, `MainActivity`) |
| File format `fieldwork-breaches 1` | `specs/B11-linx.md` section 9.2; example 9.3 is embedded in `core/breaches` tests |
