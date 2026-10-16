# Sealed decoder test fixtures

Used by `DecoderClientTest` (and by RJ's phone step 7, which downloads the two truncated files from GitHub and shares
them to Tunnels Convert). They are test data only and are not in the app.

| File | What it is | SHA-256 |
|---|---|---|
| `gradient.heic` | 64 x 48 HEIC (brand heic), 922 bytes. The same bytes are `HeicSample` in the service. | `e9d33a0229b12f26e652fc852e786378bed965928cef5613c6c828603573ef25` |
| `truncated.webp` | A 256 x 192 lossless WebP cut to half its bytes (21398 bytes): the decoder must say DAMAGED. | `60dc5545333744fa81e53e31222761a0471507278463afb0077ea095aa96c679` |
| `truncated.pdf` | A one-page PDF whose catalog comes last, cut to half its bytes (2086 bytes): no catalog, no trailer, DAMAGED at open. | `f6c507d7f9d094bfae5ca50b1b0f31599f18f9d605a5c0f1ff6f413593efc2de` |

## How they were made

In the dev container, in a virtual environment with `pip install pillow-heif==1.8.1` (pillow-heif 1.8.1, libheif
1.23.6, x265, Pillow 12.3.0). Save the script below as `make_fixtures.py` in a directory of its own and run it from
there with `python -I make_fixtures.py <output directory>`. The output is byte for byte the committed files.

```python
# Writes the sealed decoder's androidTest fixtures into the directory given as the only argument.
# Needs Pillow and pillow-heif (pillow-heif 1.8.1: libheif 1.23.6, x265). Run with `python -I` from this file's directory.
import os
import sys

from PIL import Image
import pillow_heif

out = sys.argv[1]
pillow_heif.register_heif_opener()

# gradient.heic: 64 x 48, brand heic. The same bytes are HeicSample in the service.
heic = Image.new("RGB", (64, 48))
heic.putdata([(x * 4, y * 5, 128) for y in range(48) for x in range(64)])
heic.save(os.path.join(out, "gradient.heic"), format="HEIF", quality=90)

# truncated.webp: a 256 x 192 lossless WebP, cut to half its bytes.
webp = Image.new("RGB", (256, 192))
webp.putdata([(((x * 7) ^ (y * 13)) & 255, (x + y) & 255, (x * y) & 255) for y in range(192) for x in range(256)])
path = os.path.join(out, "full.webp")
webp.save(path, format="WEBP", lossless=True)
data = open(path, "rb").read()
os.remove(path)
open(os.path.join(out, "truncated.webp"), "wb").write(data[: len(data) // 2])

# truncated.pdf: a one-page PDF whose catalog comes last, cut to half its bytes, so the catalog and trailer are gone.
ops = "".join(f"{(i * 37) % 500 + 20} {(i * 53) % 760 + 20} 30 20 re f\n" for i in range(200)).encode()
objs = [
    b"<< /Length %d >>\nstream\n" % len(ops) + ops + b"endstream",
    b"<< /Type /Page /Parent 3 0 R /MediaBox [0 0 595 842] /Contents 1 0 R >>",
    b"<< /Type /Pages /Kids [2 0 R] /Count 1 >>",
    b"<< /Type /Catalog /Pages 3 0 R >>",
]
pdf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
offsets = []
for n, body in enumerate(objs, start=1):
    offsets.append(len(pdf))
    pdf += b"%d 0 obj\n" % n + body + b"\nendobj\n"
xref = len(pdf)
pdf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
pdf += b"".join(b"%010d 00000 n \n" % o for o in offsets)
pdf += b"trailer\n<< /Size %d /Root 4 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
open(os.path.join(out, "truncated.pdf"), "wb").write(bytes(pdf[: len(pdf) // 2]))
```
