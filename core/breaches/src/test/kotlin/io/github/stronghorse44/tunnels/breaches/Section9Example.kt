package io.github.stronghorse44.tunnels.breaches

/**
 * The example of specs/B11-linx.md section 9.3, copied byte for byte (`<TAB>` is one tab byte; every line, the last
 * included, ends in one LF). Linx's tests embed the same text.
 */
object Section9Example {
    private const val TEXT = """fieldwork-breaches<TAB>1
source<TAB>hibp-v3-breaches
licence<TAB>CC BY 4.0
attribution<TAB>Test attribution, CC BY 4.0.
fetched<TAB>2026-10-05T23:00:00Z
source-sha256<TAB>0000000000000000000000000000000000000000000000000000000000000000
skipped<TAB>0
count<TAB>3
columns<TAB>name<TAB>title<TAB>domain<TAB>breach_date<TAB>added_date<TAB>pwn_count<TAB>flags<TAB>data_classes
b<TAB>AlphaShop<TAB>Alpha Shop<TAB>alpha.example<TAB>2021-03-01<TAB>2021-06-10<TAB>120000<TAB>V<TAB>Email addresses;Passwords
b<TAB>BetaForum<TAB>Beta Forum<TAB>forum.beta.example<TAB>2019-07-15<TAB>2020-01-02<TAB>5000<TAB>VR<TAB>Email addresses;Usernames
b<TAB>GammaList<TAB>Gamma spam list<TAB><TAB>2017-08-28<TAB>2017-08-30<TAB>700000<TAB>P<TAB>Email addresses
end<TAB>3
"""

    val text: String = TEXT.replace("<TAB>", "\t")
    val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
}
