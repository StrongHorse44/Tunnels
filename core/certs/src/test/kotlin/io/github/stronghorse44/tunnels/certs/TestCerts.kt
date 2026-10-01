package io.github.stronghorse44.tunnels.certs

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Throwaway certificates generated with openssl for these tests; the private keys were discarded. */
object TestCerts {
    /** RSA 2048 self-signed CA, CN=Tunnels Test Root, O=Tunnels Project, C=NL, valid 2026-10-01 to 2036-09-28. */
    const val ROOT_FINGERPRINT = "E2:71:77:3B:65:BE:8B:FF:EB:D1:50:3F:98:67:89:CC:26:C7:27:98:84:64:BE:A4:F6:D3:FA:8D:42:75:C2:65"
    val root: X509Certificate by lazy {
        parse(
            """
            -----BEGIN CERTIFICATE-----
            MIIDdzCCAl+gAwIBAgIUQ9/4lmsEwMKC13G+twupshLjJTowDQYJKoZIhvcNAQEL
            BQAwQzEaMBgGA1UEAwwRVHVubmVscyBUZXN0IFJvb3QxGDAWBgNVBAoMD1R1bm5l
            bHMgUHJvamVjdDELMAkGA1UEBhMCTkwwHhcNMjYxMDAxMDc1NzM2WhcNMzYwOTI4
            MDc1NzM2WjBDMRowGAYDVQQDDBFUdW5uZWxzIFRlc3QgUm9vdDEYMBYGA1UECgwP
            VHVubmVscyBQcm9qZWN0MQswCQYDVQQGEwJOTDCCASIwDQYJKoZIhvcNAQEBBQAD
            ggEPADCCAQoCggEBAJsc1zWO+kj6fIzriWU3KXu5NDX5wCTmDHKL19RcgAbojfUB
            RHQi9w9Ut2u6hygkAO1N5u4u/YheSyajLXigqi7ywtNiVshVisR8rKW8NzAHQN+I
            ME3yZWF6XOMeVkxmv6hYlKiHBjQTt1aEQYlNHHQMySY8OGBeYmrjkczI+gG5TDms
            J5+uwiWjCVMUV+T21SLtCkFTPlYeJQMwwc53BSCi/nbhlgC8wHlFHus44RTCiTwE
            cFmcNw9Wu+kCwJ/XHUwiYUByXbUomWkmYcs7AzwE9W4EkxQfjBhYzRoCr+M6pzgy
            Igby9WIAy/wwc4QgxWHax3bXXZwOr/KJE2cgHa8CAwEAAaNjMGEwHQYDVR0OBBYE
            FFvUSpp3ZPG2Xrm4JHgQsqX6Dl0wMB8GA1UdIwQYMBaAFFvUSpp3ZPG2Xrm4JHgQ
            sqX6Dl0wMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/BAQDAgEGMA0GCSqGSIb3
            DQEBCwUAA4IBAQBI5nWo+vp20WwMbKAARGvLuS57wxA+mR0A7t5hU3jFZTAYPwX6
            w/z+uQRffocg93lv+dFZBWUY9TPCv3EQIa2FELFaT2Tiea94B1a6v7DMOUqJ8iBp
            zKgW5BjfEWRTfRoQBYL7aXkWo8q85VXifAmT13AA/LEHpKAuW7gZzL38ov/kIrhR
            AZqgav6yHnrjaReLhkSjSl46mf39VwmPMWqzsH5APHK3MCybZSPFr08zB0Iu6Ula
            KRb1Z1dP5tRi8ycHoGS3VkLeuNGdiUVyUemcxiUiQ7KVbxEwXW4yHpbwgbpONtEb
            832hA3Xb+1/Zui9KGUVjo8p61e4gfgakxJ0+
            -----END CERTIFICATE-----
            """,
        )
    }

    /** EC P-256 leaf signed by [root], CN=leaf.example.test, O=Leaf Org, not a CA, valid 2026-10-01 to 2027-10-01. */
    const val LEAF_FINGERPRINT = "28:01:65:30:98:E9:B9:FA:BE:04:7C:D0:FE:08:2F:40:63:88:1A:02:D3:0D:CE:88:7E:40:03:87:86:8C:4A:34"
    val leaf: X509Certificate by lazy {
        parse(
            """
            -----BEGIN CERTIFICATE-----
            MIICjzCCAXegAwIBAgIULEZYc1SkfCqLEy+4lo3TpbVt9R4wDQYJKoZIhvcNAQEL
            BQAwQzEaMBgGA1UEAwwRVHVubmVscyBUZXN0IFJvb3QxGDAWBgNVBAoMD1R1bm5l
            bHMgUHJvamVjdDELMAkGA1UEBhMCTkwwHhcNMjYxMDAxMDc1NzM2WhcNMjcxMDAx
            MDc1NzM2WjAvMRowGAYDVQQDDBFsZWFmLmV4YW1wbGUudGVzdDERMA8GA1UECgwI
            TGVhZiBPcmcwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARxI4cZDaZAqEA/iTi8
            n/c1cgHk8icV4OEim2sNpo1MPo6hNBl/B8nrigzooRuLG00buh2kVeSHfw+373rf
            b9Uko1owWDAJBgNVHRMEAjAAMAsGA1UdDwQEAwIHgDAdBgNVHQ4EFgQURpmkDbrm
            1vLQDBosP5UJ7d9DdFkwHwYDVR0jBBgwFoAUW9RKmndk8bZeubgkeBCypfoOXTAw
            DQYJKoZIhvcNAQELBQADggEBAAW1wUELG4Yhkj36OwWTjna29j9y943PjFDN0GIU
            e42jrCMfOEZNNmins3EeEistYMFkC7iN7s0TJSRJXTfny2mqUYNEUmkxThZ9X3Bn
            F8stLn/uaFtK9b5fgh4HY/qgKxkDChfoxsIkgrbtdhWjmx7DV/ymImQCpSsIVCnO
            dQ9zJ1hnr27xyLuXlfYTaP7jjuvxweSirSUKiRYXGa1GMknSo0KXIiscaSf05BJJ
            ewrP6tVSNmVyPj4akUIBfM2YSFsL/nCJy+MWK8Yywb9YxMX/AEAfF/mQM/0VHOgZ
            X50TzxdDadaty3jDG7LXIu1oZWLTrDeFy3BZSi3f0FoE5Gk=
            -----END CERTIFICATE-----
            """,
        )
    }

    private fun parse(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.trimIndent().byteInputStream()) as X509Certificate
}
