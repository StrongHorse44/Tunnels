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

What was confirmed (from RJ's browser, 2026-10-06; the program repo's `facts/hibp.md`): the all-breaches endpoint needs no
API key and no sign-in; every request needs a user agent (a missing one is answered with HTTP 403); the answer is a JSON array
of objects with the fields `HibpCatalogue` reads (and a few more, which it ignores); the data class `Passwords` exists; and the
licence is CC BY 4.0 with attribution that names Have I Been Pwned as the source. The licence asks for a link to the site;
Tunnels shows the site's name in text, which the licence's "doesn't have to be overt" wording allows. **Not measured:** the
size of the answer, its `Content-Type` value, whether it ever redirects, and the rate limit of the unauthenticated endpoint
(the client's limits and refusals are set from the spec, not from a measurement).

## Where things are

| Piece | Where |
|---|---|
| Source, JSON reader, HIBP reduction, file writer and reader, holder, address check | `core/breaches` (plain Kotlin, JVM tests) |
| HTTP client, screen, share provider, send intent | `tunnels/breaches` |
| Entry | "breaches ›" on the home console (`WellHome`, `MainActivity`) |
| File format `fieldwork-breaches 1` | `specs/B11-linx.md` section 9.2; example 9.3 is embedded in `core/breaches` tests |
