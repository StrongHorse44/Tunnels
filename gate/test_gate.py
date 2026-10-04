"""Unit tests for gate.py (python3 -m unittest discover -s gate). Standard library only."""

import hashlib
import io
import json
import os
import stat
import struct
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout, redirect_stderr
from unittest import mock

import gate

HERE = os.path.dirname(os.path.abspath(__file__))
REAL_MANIFEST = os.path.join(HERE, "testdata", "prikey-0.17.0-AndroidManifest.xml")
REAL_TABLE = os.path.join(HERE, "testdata", "prikey-0.17.0-resources.arsc")
NONE = 0xFFFFFFFF

DEPS = """
> Task :app:dependencies

releaseRuntimeClasspath - Resolved configuration for runtime for variant: release
+--- project :core:model
|    \\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.21 (*)
+--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.2.21} -> 2.2.21 (c)
+--- com.example:declared-only (n)
\\--- org.jetbrains.kotlin:kotlin-stdlib:2.2.0 -> 2.2.21

(c) - A dependency constraint, not a dependency.

> Task :gateFileDependencies
gate-file-dependencies: done :
> Task :app:gateFileDependencies
gate-file-dependency: app/libs/vendor.jar
gate-file-dependencies: done :app
"""


# ---- Builders ------------------------------------------------------------------------------------


def lp(data):
    return struct.pack("<I", len(data)) + data


def string_pool(strings):
    """A UTF-8 ResStringPool chunk."""
    data, offsets = b"", []
    for s in strings:
        offsets.append(len(data))
        raw = s.encode()
        data += bytes([len(s), len(raw)]) + raw + b"\0"
    while len(data) % 4:
        data += b"\0"
    header = 28
    body = b"".join(struct.pack("<I", o) for o in offsets) + data
    return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(strings), 0, 0x100,
                       header + 4 * len(strings), 0) + body


def axml(tree, wrong_ids=None):
    """Compiled XML from (tag, [(android?, name, kind, value)], [children]), laid out like aapt2: Android
    attribute names first in the string pool, matched by the resource map."""
    android, other = [], []

    def collect(node):
        if node[0] == "#text":
            other.append(node[1])
            return
        tag, attrs, children = node
        other.append(tag)
        for is_android, name, kind, value in attrs:
            # is_android None: Android's resource ID without the Android namespace.
            (android if is_android is not False else other).append(name)
            if kind == "str":
                other.append(value)
        for child in children:
            collect(child)

    collect(tree)
    android = list(dict.fromkeys(android))
    strings = android + [s for s in dict.fromkeys([gate.ANDROID_NS] + other) if s not in android]
    idx = {s: i for i, s in enumerate(strings)}
    ids = [(wrong_ids or {}).get(n, gate.ATTR_IDS.get(n, 0x0101FFFF)) for n in android]
    rmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(ids)) + b"".join(struct.pack("<I", i) for i in ids)

    def attr(is_android, name, kind, value):
        ns = idx[gate.ANDROID_NS] if is_android else NONE
        if kind == "str":
            return struct.pack("<IIIHBBI", ns, idx[name], idx[value], 8, 0, 0x03, idx[value])
        dtype, data = {"int": (0x10, value), "bool": (0x12, NONE if value else 0), "ref": (0x01, value)}[kind]
        return struct.pack("<IIIHBBI", ns, idx[name], NONE, 8, 0, dtype, data)

    def element(node):
        if node[0] == "#text":
            return struct.pack("<HHIIIIHBBI", 0x0104, 16, 28, 1, NONE, idx[node[1]], 8, 0, 0x03, 0)
        tag, attrs, children = node
        body = struct.pack("<IIHHHHHH", NONE, idx[tag], 20, 20, len(attrs), 0, 0, 0) + b"".join(attr(*a) for a in attrs)
        out = struct.pack("<HHIII", 0x0102, 16, 16 + len(body), 1, NONE) + body
        for child in children:
            out += element(child)
        return out + struct.pack("<HHIIIII", 0x0103, 16, 24, 1, NONE, NONE, idx[tag])

    body = string_pool(strings) + rmap + element(tree)
    return struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body


def a(name, kind, value):
    return (True, name, kind, value)


def manifest(permissions=(), app_attrs=(), components=(), queries=None, sdk=(31, 36), wrong_ids=None):
    uses_sdk = [a("minSdkVersion", "int", sdk[0])] + ([a("targetSdkVersion", "int", sdk[1])] if sdk[1] else [])
    children = [("uses-sdk", uses_sdk, [])]
    for p in permissions:
        children.append(("uses-permission", [a("name", "str", p[0])] + list(p[1:]), []))
    if queries:
        children.append(("queries", [], queries))
    children.append(("application", [a("allowBackup", "bool", False)] + list(app_attrs), list(components)))
    return axml(("manifest", [(False, "package", "str", "com.example.app")], children), wrong_ids)


def dex(strings=("hello",), types=("Lcom/example/A;",), version=b"035"):
    """A dex file with only the string and type tables filled in."""
    strings = sorted(set(strings) | set(types))
    header = bytearray(0x70)
    header[:8] = b"dex\n" + version + b"\0"
    data, offsets = b"", []
    base = 0x70 + 4 * len(strings) + 4 * len(types)
    for s in strings:
        offsets.append(base + len(data))
        data += bytes([len(s)]) + s.encode() + b"\0"
    struct.pack_into("<IIII", header, 0x38, len(strings), 0x70, len(types), 0x70 + 4 * len(strings))
    return (bytes(header) + b"".join(struct.pack("<I", o) for o in offsets)
            + b"".join(struct.pack("<I", strings.index(t)) for t in types) + data)


def zip_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return out.getvalue()


def write_apk(path, manifest_bytes=None, entries=None, **dex_args):
    files = {"AndroidManifest.xml": manifest_bytes or manifest(), "classes.dex": dex(**dex_args)}
    files.update(entries or {})
    with open(path, "wb") as f:
        f.write(zip_bytes(files))


def with_signing_block(data, cert, block_id=0x7109871A):
    """Inserts an APK Signing Block holding one v2 signer with the given certificate."""
    signed_data = lp(b"") + lp(lp(cert)) + lp(b"")
    signer = lp(signed_data) + lp(b"") + lp(b"")
    value = lp(lp(signer))
    pairs = struct.pack("<QI", len(value) + 4, block_id) + value
    size = len(pairs) + 24
    block = struct.pack("<Q", size) + pairs + struct.pack("<Q", size) + b"APK Sig Block 42"
    eocd = data.rfind(b"PK\x05\x06")
    cd = struct.unpack_from("<I", data, eocd + 16)[0]
    out = bytearray(data[:cd] + block + data[cd:])
    struct.pack_into("<I", out, eocd + len(block) + 16, cd + len(block))
    return bytes(out)


def read_json(path):
    with open(path) as f:
        return json.load(f)


def write_json(path, data):
    with open(path, "w") as f:
        json.dump(data, f)


class Case(unittest.TestCase):
    def setUp(self):
        # Synthetic APKs are not real builds: keep them away from any aapt2 or apksigner here. Tests that
        # need a tool install a fake one with self.tool().
        self.tools = {}
        patcher = mock.patch.object(gate, "sdk_tool", side_effect=lambda name: self.tools.get(name))
        patcher.start()
        self.addCleanup(patcher.stop)
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.deps = self.path("deps.txt")
        with open(self.deps, "w") as f:
            f.write(DEPS)
        self.baseline = self.path("baseline.json")
        write_json(self.baseline, {"schema": 2, "app": "test", "variant": "release",
                                   "dependencies": {"configuration": "releaseRuntimeClasspath"}})

    def path(self, name):
        return os.path.join(self.dir.name, name)

    def tool(self, name, script):
        path = self.path(name)
        with open(path, "w") as f:
            f.write("#!/bin/sh\n" + script)
        os.chmod(path, os.stat(path).st_mode | stat.S_IEXEC)
        self.tools[name] = path

    def run_gate(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = gate.main(list(argv))
        return code, out.getvalue() + err.getvalue()

    def generate(self, apk_path):
        code, output = self.run_gate("generate", "--apk", apk_path, "--deps", self.deps, "--baseline", self.baseline)
        self.assertEqual(code, 0, output)

    def check(self, apk_path, *extra):
        return self.run_gate("check", "--apk", apk_path, "--deps", self.deps, "--baseline", self.baseline, *extra)

    def pin(self, certs):
        data = read_json(self.baseline)
        data["signing"] = {"cert_sha256": certs}
        write_json(self.baseline, data)


# ---- Tests ---------------------------------------------------------------------------------------


class DependenciesTest(unittest.TestCase):
    def test_reads_modules_and_skips_constraints_projects_and_unresolved_declarations(self):
        self.assertEqual(gate.dependency_modules(DEPS, "releaseRuntimeClasspath"), ["org.jetbrains.kotlin:kotlin-stdlib"])

    def test_missing_configuration_is_an_error(self):
        with self.assertRaises(gate.GateError):
            gate.dependency_modules(DEPS, "debugRuntimeClasspath")

    def test_failed_resolution_is_an_error(self):
        with self.assertRaises(gate.GateError):
            gate.dependency_modules("releaseRuntimeClasspath - x\n\\--- com.example:lib:1.0 FAILED\n", "releaseRuntimeClasspath")

    def test_unreadable_line_is_an_error_not_a_skip(self):
        with self.assertRaises(gate.GateError):
            gate.dependency_modules("releaseRuntimeClasspath - x\nsomething odd\n", "releaseRuntimeClasspath")

    def test_file_dependencies_and_their_missing_marker(self):
        self.assertEqual(gate.file_dependencies(DEPS, ":app"), (["app/libs/vendor.jar"], []))
        with self.assertRaises(gate.GateError):
            gate.file_dependencies("releaseRuntimeClasspath - x\n\\--- a:b:1\n", ":app")
        with self.assertRaises(gate.GateError):  # the root reported but the app module didn't
            gate.file_dependencies("gate-file-dependencies: done :\n", ":app")


class HostsTest(unittest.TestCase):
    def test_urls_are_reduced_to_scheme_and_authority(self):
        self.assertEqual(gate.hosts_in("see https://User@Example.COM:8443/path?q=1 now"), {"https://example.com:8443"})

    def test_bare_hosts_and_ipv4(self):
        self.assertEqual(gate.hosts_in("api.example.org or 10.111.0.1 or 1.1.1.1 or 1.200.3.4 or 0.0.0.0"),
                         {"api.example.org", "10.111.0.1", "1.1.1.1", "1.200.3.4", "0.0.0.0"})

    def test_real_hosts_under_package_words_are_kept(self):
        self.assertEqual(gate.hosts_in("android.com android.googleapis.com java.sun.com"),
                         {"android.com", "android.googleapis.com", "java.sun.com"})

    def test_package_names_identifiers_files_oids_and_namespaces_are_not_hosts(self):
        text = ("java.net kotlin.io android.app androidx.core.app kotlin.io.FilesKt Dispatchers.IO libfoo.so "
                "emoji.txt java.home java.vm.info user.home 1.2.3 2.5.4.3 1.3.6.1 API.Example.COM http://schemas.android.com")
        self.assertEqual(gate.hosts_in(text), set())

    def test_non_network_schemes_are_ignored(self):
        self.assertEqual(gate.hosts_in("content://com.example.provider file:///sdcard"), set())


class ChunkTest(unittest.TestCase):
    def test_nested_chunk_with_a_zero_header_is_an_error_not_a_hang(self):
        evil = struct.pack("<HHI", 0x0003, 8, 24) + struct.pack("<HHI", 0x0003, 0, 16) + b"\0" * 8
        with self.assertRaises(gate.GateError):
            gate.string_pools_in(evil)

    def pool_with_offsets(self, data, offsets):
        header = 28
        body = b"".join(struct.pack("<I", o) for o in offsets) + data
        return struct.pack("<HHIIIIII", 0x0001, header, header + len(body), len(offsets), 0, 0x100,
                           header + 4 * len(offsets), 0) + body

    def test_repeated_string_offsets_are_decoded_once(self):
        text = "x" * 1000
        pool = self.pool_with_offsets(bytes([0x83, 0xE8, 0x83, 0xE8]) + text.encode() + b"\0", [0] * 20000)
        strings = gate.string_pool(pool, 0)
        self.assertEqual(len(strings), 20000)
        self.assertEqual(strings[0], text)

    def test_overlapping_string_offsets_hit_the_budget(self):
        # Offsets into one long run of length bytes: each decodes to a long string.
        data = bytes([0x7F, 0x7F]) * 2000 + b"x" * 200
        pool = self.pool_with_offsets(data, list(range(0, 4000, 2)))
        with mock.patch.object(gate, "MAX_DECODED_STRING_BYTES", 100000):
            with self.assertRaises(gate.GateError):
                gate.string_pool(pool, 0)

    def test_dex_with_repeated_and_overlapping_strings(self):
        long_string = "y" * 120
        d = bytearray(dex(strings=[long_string], types=[]))
        count, offset = struct.unpack_from("<II", d, 0x38)
        first = struct.unpack_from("<I", d, offset)[0]
        repeated = bytes(d[:0x70]) + struct.pack("<I", first) * 5000
        repeated = bytearray(repeated + bytes(d[0x70 + 4 * count:]))
        shift = 4 * 5000 - 4 * count
        struct.pack_into("<IIII", repeated, 0x38, 5000, 0x70, 0, 0)
        for_offsets = struct.pack("<I", first + shift) * 5000
        repeated[0x70:0x70 + 4 * 5000] = for_offsets
        strings, _ = gate.dex_tables(bytes(repeated), "classes.dex")
        self.assertEqual(len(strings), 5000)
        self.assertEqual(strings[0], long_string)
        with mock.patch.object(gate, "MAX_DECODED_STRING_BYTES", 100):
            with self.assertRaises(gate.GateError):
                gate.dex_tables(dex(strings=[long_string], types=[]), "classes.dex")

    def test_real_resource_table_resolves_files(self):
        with open(REAL_TABLE, "rb") as f:
            table = f.read()
        self.assertEqual(len(gate.resource_files(table, 0x7F040000)), 1)  # Prikey's data extraction rules
        self.assertTrue(gate.resource_files(table, 0x7F040000)[0].startswith("res/"))
        self.assertEqual(gate.resource_files(table, 0x7F040099), [])


class NetworkSecurityConfigTest(unittest.TestCase):
    def test_trust_anchors_cleartext_domains_and_pins(self):
        xml = axml(("network-security-config", [], [
            ("base-config", [(False, "cleartextTrafficPermitted", "bool", False)], [
                ("trust-anchors", [], [("certificates", [(False, "src", "str", "system")], [])])]),
            ("domain-config", [(False, "cleartextTrafficPermitted", "bool", True)], [
                ("domain", [(False, "includeSubdomains", "bool", True)], [("#text", "lan.example")]),
                ("trust-anchors", [], [("certificates", [(False, "src", "str", "user")], []),
                                       ("certificates", [(False, "src", "ref", 0x7F0A0001)], [])]),
                ("pin-set", [(False, "expiration", "str", "2027-01-01")], [
                    ("pin", [(False, "digest", "str", "SHA-256")], [("#text", "AAAA")])])]),
            ("debug-overrides", [], [("trust-anchors", [], [("certificates", [(False, "src", "str", "user")], [])])]),
        ]))
        facts = gate.nsc_facts(xml, lambda rid: ["res/raw/lan_ca.crt"])
        by_kind = {c["kind"]: c for c in facts}
        self.assertEqual(by_kind["base-config"]["cleartext"], "false")
        self.assertEqual(by_kind["base-config"]["trust_anchors"], ["system"])
        self.assertEqual(by_kind["domain-config"]["cleartext"], "true")
        self.assertEqual(by_kind["domain-config"]["domains"], ["lan.example;includeSubdomains"])
        self.assertEqual(by_kind["domain-config"]["trust_anchors"], ["res/raw/lan_ca.crt", "user"])
        self.assertEqual(by_kind["domain-config"]["pins"], ["AAAA;expiration=2027-01-01"])
        self.assertEqual(by_kind["debug-overrides"]["trust_anchors"], ["user"])


class RealManifestTest(unittest.TestCase):
    def test_prikey_release_manifest(self):
        with open(REAL_MANIFEST, "rb") as f:
            facts = gate.manifest_facts(f.read())  # UTF-16 string pool, written by aapt2
        self.assertEqual(facts["package"], "io.github.stronghorse44.prikey")
        self.assertEqual(facts["sdk"], {"min": 31, "target": 37})
        self.assertFalse(facts["debuggable"])
        self.assertEqual(facts["backup"], {"allow_backup": False, "full_backup_content": "false",
                                           "data_extraction_rules": True, "backup_agent": None})
        self.assertEqual(facts["permissions"], {"requested": [], "declared": []})
        self.assertEqual([(c["type"], c["name"].rsplit(".", 1)[1], c["exported"]) for c in facts["components"]],
                         [("activity", "SettingsActivity", True), ("service", "KeyboardService", True)])
        self.assertEqual(facts["components"][1]["permission"], "android.permission.BIND_INPUT_METHOD")
        self.assertEqual(facts["components"][0]["categories"], ["android.intent.category.LAUNCHER"])


class ManifestTest(Case):
    def facts(self, **kwargs):
        return gate.manifest_facts(manifest(**kwargs))

    def test_components_filters_and_provider_capabilities(self):
        facts = self.facts(components=[
            ("receiver", [a("name", "str", ".Boot")], [("intent-filter", [], [
                ("action", [a("name", "str", "android.intent.action.BOOT_COMPLETED")], [])])]),
            ("activity", [a("name", "str", ".Link"), a("exported", "bool", True)], [("intent-filter", [], [
                ("action", [a("name", "str", "android.intent.action.VIEW")], []),
                ("category", [a("name", "str", "android.intent.category.BROWSABLE")], []),
                ("data", [a("scheme", "str", "https"), a("host", "str", "example.com")], [])])]),
            ("activity", [a("name", "str", ".Inner")], []),
            ("provider", [a("name", "str", ".Files"), a("exported", "bool", False), a("authorities", "str", "com.example.files"),
                          a("grantUriPermissions", "bool", True)], []),
            ("service", [a("name", "str", ".Track"), a("foregroundServiceType", "int", 0x8)], []),
        ])
        by_name = {c["name"].rsplit(".", 1)[1]: c for c in facts["components"]}
        self.assertNotIn("Inner", by_name)  # unexported activities are not recorded
        self.assertTrue(by_name["Boot"]["exported"])  # an intent filter exports by default
        self.assertEqual(by_name["Link"]["categories"], ["android.intent.category.BROWSABLE"])
        self.assertEqual(by_name["Link"]["data"], ["scheme=https;host=example.com"])
        self.assertEqual((by_name["Files"]["grant_uri_permissions"], by_name["Files"]["authorities"]), (True, ["com.example.files"]))
        self.assertEqual(by_name["Track"]["foreground_service_type"], "0x8")

    def test_queries_are_not_components(self):
        facts = self.facts(queries=[
            ("provider", [a("authorities", "str", "com.other.provider")], []),
            ("package", [a("name", "str", "com.other.app")], []),
            ("intent", [], [("action", [a("name", "str", "android.intent.action.SEND")], []),
                            ("data", [a("mimeType", "str", "text/plain")], [])]),
        ])
        self.assertEqual(facts["components"], [])
        self.assertEqual(facts["queries"], ["intent:action:android.intent.action.SEND,data:mimeType=text/plain",
                                            "package:com.other.app", "provider:com.other.provider"])

    def test_permission_flags_and_network_flags(self):
        facts = self.facts(permissions=[("android.permission.BLUETOOTH_SCAN", a("usesPermissionFlags", "int", 0x10000)),
                                        ("android.permission.READ_EXTERNAL_STORAGE", a("maxSdkVersion", "int", 32))],
                           app_attrs=[a("usesCleartextTraffic", "bool", False), a("networkSecurityConfig", "ref", 0x7F100000)])
        self.assertEqual(facts["permissions"]["requested"], [
            "android.permission.BLUETOOTH_SCAN;usesPermissionFlags=neverForLocation",
            "android.permission.READ_EXTERNAL_STORAGE;maxSdkVersion=32"])
        self.assertEqual(facts["network"]["uses_cleartext_traffic"], "false")
        self.assertEqual(facts["network"]["network_security_config"][:2], (0x01, 0x7F100000))  # apk_facts resolves it

    def test_missing_target_sdk_falls_back_to_min_sdk(self):
        facts = self.facts(sdk=(16, None), components=[("provider", [a("name", "str", ".Old"), a("authorities", "str", "x")], [])])
        self.assertTrue(facts["components"][0]["exported"])  # providers were exported by default before API 17

    def test_boolean_given_as_a_resource_reference_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(components=[("activity", [a("name", "str", ".A"), a("exported", "ref", 0x7F050001)], [])])
        with self.assertRaises(gate.GateError):
            self.facts(app_attrs=[a("debuggable", "ref", 0x7F050002)])

    def test_attribute_with_the_wrong_resource_id_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(wrong_ids={"exported": 0x01010999},
                       components=[("activity", [a("name", "str", ".A"), a("exported", "bool", True)], [])])

    def test_attribute_is_read_by_resource_id_whatever_its_namespace(self):
        facts = self.facts(components=[("service", [a("name", "str", ".S"), (None, "exported", "bool", True)], [])])
        self.assertTrue(facts["components"][0]["exported"])

    def test_component_without_a_name_fails_closed(self):
        with self.assertRaises(gate.GateError):
            self.facts(components=[("service", [a("exported", "bool", True)], [])])


class ApkTest(Case):
    def test_same_apk_passes_and_generate_is_deterministic(self):
        write_apk(self.path("a.apk"), strings=["https://b.example.com", "https://a.example.com"])
        data = read_json(self.baseline)
        data["notes"] = [{"field": "hosts", "suffix": "example.com", "note": "test hosts"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        first = read_json(self.baseline)
        self.generate(self.path("a.apk"))
        self.assertEqual(read_json(self.baseline), first)
        self.assertEqual(first["dependencies"]["files"], ["app/libs/vendor.jar"])
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 0, output)

    def test_new_permission_fails_with_a_plus_line(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        write_apk(self.path("b.apk"), manifest(permissions=[("android.permission.INTERNET",)]))
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE + permissions.requested: android.permission.INTERNET", output)

    def test_losing_never_for_location_is_a_change(self):
        write_apk(self.path("a.apk"), manifest(permissions=[("android.permission.BLUETOOTH_SCAN", a("usesPermissionFlags", "int", 0x10000))]))
        self.generate(self.path("a.apk"))
        write_apk(self.path("b.apk"), manifest(permissions=[("android.permission.BLUETOOTH_SCAN",)]))
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE - permissions.requested: android.permission.BLUETOOTH_SCAN;usesPermissionFlags=neverForLocation", output)

    def test_native_code_extra_code_network_type_host_and_debuggable_fail(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        nested = zip_bytes({"classes.dex": dex(types=["Ljava/net/Socket;"]), "x86/tool": b"\x7fELF" + b"\0" * 12})
        write_apk(self.path("b.apk"), manifest(app_attrs=[a("debuggable", "bool", True)]),
                  entries={"lib/arm64-v8a/libx.so": b"\x7fELF", "assets/blob.bin": b"\x7fELF\0\0\0\0",
                           "assets/plugin.jar": nested},
                  strings=["wss://push.example.net/socket"], types=["Ljava/net/Socket;"])
        code, output = self.check(self.path("b.apk"))
        self.assertEqual(code, 1)
        for line in ("GATE + native_libs: lib/arm64-v8a/libx.so",
                     "GATE + native_libs: assets/blob.bin",
                     "GATE + native_libs: assets/plugin.jar!/x86/tool",
                     "GATE + extra_code: assets/plugin.jar",
                     "GATE + extra_code: assets/plugin.jar!/classes.dex",
                     "GATE + network_api: Ljava/net/Socket;",
                     "GATE + hosts: wss://push.example.net",
                     "GATE ~ debuggable: false -> true"):
            self.assertIn(line, output)

    def test_notes_survive_regeneration_and_rules_fill_new_ones(self):
        write_apk(self.path("a.apk"), strings=["https://youtrack.jetbrains.com/issue"])
        data = read_json(self.baseline)
        data["notes"] = [{"field": "hosts", "suffix": "jetbrains.com", "note": "Kotlin stdlib error text"}]
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        self.assertEqual(read_json(self.baseline)["hosts"],
                         [{"value": "https://youtrack.jetbrains.com", "note": "(rule jetbrains.com) Kotlin stdlib error text"}])
        data = read_json(self.baseline)
        data["notes"], data["hosts"][0]["note"] = [], "hand-written"
        write_json(self.baseline, data)
        self.generate(self.path("a.apk"))
        self.assertEqual(read_json(self.baseline)["hosts"][0]["note"], "hand-written")

    def test_catch_all_note_rules_are_refused(self):
        write_apk(self.path("a.apk"))
        for rule in ({"field": "hosts", "suffix": "com", "note": "x"},
                     {"field": "hosts", "suffix": "co.uk", "note": "x"},
                     {"field": "hosts", "suffix": "github.io", "note": "x"},
                     {"field": "network_api", "prefix": "Ljava/net/", "note": "x"},
                     {"field": "network_api", "prefix": "Landroid/net/http/", "note": "x"},
                     {"field": "hosts", "match": ".*", "note": "x"},
                     {"field": "network_api", "prefix": "Ljava/", "note": "x"},
                     {"field": "hosts", "suffix": "example.com", "note": ""}):
            data = read_json(self.baseline)
            data["notes"] = [rule]
            write_json(self.baseline, data)
            code, output = self.run_gate("generate", "--apk", self.path("a.apk"), "--deps", self.deps, "--baseline", self.baseline)
            self.assertEqual(code, 2, (rule, output))

    def test_an_entry_without_a_note_fails_the_check(self):
        write_apk(self.path("a.apk"), strings=["https://api.example.com/v1"])
        self.generate(self.path("a.apk"))
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 1)
        self.assertIn("GATE ~ hosts: https://api.example.com has no note", output)
        data = read_json(self.baseline)
        data["hosts"][0]["note"] = "the update check"
        write_json(self.baseline, data)
        self.assertEqual(self.check(self.path("a.apk"))[0], 0)

    def test_inflating_entry_over_the_cap_is_exit_2(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("AndroidManifest.xml", manifest())
            z.writestr("classes.dex", dex())
            z.writestr("assets/bomb.dex", b"dex\n" + b"\0" * (2 * 1024 * 1024))
        with open(self.path("a.apk"), "wb") as f:
            f.write(out.getvalue())
        self.assertLess(len(out.getvalue()), 64 * 1024)
        with mock.patch.object(gate, "MAX_ENTRY_BYTES", 1024 * 1024):
            code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 2, output)
        self.assertIn("limit", output)

    def test_dex_container_is_exit_2(self):
        write_apk(self.path("a.apk"), version=b"041")
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 2, output)
        self.assertIn("dex container", output)

    def test_malformed_manifest_is_exit_2_not_a_traceback(self):
        write_apk(self.path("a.apk"), manifest()[:200])
        self.generate_code = None
        code, output = self.check(self.path("a.apk"))
        self.assertEqual(code, 2, output)
        self.assertIn("the gate could not run", output)

    def test_missing_apk_is_exit_2(self):
        code, _ = self.check(self.path("missing.apk"))
        self.assertEqual(code, 2)


class ToolsTest(Case):
    BADGING = ("package: name='com.example.app' versionCode='1' versionName='1.0'\\n"
               "sdkVersion:'31'\\ntargetSdkVersion:'36'\\n")

    def aapt2(self, badging, permissions):
        self.tool("aapt2", f'case "$2" in badging) printf "{badging}";; permissions) printf "{permissions}";; esac\n')

    def test_cross_check_reads_real_permission_lines(self):
        write_apk(self.path("a.apk"), manifest(permissions=[("android.permission.INTERNET",),
                                                            ("android.permission.BLUETOOTH_SCAN", a("usesPermissionFlags", "int", 0x10000))]))
        self.aapt2(self.BADGING, "package: com.example.app\\n"
                                 "uses-permission: name='android.permission.INTERNET'\\n"
                                 "uses-permission: name='android.permission.BLUETOOTH_SCAN' usesPermissionFlags='65536'\\n")
        code, output = self.run_gate("inspect", "--apk", self.path("a.apk"), "--require-tools")
        self.assertEqual(code, 0, output)

    def test_aapt2_disagreeing_is_exit_2(self):
        write_apk(self.path("a.apk"))
        self.aapt2(self.BADGING, "package: com.example.app\\nuses-permission: name='android.permission.CAMERA'\\n")
        code, output = self.run_gate("inspect", "--apk", self.path("a.apk"), "--require-tools")
        self.assertEqual(code, 2, output)
        self.assertIn("disagree", output)

    def test_missing_aapt2_with_require_tools_is_exit_2(self):
        write_apk(self.path("a.apk"))
        code, output = self.check(self.path("a.apk"), "--require-tools")
        self.assertEqual(code, 2, output)
        self.assertIn("aapt2 not found", output)

    def test_cert_match_mismatch_null_and_unsigned(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        self.tool("apksigner", 'echo "Signer #1 certificate SHA-256 digest: ' + "AB" * 32 + '"\n')
        cert = ("cert", "--apk", self.path("a.apk"), "--baseline", self.baseline, "--require-pin")
        self.assertEqual(self.run_gate(*cert)[0], 1)  # null pin
        self.pin(["ab" * 32])
        self.assertEqual(self.run_gate(*cert)[0], 0)  # match (case-insensitive)
        self.pin(["cd" * 32])
        code, output = self.run_gate(*cert)
        self.assertEqual(code, 1)  # mismatch
        self.assertIn("is not the pinned", output)
        self.tool("apksigner", 'echo "DOES NOT VERIFY" >&2; exit 1\n')
        code, output = self.run_gate(*cert)
        self.assertEqual(code, 1)  # unsigned
        self.assertIn("Never publish an unsigned APK", output)

    def test_require_pin_fails_check_on_a_null_pin(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        code, output = self.check(self.path("a.apk"), "--require-pin")
        self.assertEqual(code, 1, output)
        self.assertIn("GATE ~ signing.cert_sha256: signing.cert_sha256 is null or empty", output)
        self.pin(["ab" * 32])
        self.assertEqual(self.check(self.path("a.apk"), "--require-pin")[0], 0)

    def test_compare_to_lists_baseline_changes_including_the_pin_and_configuration(self):
        write_apk(self.path("a.apk"))
        self.generate(self.path("a.apk"))
        self.pin(["ab" * 32])
        base = self.path("base.json")
        write_json(base, read_json(self.baseline))
        self.pin(["cd" * 32])
        data = read_json(self.baseline)
        data["scan"]["extra_text_paths"] = ["assets/*.json"]
        write_json(self.baseline, data)
        code, output = self.check(self.path("a.apk"), "--compare-to", base)
        self.assertEqual(code, 0, output)
        self.assertIn("BASELINE + signing: " + "cd" * 32, output)
        self.assertIn("BASELINE - signing: " + "ab" * 32, output)
        self.assertIn("BASELINE + scan.extra_text_paths: assets/*.json", output)
        with mock.patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            code, output = self.check(self.path("a.apk"), "--compare-to", self.path("absent.json"))
        self.assertIn("::warning title=Gate baseline changed in this PR::the base branch has no gate/baseline.json", output)

    def test_fingerprint_reads_the_signing_block(self):
        cert = b"0\x82 a certificate in DER"
        with open(self.path("signed.apk"), "wb") as f:
            f.write(with_signing_block(zip_bytes({"AndroidManifest.xml": manifest()}), cert))
        code, output = self.run_gate("fingerprint", "--apk", self.path("signed.apk"))
        self.assertEqual(code, 0, output)
        self.assertIn(hashlib.sha256(cert).hexdigest(), output)
        with open(self.path("v3.apk"), "wb") as f:
            f.write(with_signing_block(zip_bytes({"AndroidManifest.xml": manifest()}), cert, 0xF05368C0))
        code, output = self.run_gate("fingerprint", "--apk", self.path("v3.apk"))
        self.assertIn("Scheme v3", output)
        self.assertIn(hashlib.sha256(cert).hexdigest(), output)
        write_apk(self.path("unsigned.apk"))
        self.assertEqual(self.run_gate("fingerprint", "--apk", self.path("unsigned.apk"))[0], 2)


if __name__ == "__main__":
    unittest.main()
