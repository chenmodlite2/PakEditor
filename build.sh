#!/bin/bash
# language: bash, target: Android APK (Termux aapt2 pipeline), host: Android aarch64
set -e
PREFIX=/data/data/com.termux/files/usr
PROJ=/root/pakeditor-android
AJ=/root/build/android-34/android-UpsideDownCake/android.jar
STUB=/root/build/lambda-stub.jar
AAPT2=$PREFIX/bin/aapt2
D8=$PREFIX/bin/d8
APKSIGNER=$PREFIX/bin/apksigner
KS=/root/build/bittu/debug.keystore
OUT=/root/build/out_pakeditor

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "== 1. aapt2 compile =="
"$AAPT2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "== 2. aapt2 link =="
"$AAPT2" link -o "$OUT/app.unsigned.apk" \
  -I "$AJ" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 11 --version-name "1.1.0" \
  --auto-add-overlay \
  --no-version-vectors \
  "$OUT/res.zip"

echo "== 3. javac =="
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ:$STUB" \
  -cp "$AJ:$OUT/gen" -d "$OUT/classes" \
  $(find "$PROJ/src" "$OUT/gen" -name '*.java')

echo "== 4. d8 =="
"$D8" --lib "$AJ" --lib "$STUB" --min-api 24 --release --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "== 5. package dex + assets =="
python3 - "$OUT/app.unsigned.apk" "$OUT/dex" "$PROJ" <<'PY'
import sys, zipfile, os, glob
apk, dexdir, proj = sys.argv[1], sys.argv[2], sys.argv[3]
tmp = apk + ".tmp"
zin = zipfile.ZipFile(apk, 'r')
zout = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zout.writestr(it, zin.read(it.filename))
for dex in sorted(glob.glob(os.path.join(dexdir, '*.dex'))):
    zout.writestr(os.path.basename(dex), open(dex, 'rb').read())
    print("appended", os.path.basename(dex), os.path.getsize(dex), "bytes")
assets = os.path.join(proj, 'assets')
if os.path.isdir(assets):
    for root, dirs, files in os.walk(assets):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, assets)
            zout.write(full, 'assets/' + arc)
zout.close(); zin.close()
os.replace(tmp, apk)
print("apk bytes", os.path.getsize(apk))
PY

echo "== 6. zipalign =="
python3 - "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk" <<'PY'
import sys, struct, zipfile, zlib
src, dst = sys.argv[1], sys.argv[2]

def dos(dt):
    y, mo, d, h, mi, s = dt
    t = (h << 11) | (mi << 5) | (s // 2)
    dd = ((y - 1980) << 9) | (mo << 5) | d
    return t & 0xffff, dd & 0xffff

zin = zipfile.ZipFile(src, 'r')
entries = [(it, zin.read(it.filename)) for it in zin.infolist()]
out = bytearray()
centrals = []
for it, raw in entries:
    name = it.filename.encode('utf-8')
    method = it.compress_type
    t, dd = dos(it.date_time)
    if method == zipfile.ZIP_STORED:
        hdr = 30 + len(name)
        pad = (-(len(out) + hdr)) % 4
        extra = it.extra + b'\x00' * pad
        blob = raw
        crc = zlib.crc32(raw) & 0xffffffff
        csize = usize = len(raw)
    else:
        extra = it.extra
        co = zlib.compressobj(9, zlib.DEFLATED, -15)
        blob = co.compress(raw) + co.flush()
        crc = zlib.crc32(raw) & 0xffffffff
        csize = len(blob); usize = len(raw)
    lhoff = len(out)
    lfh = struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, 0, method, t, dd, crc, csize, usize, len(name), len(extra))
    out += lfh + name + extra + blob
    centrals.append((name, extra, method, t, dd, crc, csize, usize, lhoff))

cd_start = len(out)
cds = bytearray()
for (name, extra, method, t, dd, crc, csize, usize, lhoff) in centrals:
    cdh = struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, 0, method, t, dd, crc, csize, usize,
                      len(name), len(extra), 0, 0, 0, 0, lhoff)
    cds += cdh + name + extra
out += cds
out += struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(centrals), len(centrals), len(cds), cd_start, 0)
open(dst, 'wb').write(out)
print("aligned", len(out), "bytes")
PY

echo "== 7. apksigner sign =="
"$APKSIGNER" sign \
  --ks "$KS" \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT/PakEditor-v1.1.0.apk" \
  "$OUT/app.aligned.apk"

"$APKSIGNER" verify --print-certs "$OUT/PakEditor-v1.1.0.apk" | head -4
ls -la "$OUT/PakEditor-v1.1.0.apk"
echo "DONE"
