from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
failures = []
declarations = set()
count = 0
for source_set in ("main", "test", "androidTest"):
    base = root / "app" / "src" / source_set / "kotlin"
    for source in base.rglob("*.kt"):
        count += 1
        text = source.read_text()
        match = re.search(r"^package ([\w.]+)$", text, re.MULTILINE)
        if not match:
            failures.append(f"Missing package: {source.relative_to(root)}")
            continue
        package = match.group(1)
        if source.parent.relative_to(base).as_posix() != package.replace(".", "/"):
            failures.append(f"Package/path mismatch: {source.relative_to(root)}")
        if source_set == "main":
            for match in re.finditer(
                r"^(?:(?:internal|public|data|sealed|enum|annotation|value|open|abstract)\s+)*"
                r"(?:class|object|interface)\s+(\w+)", text, re.MULTILINE
            ):
                declarations.add(f"{package}.{match.group(1)}")

manifest = ET.parse(root / "app/src/main/AndroidManifest.xml")
for element in manifest.iter():
    if element.tag not in ("activity", "service", "provider", "receiver", "application"):
        continue
    name = element.get("{http://schemas.android.com/apk/res/android}name", "")
    if name.startswith("."):
        name = "com.simplexray.an" + name
    if name.startswith("com.simplexray.an.") and name not in declarations:
        failures.append(f"Missing Android component: {name}")

rules = (root / "app/proguard-rules.pro").read_text()
for name in re.findall(r"-keep (?:class|enum) (com\.simplexray\.an\.[\w.]+)", rules):
    if name not in declarations:
        failures.append(f"Stale ProGuard class: {name}")

if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print(f"Source layout OK: {count} Kotlin files; Android components and ProGuard targets resolve")
