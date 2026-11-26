"""Keep the official 0.7.69 OpenVPN engine intact and add Setu VPN's app shell."""
from pathlib import Path
from struct import pack_into, unpack_from
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
import re
import sys

base, shell, dex, directory, icon, jni, result = map(Path, sys.argv[1:])
with ZipFile(shell) as source:
    manifest = bytearray(source.read("AndroidManifest.xml"))

# Resolve the launcher icons and dialog theme against the pinned upstream
# resource table. The generated app manifest otherwise uses SDK resources.
def replace_reference(blob, old, new):
    position = 8
    while position < len(blob):
        kind, header_size, size = unpack_from("<HHI", blob, position)
        if kind == 0x0102:
            extension = position + header_size
            start, attribute_size, count = unpack_from("<HHH", blob, extension + 8)
            for i in range(count):
                attribute = extension + start + attribute_size * i
                data_type, reference = unpack_from("<BI", blob, attribute + 15)
                if data_type == 1 and reference == old:
                    pack_into("<I", blob, attribute + 16, new)
        if size < 8:
            raise ValueError("Invalid manifest")
        position += size

icon_placeholder, round_placeholder, dialog_placeholder = (
    int(value, 16) for value in (shell.parent / "framework-refs.txt").read_text().split()
)
replace_reference(manifest, icon_placeholder, 0x7F0F0001)
replace_reference(manifest, round_placeholder, 0x7F0F0002)
replace_reference(manifest, dialog_placeholder, 0x7F12060F)

with ZipFile(base) as original, ZipFile(icon) as icon_archive, ZipFile(result, "w") as output:
    logo = bytearray(icon_archive.read("res/drawable/paperline.xml"))
    replace_reference(logo, icon_placeholder, 0x7F0F0000)
    app = Path(__file__).resolve().parent.parent / "main/src/main"
    assets = app / "assets"
    drawables = app / "res/drawable-nodpi"
    foreground = (drawables / "setu_bridge_foreground.png").read_bytes()
    brand = (assets / "setu-logo.png").read_bytes()
    legacy = (drawables / "setu_bridge.png").read_bytes()
    notification = icon_archive.read("res/drawable/setu_notification.xml")
    # Connection state icons in the pinned OpenVPN resource table.
    status_icons = {"res/mw.xml", "res/_G1.xml", "res/oP.xml", "res/0X.xml"}
    for info in original.infolist():
        name = info.filename
        if name == "AndroidManifest.xml" or name.startswith("assets/dexopt/"):
            continue
        if name.startswith("META-INF/") and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC", ".MF")):
            continue
        data = original.read(name)
        if name in {"res/BW.xml", "res/0K.xml"}:
            data = logo
        elif name == "res/1e.png":
            data = foreground
        elif name in {"res/9w.png", "res/yn.png", "res/FS.png", "res/RJ.png", "res/o-.png",
                      "res/zR.png", "res/8c.png", "res/wb.png", "res/fO.png", "res/Gc.png"}:
            data = legacy
        elif name in status_icons:
            data = notification
        output.writestr(info, data)
    output.writestr("AndroidManifest.xml", manifest, ZIP_DEFLATED)
    indices = [int(m.group(1) or 1) for name in original.namelist()
               if (m := re.fullmatch(r"classes(\d*)\.dex", name))]
    output.writestr(f"classes{max(indices) + 1}.dex", dex.read_bytes(), ZIP_DEFLATED)
    output.writestr("assets/paperline-directory.json", directory.read_bytes(), ZIP_DEFLATED)
    output.writestr("assets/setu-logo.png",brand,ZIP_DEFLATED)
    output.write(assets / "paperline-starter.json","assets/paperline-starter.json",ZIP_DEFLATED)
    output.write(assets / "paperline-grokssh.tsv","assets/paperline-grokssh.tsv",ZIP_DEFLATED)
    for font in sorted((assets / "fonts").iterdir()):
        output.write(font,"assets/fonts/" + font.name,ZIP_DEFLATED)
    quick = assets / "paperline-quick"
    for profile in sorted(quick.glob("*.json")):
        output.write(profile, "assets/paperline-quick/" + profile.name, ZIP_DEFLATED)
    for abi_directory in sorted(jni.iterdir()):
        output.write(abi_directory / "libsetu_core.so",
                     f"lib/{abi_directory.name}/libsetu_core.so", ZIP_STORED)
