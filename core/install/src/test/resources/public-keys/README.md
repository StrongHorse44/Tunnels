# Publicly known signing certificates (fixtures for `PublicSigningKeysTest`)

Each `PublicSigningKeys` entry must equal the SHA-256 of the DER form of its certificate here.

- The nine `*.x509.pem` files other than `tunnels_debug.x509.pem` are AOSP's public test certificates from
  `build/make/target/product/security/`, copied from the GitHub mirror `aosp-mirror/platform_build` (branch `main`,
  commit 045a3d6a3e359633a14853a5a5e1e4f2a11cbdae) and compared byte for byte with LineageOS `android_build`
  (branch `lineage-22.2`, commit ab5acb1281035ebc9b0f190baf22ef3ea896106e): all nine identical.
- `tunnels_debug.x509.pem` is the certificate of `app/debug.keystore` (CN=Tunnels Debug), exported with
  `keytool -exportcert -rfc -alias androiddebugkey -keystore app/debug.keystore -storepass android`; its SHA-256 is the
  `signing.cert_sha256` pin of `gate/baseline.json`.

Check one by hand: `openssl x509 -in testkey.x509.pem -outform DER | sha256sum`.
