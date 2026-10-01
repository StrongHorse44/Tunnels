package io.github.stronghorse44.tunnels.dns

/**
 * A bundled subset of the Public Suffix List, enough to collapse hostnames to registrable domains
 * (eTLD+1) under the common country second-levels and the big hosting/CDN suffixes. Hosts under a
 * suffix the subset does not know fall back to the PSL's default rule: the last two labels.
 * Rules follow PSL syntax: plain, `*.` wildcard and `!` exception.
 */
object PublicSuffix {
    private val RULES: List<String> = listOf(
        // United Kingdom
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "ac.uk", "gov.uk", "nhs.uk", "police.uk",
        // Australia, New Zealand
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "asn.au", "id.au",
        "co.nz", "net.nz", "org.nz", "govt.nz", "ac.nz", "school.nz", "geek.nz",
        // Japan, Korea, China, Hong Kong, Taiwan, Singapore
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "gr.jp", "ad.jp", "ed.jp", "lg.jp",
        "co.kr", "ne.kr", "or.kr", "go.kr", "re.kr", "pe.kr", "ac.kr",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "net.hk", "org.hk", "edu.hk", "gov.hk",
        "com.tw", "net.tw", "org.tw", "edu.tw", "gov.tw", "idv.tw",
        "com.sg", "net.sg", "org.sg", "edu.sg", "gov.sg",
        // South and south-east Asia
        "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in", "gov.in", "ac.in", "edu.in", "res.in", "nic.in",
        "co.id", "or.id", "web.id", "ac.id", "go.id", "my.id",
        "com.my", "net.my", "org.my", "edu.my", "gov.my",
        "com.ph", "net.ph", "org.ph",
        "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn",
        "co.th", "or.th", "in.th", "ac.th", "go.th",
        "com.pk", "net.pk", "org.pk", "edu.pk", "gov.pk",
        "com.bd", "net.bd", "org.bd",
        // Middle East and Africa
        "co.il", "org.il", "ac.il", "gov.il", "net.il", "muni.il",
        "com.tr", "net.tr", "org.tr", "gov.tr", "edu.tr", "web.tr",
        "com.sa", "net.sa", "org.sa", "edu.sa", "gov.sa",
        "com.eg", "net.eg", "org.eg",
        "co.za", "org.za", "net.za", "gov.za", "ac.za", "web.za",
        "com.ng", "net.ng", "org.ng", "edu.ng", "gov.ng",
        "co.ke", "or.ke", "ne.ke", "go.ke", "ac.ke",
        // Americas
        "com.br", "net.br", "org.br", "gov.br", "edu.br", "art.br", "blog.br",
        "com.mx", "org.mx", "net.mx", "gob.mx", "edu.mx",
        "com.ar", "net.ar", "org.ar", "gob.ar", "edu.ar",
        "com.co", "net.co", "org.co", "edu.co", "gov.co",
        "com.pe", "net.pe", "org.pe", "edu.pe", "gob.pe",
        "com.ve", "net.ve", "org.ve",
        "com.ec", "net.ec", "org.ec",
        "com.uy", "net.uy", "org.uy",
        "gc.ca",
        // Europe
        "com.ua", "net.ua", "org.ua", "gov.ua", "edu.ua", "in.ua", "kiev.ua",
        "com.pl", "net.pl", "org.pl", "edu.pl", "gov.pl", "waw.pl",
        "com.es", "nom.es", "org.es", "gob.es", "edu.es",
        "com.pt", "edu.pt", "gov.pt", "org.pt",
        "com.gr", "edu.gr", "net.gr", "org.gr", "gov.gr",
        "co.at", "or.at", "ac.at", "gv.at",
        "asso.fr", "gouv.fr", "nom.fr", "com.fr",
        "edu.it", "gov.it",
        "msk.ru", "spb.ru",
        // Cook Islands: the PSL's textbook wildcard plus exception.
        "*.ck", "!www.ck",

        // Hosting, serverless and CDN suffixes (PSL private section): a hostname under one of these
        // belongs to the tenant, not the provider, so the tenant label is part of the registrable domain.
        "s3.amazonaws.com", "*.compute.amazonaws.com", "*.compute-1.amazonaws.com", "elb.amazonaws.com", "*.elb.amazonaws.com",
        "cloudfront.net", "awsglobalaccelerator.com", "amplifyapp.com",
        "azurewebsites.net", "cloudapp.net", "cloudapp.azure.com", "azurestaticapps.net", "blob.core.windows.net",
        "azureedge.net", "trafficmanager.net", "azure-api.net", "azurecontainer.io", "azurefd.net",
        "appspot.com", "firebaseapp.com", "web.app", "run.app", "withgoogle.com", "cloudfunctions.net",
        "github.io", "githubusercontent.com", "gitlab.io", "bitbucket.io",
        "pages.dev", "workers.dev", "r2.dev", "cloudflare-ipfs.com",
        "netlify.app", "vercel.app", "now.sh", "herokuapp.com", "herokussl.com", "fly.dev", "onrender.com",
        "glitch.me", "surge.sh", "readthedocs.io", "ngrok.io", "ngrok.app", "ngrok-free.app",
        "a.ssl.fastly.net", "b.ssl.fastly.net", "global.ssl.fastly.net", "a.prod.fastly.net", "global.prod.fastly.net", "fastlylb.net",
        "linodeusercontent.com", "digitaloceanspaces.com", "ondigitalocean.app", "oraclecloudapps.com",
        "wixsite.com", "blogspot.com", "wordpress.com", "myshopify.com", "webflow.io", "bubbleapps.io", "carrd.co", "notion.site",
        "pythonanywhere.com", "repl.co", "replit.app", "stackblitz.io", "codesandbox.io",
        "duckdns.org", "hopto.org", "zapto.org", "ddns.net", "dynu.net", "freeddns.org", "no-ip.org", "dyndns.org",
        "nip.io", "sslip.io", "xip.io",
    )

    private val exact: Set<String> = RULES.filter { !it.startsWith("*.") && !it.startsWith("!") }.toSet()
    /** The part after `*.` of each wildcard rule. */
    private val wildcard: Set<String> = RULES.filter { it.startsWith("*.") }.map { it.removePrefix("*.") }.toSet()
    private val exceptions: Set<String> = RULES.filter { it.startsWith("!") }.map { it.removePrefix("!") }.toSet()

    /** Number of rules bundled, for the tests. */
    val ruleCount: Int get() = RULES.size

    private val ipv4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * The registrable domain (eTLD+1) of [host], lowercased and without a trailing dot. IP literals,
     * single-label names and hosts that are themselves a public suffix come back unchanged.
     */
    fun registrableDomain(host: String): String {
        val h = normalize(host)
        if (h.isEmpty() || isIpLiteral(h)) return h
        val labels = h.split('.')
        if (labels.size <= 1) return h
        val suffix = publicSuffixLength(labels)
        if (suffix >= labels.size) return h
        return labels.subList(labels.size - suffix - 1, labels.size).joinToString(".")
    }

    /** True when [host] is an IPv4 dotted quad or contains a colon (IPv6). */
    fun isIpLiteral(host: String): Boolean = host.contains(':') || ipv4.matches(host)

    fun normalize(host: String): String = host.trim().trimEnd('.').lowercase()

    /** How many trailing labels of [labels] form the public suffix. The default rule `*` makes it at least 1. */
    private fun publicSuffixLength(labels: List<String>): Int {
        var best = 1
        for (i in labels.indices) {
            val len = labels.size - i
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in exceptions) return len - 1
            if (candidate in exact && len > best) best = len
            if (i + 1 < labels.size && labels.subList(i + 1, labels.size).joinToString(".") in wildcard && len > best) best = len
        }
        return best
    }
}
