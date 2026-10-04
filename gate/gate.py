#!/usr/bin/env python3
"""Release gate: compares what a built APK can do with the checked-in gate/baseline.json.

A change to what the app can do (permissions, components and their intent filters, package queries,
native code, extra code, network APIs, host literals, runtime and file dependencies, SDK levels,
debuggable, backup and network flags, signing certificate) must come with a change to
gate/baseline.json in the same pull request, so the owner sees it before merging.

  gate.py generate    --apk APK --deps LOG [--baseline FILE] [--out FILE] [--require-tools]
  gate.py check       --apk APK --deps LOG [--baseline FILE] [--generated FILE] [--require-tools]
                      [--require-pin] [--compare-to BASE_BASELINE]
  gate.py cert        --apk APK [--baseline FILE] [--require-pin]
  gate.py inspect     --apk APK [--require-tools]
  gate.py fingerprint --apk APK

LOG is Gradle output holding `<module>:dependencies --configuration <c>` and the gateFileDependencies
task from gate/file-dependencies.init.gradle. Exit codes: 0 match, 1 differences (or a signing problem),
2 the check could not run. Contract: specs/gate-baseline.md in the program repo. Standard library only.
"""

import argparse
import fnmatch
import hashlib
import io
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
import zlib

SCHEMA = 2
ANDROID_NS = "http://schemas.android.com/apk/res/android"


class GateError(Exception):
    """The check could not run (exit 2). Never treated as a pass."""


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


# ---- Compiled XML and resource tables ------------------------------------------------------------

XML_MAGIC = b"\x03\x00\x08\x00"
TABLE_MAGIC = b"\x02\x00\x0c\x00"

# Android attribute resource IDs (framework public IDs; checked against real manifests). Attributes are
# identified by these, not by their string-pool names.
ATTR_IDS = {
    "name": 0x01010003, "exported": 0x01010010, "permission": 0x01010006, "readPermission": 0x01010007,
    "writePermission": 0x01010008, "protectionLevel": 0x01010009, "sharedUserId": 0x0101000B,
    "debuggable": 0x0101000F, "authorities": 0x01010018, "grantUriPermissions": 0x0101001B,
    "mimeType": 0x01010026, "scheme": 0x01010027, "host": 0x01010028, "port": 0x01010029,
    "path": 0x0101002A, "pathPrefix": 0x0101002B, "pathPattern": 0x0101002C, "minSdkVersion": 0x0101020C,
    "targetSdkVersion": 0x01010270, "maxSdkVersion": 0x01010271, "backupAgent": 0x0101027F,
    "allowBackup": 0x01010280, "required": 0x0101028E, "fullBackupContent": 0x010104EB,
    "usesCleartextTraffic": 0x010104EC, "networkSecurityConfig": 0x01010527,
    "foregroundServiceType": 0x01010599, "dataExtractionRules": 0x0101063E,
    "usesPermissionFlags": 0x01010644,
}
ID_TO_ATTR = {v: k for k, v in ATTR_IDS.items()}
TYPE_REFERENCE, TYPE_STRING, TYPE_INT_DEC, TYPE_INT_HEX, TYPE_BOOLEAN = 0x01, 0x03, 0x10, 0x11, 0x12


# Decoded string bytes per string pool or dex. Real ones hold a few MB; offset tables that point many
# entries into one long string would otherwise cost time and memory without bound.
MAX_DECODED_STRING_BYTES = 64 * 1024 * 1024


class StringBudget:
    def __init__(self, what):
        self.what, self.used = what, 0

    def spend(self, n):
        self.used += n
        if self.used > MAX_DECODED_STRING_BYTES:
            raise GateError(f"{self.what} decodes to more than {MAX_DECODED_STRING_BYTES} bytes of strings")


def string_pool(b, start):
    """Decodes a ResStringPool chunk. Each distinct offset is decoded once, within a byte budget."""
    header_size, count, flags, strings_start = u16(b, start + 2), u32(b, start + 8), u32(b, start + 16), u32(b, start + 20)
    utf8 = bool(flags & 0x100)
    out, seen, budget = [], {}, StringBudget(f"string pool at offset {start}")
    for i in range(count):
        o = start + strings_start + u32(b, start + header_size + 4 * i)
        if o in seen:
            out.append(seen[o])
            continue
        first = o
        if utf8:
            for _ in range(2):  # UTF-16 length, then UTF-8 byte length
                n = b[o]
                o += 1
                if n & 0x80:
                    n = ((n & 0x7F) << 8) | b[o]
                    o += 1
            budget.spend(n)
            seen[first] = b[o:o + n].decode("utf-8", "replace")
        else:
            n = u16(b, o)
            o += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | u16(b, o)
                o += 2
            budget.spend(2 * n)
            seen[first] = b[o:o + 2 * n].decode("utf-16-le", "replace")
        out.append(seen[first])
    return out


def chunk_header(b, o, end):
    """(type, header size, size) of the chunk at o, which must lie within [o, end)."""
    if o + 8 > end:
        raise GateError(f"chunk at offset {o} runs past its parent")
    ctype, hsize, size = u16(b, o), u16(b, o + 2), u32(b, o + 4)
    if hsize < 8 or size < hsize or o + size > end:
        raise GateError(f"chunk at offset {o} has header size {hsize} and size {size}")
    return ctype, hsize, size


def string_pools_in(b):
    """All string pools in a chunk file (resources.arsc or compiled XML), in file order. Every chunk must
    have a header of at least 8 bytes and fit inside its parent, so the walk always moves forward."""
    pools = []

    def walk(o, end):
        ctype, hsize, size = chunk_header(b, o, end)
        if ctype == 0x0001:
            pools.append(string_pool(b, o))
        elif ctype in (0x0002, 0x0003, 0x0200):  # table, xml, package: children follow the header
            child = o + hsize
            while child + 8 <= o + size:
                child += walk(child, o + size)
        return size

    walk(0, len(b))
    return pools


def axml_elements(b):
    """Yields (parents, tag, attrs) for each start tag of compiled XML. Android attributes are keyed
    "android:<name>" by resource ID; values are (type, data, raw string or None)."""
    if b[:4] != XML_MAGIC:
        raise GateError("AndroidManifest.xml is not compiled XML")
    strings, rmap, parents, o = [], [], [], u16(b, 2)
    while o + 8 <= len(b):
        ctype, hsize, size = u16(b, o), u16(b, o + 2), u32(b, o + 4)
        if size < 8:
            raise GateError(f"compiled XML chunk at {o} has size {size}")
        if ctype == 0x0001:
            strings = string_pool(b, o)
        elif ctype == 0x0180:
            rmap = [u32(b, o + 8 + 4 * i) for i in range((size - 8) // 4)]
        elif ctype == 0x0102:
            body = o + hsize
            tag = strings[u32(b, body + 4)]
            attr_start, attr_size, attr_count = u16(b, body + 8), u16(b, body + 10), u16(b, body + 12)
            attrs = {}
            for i in range(attr_count):
                a = body + attr_start + i * attr_size
                ns, name_index, raw = u32(b, a), u32(b, a + 4), u32(b, a + 8)
                dtype, data = b[a + 15], u32(b, a + 16)
                attrs[attr_key(strings, rmap, ns, name_index, tag)] = (dtype, data, strings[raw] if raw != 0xFFFFFFFF else None)
            yield tuple(parents), tag, attrs
            parents.append(tag)
        elif ctype == 0x0103:
            if not parents:
                raise GateError("compiled XML closes more elements than it opens")
            parents.pop()
        elif ctype == 0x0104:  # character data inside the current element
            yield tuple(parents), "#text", {"#text": (TYPE_STRING, 0, strings[u32(b, o + hsize)])}
        o += size


def attr_key(strings, rmap, ns, name_index, tag):
    """The resource ID decides, whatever the namespace: an attribute carrying android:exported's ID is
    android:exported even without the Android namespace."""
    name = strings[name_index]
    rid = rmap[name_index] if name_index < len(rmap) else None
    android_ns = ns != 0xFFFFFFFF and strings[ns] == ANDROID_NS
    if rid not in ID_TO_ATTR and not android_ns:
        return name
    if rid in ID_TO_ATTR:
        known = ID_TO_ATTR[rid]
        if name and name != known:
            raise GateError(f"<{tag}> attribute named '{name}' has the resource ID of android:{known}")
        return "android:" + known
    if name in ATTR_IDS:
        found = "none" if rid is None else f"0x{rid:08x}"
        raise GateError(f"<{tag}> android:{name} has resource ID {found}, expected 0x{ATTR_IDS[name]:08x}")
    return "android:" + name


def attr(attrs, name):
    return attrs.get("android:" + name)


def attr_text(value):
    dtype, data, raw = value
    if raw is not None:
        return raw
    if dtype == TYPE_BOOLEAN:
        return "true" if data else "false"
    if dtype in (TYPE_INT_DEC, TYPE_INT_HEX):
        return str(data)
    if dtype == TYPE_REFERENCE:
        return "@0x%08x" % data
    return "(type 0x%02x)0x%08x" % (dtype, data)


def attr_bool(attrs, name, default, tag):
    """A literal boolean. Anything else (a resource reference, say) fails closed: the gate can't tell
    what it resolves to on a given device configuration."""
    v = attr(attrs, name)
    if v is None:
        return default
    if v[0] == TYPE_BOOLEAN:
        return bool(v[1])
    if v[0] == TYPE_STRING and v[2] in ("true", "false"):
        return v[2] == "true"
    raise GateError(f"<{tag}> android:{name} is not a literal true or false ({attr_text(v)}); "
                    "make it literal so the gate can read it")


def attr_str(attrs, name):
    v = attr(attrs, name)
    return None if v is None else attr_text(v)


def attr_int(attrs, name, tag):
    v = attr(attrs, name)
    if v is None:
        return None
    if v[0] in (TYPE_INT_DEC, TYPE_INT_HEX):
        return v[1]
    text = attr_text(v)
    if v[0] == TYPE_STRING and re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", text):
        return text  # a preview SDK codename
    if v[0] == TYPE_STRING and text.isdigit():
        return int(text)
    raise GateError(f"<{tag}> android:{name} is not a literal number ({text})")


def required_name(attrs, tag):
    name = attr_str(attrs, "name")
    if not name:
        raise GateError(f"<{tag}> has no android:name")
    return name


# ---- Manifest facts ------------------------------------------------------------------------------

COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")
DATA_KEYS = ("scheme", "host", "port", "path", "pathPrefix", "pathPattern", "mimeType")
NEVER_FOR_LOCATION = 0x10000


def data_text(attrs):
    parts = [f"{k}={attr_str(attrs, k)}" for k in DATA_KEYS if attr(attrs, k) is not None]
    return ";".join(parts)


def permission_flags(flags):
    if flags is None or flags == 0:
        return None
    names = []
    if flags & NEVER_FOR_LOCATION:
        names.append("neverForLocation")
    rest = flags & ~NEVER_FOR_LOCATION
    if rest:
        names.append(f"0x{rest:x}")
    return "|".join(names)


def manifest_facts(manifest):
    package = shared_user_id = None
    sdk = {"min": None, "target": None}
    app = None
    requested, declared, queries, native_libraries = set(), set(), set(), set()
    components, current, query_intents = [], None, []

    for parents, tag, attrs in axml_elements(manifest):
        if tag == "#text":
            continue
        depth = len(parents)
        if depth == 0:
            if tag != "manifest":
                raise GateError(f"root element is <{tag}>, not <manifest>")
            package = attrs.get("package", (0, 0, None))[2]
            shared_user_id = attr_str(attrs, "sharedUserId")
        elif parents == ("manifest",):
            if tag == "uses-sdk":
                sdk = {"min": attr_int(attrs, "minSdkVersion", tag), "target": attr_int(attrs, "targetSdkVersion", tag)}
            elif tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
                item = required_name(attrs, tag)
                max_sdk = attr_int(attrs, "maxSdkVersion", tag)
                flags = permission_flags(attr_int(attrs, "usesPermissionFlags", tag))
                if max_sdk is not None:
                    item += f";maxSdkVersion={max_sdk}"
                if flags:
                    item += f";usesPermissionFlags={flags}"
                requested.add(item)
            elif tag == "permission":
                level = attr_int(attrs, "protectionLevel", tag)
                declared.add(required_name(attrs, tag) + ("" if level is None else f";protectionLevel=0x{level:x}"))
            elif tag == "application":
                app = attrs
        elif parents == ("manifest", "queries"):
            if tag == "package":
                queries.add("package:" + required_name(attrs, tag))
            elif tag == "provider":
                queries.add("provider:" + (attr_str(attrs, "authorities") or ""))
            elif tag == "intent":
                query_intents.append([])
        elif parents == ("manifest", "queries", "intent"):
            if tag == "data":
                query_intents[-1].append("data:" + data_text(attrs))
            else:
                query_intents[-1].append(f"{tag}:{attr_str(attrs, 'name')}")
        elif parents == ("manifest", "application"):
            if tag == "uses-native-library":
                native_libraries.add(f"{required_name(attrs, tag)};required={str(attr_bool(attrs, 'required', True, tag)).lower()}")
            elif tag in COMPONENT_TAGS:
                current = {"type": tag, "attrs": attrs, "filters": 0, "actions": set(), "categories": set(), "data": set()}
                components.append(current)
            else:
                current = None
        elif len(parents) == 3 and parents[:2] == ("manifest", "application") and parents[2] in COMPONENT_TAGS:
            if tag == "intent-filter" and current is not None:
                current["filters"] += 1
        elif len(parents) == 4 and parents[:2] == ("manifest", "application") and parents[3] == "intent-filter":
            if current is not None:
                if tag == "action":
                    current["actions"].add(required_name(attrs, tag))
                elif tag == "category":
                    current["categories"].add(required_name(attrs, tag))
                elif tag == "data":
                    current["data"].add(data_text(attrs))

    queries |= {"intent:" + ",".join(sorted(items)) for items in query_intents}
    if package is None or app is None:
        raise GateError("manifest has no package or no <application>")

    min_sdk = sdk["min"] if isinstance(sdk["min"], int) else 1
    target = sdk["target"]
    effective_target = target if isinstance(target, int) else (10000 if isinstance(target, str) else min_sdk)

    out_components = []
    for c in components:
        attrs, tag = c["attrs"], c["type"]
        name = required_name(attrs, tag)
        if name.startswith("."):
            name = package + name
        default = c["filters"] > 0 if tag != "provider" else effective_target < 17
        exported = attr_bool(attrs, "exported", default, tag)
        if not exported and tag in ("activity", "activity-alias"):
            continue  # an unexported activity adds nothing another app or the system can reach
        fgs = attr_int(attrs, "foregroundServiceType", tag) if tag == "service" else None
        out_components.append({
            "type": tag,
            "name": name,
            "exported": exported,
            "permission": attr_str(attrs, "permission"),
            "read_permission": attr_str(attrs, "readPermission") if tag == "provider" else None,
            "write_permission": attr_str(attrs, "writePermission") if tag == "provider" else None,
            "grant_uri_permissions": attr_bool(attrs, "grantUriPermissions", False, tag) if tag == "provider" else None,
            "authorities": sorted((attr_str(attrs, "authorities") or "").split(";")) if tag == "provider" else [],
            "foreground_service_type": None if fgs is None else f"0x{fgs:x}",
            "actions": sorted(c["actions"]),
            "categories": sorted(c["categories"]),
            "data": sorted(c["data"]),
        })
    out_components.sort(key=lambda c: (c["type"], c["name"]))

    full_backup = attr(app, "fullBackupContent")
    if full_backup is None:
        full_backup_text = "absent"
    elif full_backup[0] == TYPE_REFERENCE:
        full_backup_text = "resource"
    else:
        full_backup_text = "true" if attr_bool(app, "fullBackupContent", False, "application") else "false"
    backup_agent = attr_str(app, "backupAgent")
    if backup_agent and backup_agent.startswith("."):
        backup_agent = package + backup_agent
    cleartext = attr(app, "usesCleartextTraffic")

    return {
        "package": package,
        "shared_user_id": shared_user_id,
        "sdk": {"min": sdk["min"], "target": target},
        "debuggable": attr_bool(app, "debuggable", False, "application"),
        "backup": {
            "allow_backup": attr_bool(app, "allowBackup", True, "application"),
            "full_backup_content": full_backup_text,
            "data_extraction_rules": attr(app, "dataExtractionRules") is not None,
            "backup_agent": backup_agent,
        },
        "network": {
            "uses_cleartext_traffic": "absent" if cleartext is None else
            str(attr_bool(app, "usesCleartextTraffic", False, "application")).lower(),
            # A resource reference here; apk_facts replaces it with the file's content.
            "network_security_config": attr(app, "networkSecurityConfig"),
        },
        "permissions": {"requested": sorted(requested), "declared": sorted(declared)},
        "queries": sorted(queries),
        "uses_native_libraries": sorted(native_libraries),
        "components": out_components,
    }


# ---- Resource files and the network security config ---------------------------------------------


def resource_files(table, rid):
    """Every file path resources.arsc maps resource rid to (one per configuration that has it)."""
    _, hsize, size = chunk_header(table, 0, len(table))
    strings, paths = None, set()
    o = hsize
    while o + 8 <= size:
        ctype, chsize, csize = chunk_header(table, o, size)
        if ctype == 0x0001 and strings is None:
            strings = string_pool(table, o)
        elif ctype == 0x0200 and u32(table, o + 8) == rid >> 24:
            c = o + chsize
            while c + 8 <= o + csize:
                ttype, thsize, tsize = chunk_header(table, c, o + csize)
                if ttype == 0x0201 and table[c + 8] == (rid >> 16) & 0xFF:
                    paths |= type_entry_files(table, c, thsize, tsize, rid & 0xFFFF, strings)
                c += tsize
        o += csize
    return sorted(paths)


def type_entry_files(b, t, hsize, size, index, strings):
    flags, count, start = b[t + 9], u32(b, t + 12), u32(b, t + 16)
    offset = None
    if flags & 0x01:  # sparse: (index, offset / 4) pairs
        for i in range(count):
            if u16(b, t + hsize + 4 * i) == index:
                offset = u16(b, t + hsize + 4 * i + 2) * 4
    elif flags & 0x02:  # 16-bit offsets / 4
        if index < count and u16(b, t + hsize + 2 * index) != 0xFFFF:
            offset = u16(b, t + hsize + 2 * index) * 4
    elif index < count and u32(b, t + hsize + 4 * index) != 0xFFFFFFFF:
        offset = u32(b, t + hsize + 4 * index)
    if offset is None:
        return set()
    e = t + start + offset
    if e + 8 > t + size:
        raise GateError(f"resource entry 0x{index:04x} runs past its type chunk")
    entry_size, entry_flags = u16(b, e), u16(b, e + 2)
    if entry_flags & 0x0008:  # compact entry: value type in the flags' high byte, data after the key
        dtype, data = entry_flags >> 8, u32(b, e + 4)
    elif entry_flags & 0x0001:
        raise GateError(f"resource entry 0x{index:04x} is a bag, not a file")
    else:
        dtype, data = b[e + entry_size + 3], u32(b, e + entry_size + 4)
    if dtype != TYPE_STRING or strings is None:
        raise GateError(f"resource entry 0x{index:04x} is not a file path")
    return {strings[data]}


def plain_bool(attrs, name, default, tag):
    """attr_bool for an attribute outside the Android namespace (the network security config's own)."""
    return attr_bool({"android:" + name: attrs[name]} if name in attrs else {}, name, default, tag)


def nsc_facts(xml, resolve):
    """The trust and cleartext rules of a compiled network security config."""
    configs, stack = [], []

    for parents, tag, attrs in axml_elements(xml):
        depth = len(parents)
        del stack[depth:]
        if tag == "#text":
            if stack and stack[-1][0] in ("domain", "pin"):
                stack[-1][1]["text"] = attrs["#text"][2].strip()
            continue
        node = {}
        if tag in ("base-config", "domain-config", "debug-overrides"):
            node = {"kind": tag, "cleartext": "absent", "domains": [], "trust_anchors": [], "pins": []}
            if "cleartextTrafficPermitted" in attrs:
                node["cleartext"] = str(plain_bool(attrs, "cleartextTrafficPermitted", False, tag)).lower()
            configs.append(node)
        elif tag == "certificates" and stack:
            src = attrs.get("src")
            if src is None:
                raise GateError("<certificates> has no src")
            value = "+".join(resolve(src[1])) if src[0] == TYPE_REFERENCE else attr_text(src)
            if plain_bool(attrs, "overridePins", False, tag):
                value += ";overridePins"
            owner = next(n for t, n in reversed(stack) if t in ("base-config", "domain-config", "debug-overrides"))
            owner["trust_anchors"].append(value)
        elif tag == "domain":
            node = {"include": plain_bool(attrs, "includeSubdomains", False, tag)}
            owner = next(n for t, n in reversed(stack) if t == "domain-config")
            owner["domains"].append(node)
        elif tag == "pin-set":
            node = {"expiration": attr_text(attrs["expiration"]) if "expiration" in attrs else None}
            owner = next(n for t, n in reversed(stack) if t == "domain-config")
            owner["pins"].append(node)
        elif tag == "pin":
            node = {}
            owner = next(n for t, n in reversed(stack) if t == "pin-set")
            owner.setdefault("digests", []).append(node)
        stack.append((tag, node))

    out = []
    for c in configs:
        out.append({
            "kind": c["kind"],
            "cleartext": c["cleartext"],
            "domains": sorted(f"{d.get('text', '')}{';includeSubdomains' if d['include'] else ''}" for d in c["domains"]),
            "trust_anchors": sorted(c["trust_anchors"]),
            "pins": sorted(f"{','.join(sorted(d.get('text', '') for d in p.get('digests', [])))};expiration={p['expiration']}"
                           for p in c["pins"]),
        })
    return sorted(out, key=lambda c: json.dumps(c, sort_keys=True))


# ---- Dex -----------------------------------------------------------------------------------------


def uleb128(b, o):
    result, shift = 0, 0
    while True:
        byte = b[o]
        o += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, o
        shift += 7


def dex_tables(dex, name):
    """Returns (strings, type descriptors) of one dex file."""
    if dex[:4] != b"dex\n":
        raise GateError(f"{name} is not a dex file")
    try:
        version = int(dex[4:7])
    except ValueError:
        raise GateError(f"{name} has an unreadable dex version")
    if version >= 41:
        raise GateError(f"{name} is a dex container (version {version}); gate.py does not read those yet")
    string_count, string_off, type_count, type_off = struct.unpack_from("<IIII", dex, 0x38)
    strings, seen, budget = [], {}, StringBudget(name)
    for i in range(string_count):
        offset = u32(dex, string_off + 4 * i)
        if offset not in seen:  # each distinct offset decoded once, within a byte budget
            _, o = uleb128(dex, offset)
            end = dex.index(b"\0", o)
            budget.spend(end - o)
            seen[offset] = dex[o:end].decode("utf-8", "replace")
        strings.append(seen[offset])
    types = [strings[u32(dex, type_off + 4 * i)] for i in range(type_count)]
    return strings, types


# ---- Scans ---------------------------------------------------------------------------------------

# Types that open or describe network connections. android.net.Uri is a string wrapper, not network.
NETWORK_TYPE = re.compile(
    r"^L(java/net/|javax/net/|java/nio/channels/[A-Za-z]*Socket|android/net/(?!Uri;|Uri\$)|android/webkit/|"
    r"android/app/DownloadManager|org/apache/http/|okhttp3/|com/squareup/okhttp/|retrofit2/|io/ktor/|io/grpc/|"
    r"io/netty/|org/chromium/net/|com/android/volley/)"
)

URL_SCHEMES = (r"https?|wss?|s?ftps?|sftp|ssh|git|rtsps?|rtmps?|mqtts?|smtps?|imaps?|pop3s?|ldaps?|"
               r"xmpps?|sips?|tcp|udp|grpcs?|quic")
URL = re.compile(r"(?i)(?<![a-z0-9+.-])(" + URL_SCHEMES + r")://([^\s/?#\"'<>\\^`{|}()\[\],;]*)")
TLDS = ("com|net|org|io|dev|app|co|me|info|biz|xyz|ai|gov|edu|mil|int|us|uk|eu|de|fr|ca|au|jp|nl|ch|ru|cn|"
        "tv|cc|gg|ly|to|page|cloud|site|online|tech|link|social|onion|arpa|local|lan")
# Lower case only: identifiers like Dispatchers.IO are not hosts.
BARE_HOST = re.compile(
    r"(?<![A-Za-z0-9._%+-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:" + TLDS + r"))(?![A-Za-z0-9-]|\.[A-Za-z0-9])"
)
OCTET = r"(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"
IPV4 = re.compile(r"(?<![0-9.])(" + OCTET + r"(?:\." + OCTET + r"){3})(?![0-9.])")
# Java, Kotlin and Android package and property names that end in a TLD-like label (java.net, kotlin.io,
# androidx.core.app, java.home). Names starting with these words are hosts only when they end in .com or .org
# (android.com, android.googleapis.com, java.sun.com).
PACKAGE_ROOTS = ("java", "javax", "kotlin", "kotlinx", "android", "androidx", "dalvik", "sun", "jdk", "junit")
HOST_LAST_UNDER_PACKAGE_ROOTS = ("com", "org")
IGNORED_URLS = {"http://schemas.android.com"}
# Four-arc ASN.1 object identifiers (2.5.4.3, 1.3.6.1) look like IPv4: an address whose first arc is 0, 1
# or 2 and whose arcs are all below 64 is taken for one and skipped, except the public resolvers and the
# any-address below.
IPV4_ALWAYS = {"0.0.0.0", "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3"}
IPV4_IGNORED = {"127.0.0.1", "255.255.255.255"}


def looks_like_oid(ip):
    arcs = [int(a) for a in ip.split(".")]
    return arcs[0] <= 2 and all(a < 64 for a in arcs)


def hosts_in(text):
    found = set()
    for m in URL.finditer(text):
        authority = m.group(2).rsplit("@", 1)[-1].lower().rstrip(".")
        found.add(m.group(1).lower() + "://" + authority)
    rest = URL.sub(" ", text)
    for m in BARE_HOST.finditer(rest):
        host = m.group(1)
        labels = host.split(".")
        if labels[0] in PACKAGE_ROOTS and labels[-1] not in HOST_LAST_UNDER_PACKAGE_ROOTS:
            continue
        found.add(host)
    for m in IPV4.finditer(rest):
        ip = m.group(1)
        if ip in IPV4_IGNORED or (looks_like_oid(ip) and ip not in IPV4_ALWAYS):
            continue
        found.add(ip)
    return found - IGNORED_URLS


MAX_NESTING = 3
# Entries the gate reads whole (dex, nested archives, compiled XML, resource tables, text assets). Real
# ones are a few MB; the caps stop a decompression bomb from exhausting memory.
MAX_ENTRY_BYTES = 256 * 1024 * 1024
MAX_TOTAL_BYTES = 1024 * 1024 * 1024


class Scan:
    def __init__(self):
        self.native, self.extra_code, self.network, self.texts = set(), set(), set(), set()
        self.desugared = False
        self.read_bytes = 0

    def read(self, z, info, path):
        """Reads one entry whole, within the per-entry and total caps."""
        if info.file_size > MAX_ENTRY_BYTES:
            raise GateError(f"{path} declares {info.file_size} bytes, over the gate's {MAX_ENTRY_BYTES}-byte limit")
        with z.open(info) as f:
            data = f.read(MAX_ENTRY_BYTES + 1)
        if len(data) > MAX_ENTRY_BYTES:
            raise GateError(f"{path} inflates past the gate's {MAX_ENTRY_BYTES}-byte limit")
        self.read_bytes += len(data)
        if self.read_bytes > MAX_TOTAL_BYTES:
            raise GateError(f"the APK's code and resources inflate past the gate's {MAX_TOTAL_BYTES}-byte limit")
        return data


def scan_archive(z, prefix, depth, scan, extra_text_paths):
    """Scans every entry by its first bytes, not its name: dex, ELF, nested archives, compiled XML and
    resource tables are found wherever they are. Returns True if the archive holds code."""
    has_code = False
    for info in sorted(z.infolist(), key=lambda i: i.filename):
        if info.is_dir():
            continue
        name, path = info.filename, prefix + info.filename
        with z.open(info) as f:
            head = f.read(8)
        is_root_dex = not prefix and re.fullmatch(r"classes\d*\.dex", name)
        if head[:4] == b"\x7fELF" or name.endswith(".so") or (not prefix and name.startswith("lib/")):
            scan.native.add(path)
            has_code = True
        if head[:4] == b"dex\n":
            strings, types = dex_tables(scan.read(z, info, path), path)
            scan.texts.update(strings)
            scan.network.update(t for t in types if NETWORK_TYPE.match(t))
            scan.desugared |= any(t.startswith("Lj$/") for t in types)
            has_code = True
            if not is_root_dex:
                scan.extra_code.add(path)
        elif head[:4] == b"PK\x03\x04":
            if depth >= MAX_NESTING:
                raise GateError(f"{path} is nested more than {MAX_NESTING} archives deep")
            try:
                inner = zipfile.ZipFile(io.BytesIO(scan.read(z, info, path)))
            except zipfile.BadZipFile as e:
                raise GateError(f"{path} looks like an archive but can't be read: {e}")
            with inner:
                if scan_archive(inner, path + "!/", depth + 1, scan, ()):
                    scan.extra_code.add(path)
                    has_code = True
        elif head[:4] in (XML_MAGIC, TABLE_MAGIC):
            for pool in string_pools_in(scan.read(z, info, path)):
                scan.texts.update(pool)
        elif name.endswith(".class") or head[:4] == b"\xca\xfe\xba\xbe":
            scan.extra_code.add(path)
            has_code = True
        elif not prefix and any(fnmatch.fnmatchcase(name, p) for p in extra_text_paths):
            scan.texts.add(scan.read(z, info, path).decode("utf-8", "replace"))
    return has_code


def apk_facts(apk_path, extra_text_paths=()):
    try:
        z = zipfile.ZipFile(apk_path)
    except (OSError, zipfile.BadZipFile) as e:
        raise GateError(f"cannot open {apk_path}: {e}")
    with z:
        if "AndroidManifest.xml" not in z.namelist():
            raise GateError(f"{apk_path} has no AndroidManifest.xml")
        scan = Scan()
        manifest = scan.read(z, z.getinfo("AndroidManifest.xml"), "AndroidManifest.xml")
        facts = manifest_facts(manifest)
        nsc = facts["network"]["network_security_config"]
        if nsc is not None:
            if nsc[0] != TYPE_REFERENCE or "resources.arsc" not in z.namelist():
                raise GateError("android:networkSecurityConfig is not a resource reference the gate can resolve")
            table = scan.read(z, z.getinfo("resources.arsc"), "resources.arsc")

            def resolve(rid):
                files = resource_files(table, rid)
                if not files:
                    raise GateError(f"resource 0x{rid:08x} does not resolve to a file")
                return files

            configs = []
            for path in resolve(nsc[1]):
                if path not in z.namelist():
                    raise GateError(f"network security config {path} is missing from the APK")
                configs.extend(nsc_facts(scan.read(z, z.getinfo(path), path), resolve))
            facts["network"]["network_security_config"] = configs
        scan_archive(z, "", 0, scan, extra_text_paths)
    hosts, package = set(), facts["package"]
    for t in scan.texts:
        hosts |= hosts_in(t)
    # The app's own package name, and names under it, are identifiers even when they end like a host.
    hosts = {h for h in hosts if h != package and not h.startswith(package + ".")}
    facts.update({
        "native_libs": sorted(scan.native),
        "extra_code": sorted(scan.extra_code),
        "desugared_library": scan.desugared,
        "network_api": sorted(scan.network),
        "hosts": sorted(hosts),
    })
    return facts


# ---- Gradle output -------------------------------------------------------------------------------

DEP_LINE = re.compile(r"^[| +\\-]*[+\\]--- (.*)$")
MODULE = re.compile(r"^([^\s:]+):([^\s:]+)")


def dependency_modules(text, configuration):
    """group:module of every external module in one configuration of `gradle dependencies` output.

    Any tree line it cannot read is an error, so a dependency is never skipped silently."""
    lines = text.splitlines()
    try:
        start = next(i for i, l in enumerate(lines) if l.startswith(configuration + " - ") or l == configuration)
    except StopIteration:
        raise GateError(f"configuration '{configuration}' not found in the dependencies output")
    modules = set()
    for line in lines[start + 1:]:
        if not line.strip():
            break
        m = DEP_LINE.match(line)
        if not m:
            if line.strip() == "No dependencies":
                continue
            raise GateError(f"unreadable line in {configuration}: {line!r}")
        entry = m.group(1).strip()
        if "FAILED" in entry.split():
            raise GateError(f"unresolved dependency in {configuration}: {entry}")
        if entry.endswith((" (c)", " (n)")) or entry.startswith("project "):
            continue  # constraints and unresolved declarations are not on the classpath; projects are ours
        coordinate = MODULE.match(entry)
        if not coordinate:
            raise GateError(f"unreadable dependency in {configuration}: {entry!r}")
        modules.add(coordinate.group(1) + ":" + coordinate.group(2))
    return sorted(modules)


def file_dependencies(text, project):
    """What the dependencies report can't show, from gate/file-dependencies.init.gradle. The root project
    and the app's project must both have reported."""
    for path in sorted({":", project}):
        if not re.search(r"^gate-file-dependencies: done " + re.escape(path) + r"$", text, re.M):
            raise GateError(f"the Gradle output has no gateFileDependencies result for project '{path}'; run "
                            "Gradle with --init-script gate/file-dependencies.init.gradle -Pgate.configuration=<c> "
                            "gateFileDependencies")
    files = sorted(set(re.findall(r"^gate-file-dependency: (.+)$", text, re.M)))
    desugaring = sorted(set(re.findall(r"^gate-desugaring: (.+)$", text, re.M)))
    return files, desugaring


# ---- SDK tools and the signing block -------------------------------------------------------------


def sdk_tool(name):
    override = os.environ.get(name.upper())
    if override:
        return override
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        return None
    root = os.path.join(sdk, "build-tools")
    if not os.path.isdir(root):
        return None

    def version_key(v):
        return [int(p) if p.isdigit() else -1 for p in re.split(r"[.-]", v)]

    for version in sorted(os.listdir(root), key=version_key, reverse=True):
        path = os.path.join(root, version, name)
        if os.access(path, os.X_OK):
            return path
    return None


def run(cmd):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True)
    except OSError as e:
        raise GateError(f"cannot run {cmd[0]}: {e}")
    return p.returncode, p.stdout, p.stderr


def aapt2_cross_check(apk, facts, required):
    """aapt2 must agree with the decoder on package, permissions, SDK levels and debuggable."""
    aapt2 = sdk_tool("aapt2")
    if aapt2 is None:
        if required:
            raise GateError("aapt2 not found (set ANDROID_HOME); the gate needs it in CI")
        warn("aapt2 not found: the aapt2 cross-check was skipped")
        return
    code, badging, err = run([aapt2, "dump", "badging", apk])
    if code != 0:
        raise GateError(f"aapt2 dump badging failed: {err.strip()}")
    code, perms, err = run([aapt2, "dump", "permissions", apk])
    if code != 0:
        raise GateError(f"aapt2 dump permissions failed: {err.strip()}")
    seen = {
        "package": (re.search(r"^package: name='([^']*)'", badging, re.M) or [None, None])[1],
        "min": (re.search(r"^(?:min)?[sS]dkVersion:'([^']*)'", badging, re.M) or [None, None])[1],
        "target": (re.search(r"^targetSdkVersion:'([^']*)'", badging, re.M) or [None, None])[1],
        "debuggable": bool(re.search(r"^application-debuggable", badging, re.M)),
        "requested": sorted(set(re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']*)'", perms, re.M))),
        "declared": sorted(set(re.findall(r"^permission: (\S+)", perms, re.M))),
    }
    target = facts["sdk"]["target"] if facts["sdk"]["target"] is not None else facts["sdk"]["min"]
    ours = {
        "package": facts["package"],
        "min": str(facts["sdk"]["min"]),
        "target": str(target),
        "debuggable": facts["debuggable"],
        "requested": sorted({p.split(";")[0] for p in facts["permissions"]["requested"]}),
        "declared": sorted({p.split(";")[0] for p in facts["permissions"]["declared"]}),
    }
    wrong = [f"{k}: aapt2 says {seen[k]!r}, decoder says {ours[k]!r}" for k in ours if seen[k] != ours[k]]
    if wrong:
        raise GateError("aapt2 and the manifest decoder disagree, so the gate cannot be trusted:\n  " + "\n  ".join(wrong))


def signing_certs(apk):
    """Verifies the APK with apksigner; returns (sorted certificate SHA-256 list, None) or (None, problem)."""
    apksigner = sdk_tool("apksigner")
    if apksigner is None:
        raise GateError("apksigner not found (set ANDROID_HOME)")
    code, out, err = run([apksigner, "verify", "--print-certs", apk])
    if code != 0:
        return None, (err or out).strip()
    certs = sorted({c.lower() for c in re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", out)})
    if not certs:
        return None, "apksigner printed no certificate digest"
    return certs, None


SIGNATURE_SCHEMES = ((0x1B93AD61, "v3.1"), (0xF05368C0, "v3"), (0x7109871A, "v2"))


def signing_block_certs(path):
    """Certificate SHA-256 of each signer, read from the APK Signing Block without verifying anything.
    For bootstrapping a baseline where apksigner is missing; the release job verifies with apksigner."""
    with open(path, "rb") as f:
        data = f.read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise GateError(f"{path} is not a zip file")
    cd_offset = u32(data, eocd + 16)
    if data[cd_offset - 16:cd_offset] != b"APK Sig Block 42":
        raise GateError(f"{path} has no APK Signing Block (v1-only or unsigned)")
    size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
    o, end, blocks = cd_offset - size - 8 + 8, cd_offset - 24, {}
    while o < end:
        length = struct.unpack_from("<Q", data, o)[0]
        blocks[u32(data, o + 8)] = data[o + 12:o + 8 + length]
        o += 8 + length
    for block_id, scheme in SIGNATURE_SCHEMES:
        block = blocks.get(block_id)
        if block is None:
            continue
        certs, p, signers_end = set(), 4, 4 + u32(block, 0)
        while p < signers_end:
            signer_len = u32(block, p)
            signer = block[p + 4:p + 4 + signer_len]
            signed_data = signer[4:4 + u32(signer, 0)]
            digests_len = u32(signed_data, 0)
            certs_seq = 4 + digests_len
            first_cert = signed_data[certs_seq + 8:certs_seq + 8 + u32(signed_data, certs_seq + 4)]
            certs.add(hashlib.sha256(first_cert).hexdigest())
            p += 4 + signer_len
        return sorted(certs), scheme
    raise GateError(f"{path} has no v2 or v3 signature")


# ---- Baseline ------------------------------------------------------------------------------------

# Compared between the APK and the baseline.
COMPARED = ("package", "shared_user_id", "sdk", "debuggable", "backup", "network", "permissions", "queries",
            "uses_native_libraries", "components", "native_libs", "extra_code", "desugared_library",
            "network_api", "dependencies", "hosts")
# Compared between the base branch's baseline and this one (--compare-to): also the fields no APK built
# in a pull request can be checked against, and the gate's own configuration.
COMPARED_BASELINES = ("schema", "variant") + COMPARED + ("signing", "scan", "notes")
NOTED = ("hosts", "network_api")
PIN = re.compile(r"^[0-9a-f]{64}$")


def load_baseline(path, any_schema=False):
    try:
        with open(path, encoding="utf-8") as f:
            baseline = json.load(f)
    except FileNotFoundError:
        return None
    except (OSError, ValueError) as e:
        raise GateError(f"cannot read {path}: {e}")
    if not isinstance(baseline, dict):
        raise GateError(f"{path} is not a JSON object")
    if not any_schema and baseline.get("schema") != SCHEMA:
        raise GateError(f"{path}: schema {baseline.get('schema')!r}, this gate.py reads schema {SCHEMA}")
    return baseline


HOST_SUFFIX = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9-]{2,63}$")
TYPE_PREFIX = re.compile(r"^L[a-z0-9_]+/[A-Za-z0-9_$]+/")
# Two-label suffixes that many unrelated owners share: a rule on one would note hosts nobody looked at.
SHARED_SECOND_LEVEL = {"co", "com", "net", "org", "ac", "gov", "edu", "ne", "or", "go", "gob", "nic", "mil"}
SHARED_SUFFIXES = {"github.io", "gitlab.io", "pages.dev", "workers.dev", "netlify.app", "vercel.app", "web.app",
                   "firebaseapp.com", "appspot.com", "herokuapp.com", "blogspot.com", "azurewebsites.net",
                   "cloudfront.net", "amazonaws.com", "googleapis.com", "fastly.net", "akamaized.net",
                   "duckdns.org", "ngrok.io", "ngrok-free.app", "onrender.com", "fly.dev", "glitch.me"}
# Framework packages: their network types get a note each, never a rule.
FRAMEWORK_TYPE_ROOTS = ("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lkotlin/")


def shared_suffix(suffix):
    labels = suffix.split(".")
    return suffix in SHARED_SUFFIXES or (len(labels) == 2 and labels[0] in SHARED_SECOND_LEVEL)


def check_rules(rules):
    """Bulk-note rules name a literal domain (two labels or more) or a type package (two segments or
    more), never a pattern: a catch-all rule would note every new host before anyone looked at it."""
    for rule in rules:
        field, note = rule.get("field"), rule.get("note")
        if not note or set(rule) - {"field", "suffix", "prefix", "note"}:
            raise GateError(f"notes rule {rule}: needs a note and only field, suffix or prefix")
        suffix, prefix = rule.get("suffix"), rule.get("prefix")
        if (field == "hosts" and isinstance(suffix, str) and HOST_SUFFIX.match(suffix) and prefix is None
                and not shared_suffix(suffix)):
            continue
        if (field == "network_api" and isinstance(prefix, str) and TYPE_PREFIX.match(prefix) and suffix is None
                and not prefix.startswith(FRAMEWORK_TYPE_ROOTS)):
            continue
        raise GateError(f"notes rule {rule}: hosts rules take a literal domain suffix of two labels or more that "
                        "one owner controls (\"doubleclick.net\", not \"co.uk\" or \"github.io\"), network_api "
                        "rules a library's type prefix of two segments or more (\"Lorg/bouncycastle/\", not a "
                        "java/, javax/, android/, dalvik/ or kotlin/ package)")


def host_of(value):
    return value.split("://", 1)[-1].rsplit(":", 1)[0] if "://" in value else value


def rule_matches(rule, field, value):
    if field == "hosts" and rule.get("field") == "hosts":
        host = host_of(value)
        return host == rule["suffix"] or host.endswith("." + rule["suffix"])
    if field == "network_api" and rule.get("field") == "network_api":
        return value.startswith(rule["prefix"])
    return False


def apply_notes(entries, field, rules):
    """Fills empty notes from the first matching rule, marked so the rule is visible wherever the note is."""
    for entry in entries:
        if entry["note"]:
            continue
        for rule in rules:
            if rule_matches(rule, field, entry["value"]):
                entry["note"] = f"(rule {rule.get('suffix') or rule.get('prefix')}) {rule['note']}"
                break
    return entries


def build(apk, deps_path, old, require_tools):
    old = old or {}
    configuration = (old.get("dependencies") or {}).get("configuration")
    project = (old.get("dependencies") or {}).get("project", ":app")
    if not configuration:
        raise GateError("baseline has no dependencies.configuration; set it (e.g. releaseRuntimeClasspath) first")
    extra = (old.get("scan") or {}).get("extra_text_paths", [])
    rules = old.get("notes", [])
    check_rules(rules)
    facts = apk_facts(apk, extra)
    aapt2_cross_check(apk, facts, require_tools)
    try:
        with open(deps_path, encoding="utf-8", errors="replace") as f:
            deps_text = f.read()
    except OSError as e:
        raise GateError(f"cannot read the Gradle output {deps_path}: {e}")
    files, desugaring = file_dependencies(deps_text, project)
    old_notes = {f: {e["value"]: e.get("note", "") for e in old.get(f, [])} for f in NOTED}

    def noted(field):
        return apply_notes([{"value": v, "note": old_notes[field].get(v, "")} for v in facts[field]], field, rules)

    return {
        "schema": SCHEMA,
        "app": old.get("app", ""),
        "variant": old.get("variant", ""),
        "package": facts["package"],
        "shared_user_id": facts["shared_user_id"],
        "sdk": facts["sdk"],
        "debuggable": facts["debuggable"],
        "backup": facts["backup"],
        "network": facts["network"],
        "permissions": facts["permissions"],
        "queries": facts["queries"],
        "uses_native_libraries": facts["uses_native_libraries"],
        "components": facts["components"],
        "native_libs": facts["native_libs"],
        "extra_code": facts["extra_code"],
        "desugared_library": facts["desugared_library"],
        "network_api": noted("network_api"),
        "dependencies": {
            "project": project,
            "configuration": configuration,
            "modules": dependency_modules(deps_text, configuration),
            "files": files,
            "desugaring": desugaring,
        },
        "hosts": noted("hosts"),
        "signing": {"cert_sha256": (old.get("signing") or {}).get("cert_sha256")},
        "scan": {"extra_text_paths": extra},
        "notes": rules,
    }


def comparable(baseline, key, against_apk):
    value = baseline.get(key)
    if key in NOTED:
        return sorted(e["value"] for e in value or [])
    if key == "dependencies" and against_apk:
        return {k: v for k, v in (value or {}).items() if k not in ("project", "configuration")}
    if key == "signing":
        certs = (value or {}).get("cert_sha256")
        return None if certs is None else sorted(str(c).lower() for c in certs)
    return value


def item_key(value):
    if isinstance(value, dict) and "type" in value and "name" in value:
        return value["type"] + " " + value["name"]
    return value if isinstance(value, str) else json.dumps(value, sort_keys=True)


def differences(old, new, keys=COMPARED, against_apk=True):
    diffs = []
    for key in keys:
        a, b = comparable(old, key, against_apk), comparable(new, key, against_apk)
        if a == b:
            continue
        if isinstance(a, dict) and isinstance(b, dict):
            for sub in sorted(set(a) | set(b)):
                if isinstance(a.get(sub), list) and isinstance(b.get(sub), list):
                    diffs += list_diff(f"{key}.{sub}", a[sub], b[sub])
                elif a.get(sub) != b.get(sub):
                    diffs.append(("~", f"{key}.{sub}", f"{json.dumps(a.get(sub))} -> {json.dumps(b.get(sub))}"))
        elif isinstance(a, list) and isinstance(b, list):
            diffs += list_diff(key, a, b)
        else:
            diffs.append(("~", key, f"{json.dumps(a)} -> {json.dumps(b)}"))
    return diffs


def list_diff(field, old, new):
    olds, news = {item_key(v): v for v in old}, {item_key(v): v for v in new}
    out = []
    for k in sorted(set(olds) | set(news)):
        if k not in olds:
            out.append(("+", field, k))
        elif k not in news:
            out.append(("-", field, k))
        elif olds[k] != news[k]:
            out.append(("~", field, f"{json.dumps(olds[k], sort_keys=True)} -> {json.dumps(news[k], sort_keys=True)}"))
    return out


def pin_problem(baseline):
    """Why signing.cert_sha256 can't serve as a pin, or None if it can."""
    certs = (baseline.get("signing") or {}).get("cert_sha256")
    if not certs:
        return "signing.cert_sha256 is null or empty"
    bad = [c for c in certs if not isinstance(c, str) or not PIN.match(c.lower())]
    if bad:
        return f"signing.cert_sha256 has values that are not SHA-256 hex digests: {bad}"
    return None


def write_json(path, data):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=True)
        f.write("\n")


def in_actions():
    return os.environ.get("GITHUB_ACTIONS") == "true"


def warn(message):
    print(("::warning title=Release gate::" if in_actions() else "gate: WARNING: ") + message, file=sys.stderr)


def error(message):
    print(("::error title=Release gate::" if in_actions() else "gate: ERROR: ") + message, file=sys.stderr)


# ---- Commands ------------------------------------------------------------------------------------


def cmd_generate(args):
    old = load_baseline(args.baseline, any_schema=True)
    if old is None:
        raise GateError(f"{args.baseline} does not exist; copy the seed from the gate spec and set "
                        "app, variant and dependencies.configuration first")
    new = build(args.apk, args.deps, old, args.require_tools)
    write_json(args.out or args.baseline, new)
    diffs = differences(old, new, COMPARED_BASELINES, against_apk=False)
    print(f"gate: wrote {args.out or args.baseline} ({len(diffs)} differences from the previous baseline)")
    for op, field, value in diffs:
        print(f"GATE {op} {field}: {value}")
    if pin_problem(new):
        warn(f"{pin_problem(new)}: set it (gate.py fingerprint on the published APK) before the release job runs")
    return 0


def report_baseline_changes(base_path, head):
    """Lists every field the pull request changes in the baseline, so each gate change shows on the PR.
    Informational: an intended change is allowed; the owner sees it before merging."""
    base = load_baseline(base_path, any_schema=True)
    if base is None:
        message = "the base branch has no gate/baseline.json, so every field in this one is new in this pull request"
        print(f"gate: {message} (looked for {base_path})")
        if in_actions():
            print(f"::warning title=Gate baseline changed in this PR::{message}")
        return
    if base.get("schema") != head.get("schema"):
        print(f"gate: the base baseline is schema {base.get('schema')!r}, this one {head.get('schema')!r}: "
              "review the whole file")
    changes = differences(base, head, COMPARED_BASELINES, against_apk=False)
    if not changes:
        print("gate: this pull request does not change the baseline")
        return
    notes = {(f, e["value"]): e.get("note", "") for f in NOTED for e in head.get(f, [])}
    print(f"gate: this pull request changes the baseline in {len(changes)} places (each is a gate change):")
    for op, field, value in changes:
        if op == "+" and (field, value) in notes:
            value = f"{value} (note: {notes[(field, value)] or 'none'})"
        print(f"BASELINE {op} {field}: {value}")
        if in_actions():
            print(f"::warning title=Gate baseline changed in this PR::{op} {field}: {value}")


def cmd_check(args):
    old = load_baseline(args.baseline)
    if old is None:
        raise GateError(f"{args.baseline} does not exist")
    new = build(args.apk, args.deps, old, args.require_tools)
    if args.generated:
        write_json(args.generated, new)
    if args.compare_to:
        report_baseline_changes(args.compare_to, old)
    pinned = (old.get("signing") or {}).get("cert_sha256")
    print(f"gate: pinned signing certificate: {', '.join(pinned) if pinned else 'none'}")
    diffs = differences(old, new)
    # Every host and network type needs a reason in the baseline before it can pass.
    diffs += [("~", field, f"{e['value']} has no note") for field in NOTED for e in old.get(field, [])
              if not str(e.get("note", "")).strip()]
    problem = pin_problem(old) if args.require_pin else None
    if problem:
        print(f"GATE ~ signing.cert_sha256: {problem}; a release can't be checked without it")
        if in_actions():
            print(f"::error title=Release gate::{problem}")
    if not diffs:
        if problem:
            return 1
        print(f"gate: OK, the APK matches {args.baseline}")
        return 0
    print(f"gate: CHECK FAILED, the APK differs from {args.baseline} in {len(diffs)} places:")
    for op, field, value in diffs:
        print(f"GATE {op} {field}: {value}")
        if in_actions():
            print(f"::error title=Release gate::{op} {field}: {value}")
    print("gate: + is in the APK but not the baseline, - is in the baseline but not the APK, ~ changed.")
    print("gate: if the change is intended, update gate/baseline.json in this pull request "
          "(copy the generated baseline this run printed) and list it under GATE CHANGES.")
    return 1


def cmd_cert(args):
    old = load_baseline(args.baseline)
    if old is None:
        raise GateError(f"{args.baseline} does not exist")
    certs, problem = signing_certs(args.apk)
    if certs is None:
        error(f"{args.apk} is not validly signed ({problem}). Never publish an unsigned APK.")
        return 1
    problem = pin_problem(old)
    if problem and args.require_pin:
        error(f"{problem}, so the signing certificate can't be checked. Do not publish. "
              f"This APK is signed by {', '.join(certs)}.")
        return 1
    if problem:
        warn(f"{problem}, so the signing certificate was NOT compared. "
             f"This APK is signed by {', '.join(certs)}. Pin it in gate/baseline.json.")
        return 0
    pinned = sorted(c.lower() for c in old["signing"]["cert_sha256"])
    if pinned != certs:
        error(f"signing certificate {', '.join(certs)} is not the pinned {', '.join(pinned)}. Do not publish.")
        return 1
    print(f"gate: OK, signed by the pinned certificate {', '.join(certs)}")
    return 0


def cmd_inspect(args):
    facts = apk_facts(args.apk)
    aapt2_cross_check(args.apk, facts, args.require_tools)
    print(json.dumps(facts, indent=2))
    return 0


def cmd_fingerprint(args):
    certs, scheme = signing_block_certs(args.apk)
    print(f"gate: {args.apk}: APK Signature Scheme {scheme}, not verified (apksigner verifies in the release job)")
    for c in certs:
        print(c)
    return 0


MALFORMED = (struct.error, IndexError, ValueError, KeyError, TypeError, UnicodeDecodeError, zipfile.BadZipFile,
             zlib.error, EOFError, OSError, RecursionError, MemoryError, StopIteration)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("generate", "check", "cert", "inspect", "fingerprint"):
        p = sub.add_parser(name)
        p.add_argument("--apk", required=True)
        if name in ("generate", "check", "cert"):
            p.add_argument("--baseline", default="gate/baseline.json")
        if name in ("generate", "check"):
            p.add_argument("--deps", required=True, help="Gradle output with the dependencies report and gateFileDependencies")
        if name in ("generate", "check", "inspect"):
            p.add_argument("--require-tools", action="store_true",
                           help="fail if aapt2 is missing instead of skipping the cross-check (CI sets this)")
        if name in ("check", "cert"):
            p.add_argument("--require-pin", action="store_true",
                           help="fail if signing.cert_sha256 is not set (CI and the release job set this)")
    sub.choices["generate"].add_argument("--out", help="write here instead of over the baseline")
    check = sub.choices["check"]
    check.add_argument("--generated", help="also write the baseline this APK would produce")
    check.add_argument("--compare-to", help="the base branch's baseline: list every field this change alters")
    args = parser.parse_args(argv)
    commands = {"generate": cmd_generate, "check": cmd_check, "cert": cmd_cert, "inspect": cmd_inspect,
                "fingerprint": cmd_fingerprint}
    try:
        return commands[args.command](args)
    except GateError as e:
        error(f"the gate could not run: {e}")
        return 2
    except MALFORMED as e:
        error(f"the gate could not run: malformed input ({type(e).__name__}: {e})")
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
