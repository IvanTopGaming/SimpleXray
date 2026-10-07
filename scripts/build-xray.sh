#!/usr/bin/env bash
set -euo pipefail
root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir=""
with_host=false
while (($#)); do
    case "$1" in
        --source-dir) source_dir="$2"; shift 2 ;;
        --host) with_host=true; shift ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done
property() { sed -n "s/^$1=//p" "$root_dir/version.properties" | tr -d '\r'; }
core_version="$(property XRAY_CORE_VERSION)"
go_version="$(property GO_VERSION)"
ndk_version="$(property NDK_VERSION)"
actual_go="$(go env GOVERSION)"
if [[ "${actual_go%%-*}" != "go${go_version}" ]]; then
    echo "Go ${go_version} is required; found ${actual_go}" >&2
    exit 1
fi
manifest="$root_dir/patches/xray/$core_version/build.json"
mapfile -t core_spec < <(python3 - "$manifest" <<'PY'
import json,sys
spec=json.load(open(sys.argv[1]))
print(spec['revision'])
print(spec['buildSuffix'])
print(*spec['patches'],sep='\n')
PY
)
expected_revision="${core_spec[0]:?Missing core revision}"
build_suffix="${core_spec[1]:?Missing build suffix}"
if [[ -z "$source_dir" ]]; then
    source_dir="$root_dir/.gradle/xray-core/$core_version"
    if [[ ! -d "$source_dir/.git" ]]; then
        mkdir -p "$(dirname "$source_dir")"
        git clone --depth 1 --branch "$core_version" https://github.com/XTLS/Xray-core.git "$source_dir"
    fi
fi
source_dir="$(cd -- "$source_dir" && pwd)"
revision="$(git -C "$source_dir" rev-parse HEAD)"
if [[ "$revision" != "$expected_revision" ]]; then
    echo "Unexpected Xray source revision: $revision" >&2
    exit 1
fi
for patch in "${core_spec[@]:2}"; do
    patch_file="$root_dir/patches/xray/$core_version/$patch"
    if git -C "$source_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
        continue
    fi
    git -C "$source_dir" apply --check "$patch_file"
    git -C "$source_dir" apply "$patch_file"
done
python3 - "$source_dir" "$root_dir/patches/xray/$core_version" "$manifest" <<'PYVERIFY'
import json,os,subprocess,sys,tempfile
source,patch_dir,manifest=sys.argv[1:]
with tempfile.TemporaryDirectory(prefix='simplexray-core-index-') as temporary:
    env=os.environ.copy()
    env['GIT_INDEX_FILE']=temporary+'/index'
    def git(*args):
        return subprocess.run(['git','-C',source,*args],env=env,capture_output=True,text=True,check=True).stdout
    git('read-tree','HEAD')
    for patch in json.load(open(manifest))['patches']:
        git('apply','--cached',patch_dir+'/'+patch)
    git('diff','--exit-code','--')
    if git('ls-files','--others','--exclude-standard').strip():
        raise SystemExit('Unexpected untracked files in Xray source directory')
PYVERIFY
build_marker="${revision:0:7}-${build_suffix}"
base_ldflags="-X github.com/xtls/xray-core/core.build=${build_marker} -s -w -buildid="
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk_dir="$sdk_dir/ndk/$ndk_version"
ndk_bin="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$ndk_bin/aarch64-linux-android24-clang" ]] || { echo "Install Android NDK $ndk_version" >&2; exit 1; }
cd -- "$source_dir"
if "$with_host"; then
    mkdir -p "$root_dir/.gradle/xray-host"
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -mod=readonly -trimpath -buildvcs=false -ldflags="$base_ldflags" -o "$root_dir/.gradle/xray-host/xray" ./main
fi
for target in arm64-v8a x86_64; do
    if [[ "$target" == arm64-v8a ]]; then
        go_arch=arm64
        cc="$ndk_bin/aarch64-linux-android24-clang"
    else
        go_arch=amd64
        cc="$ndk_bin/x86_64-linux-android24-clang"
    fi
    mkdir -p "$root_dir/app/src/main/jniLibs/$target"
    CGO_ENABLED=1 GOOS=android GOARCH="$go_arch" CC="$cc" go build -mod=readonly -trimpath -buildvcs=false -ldflags="$base_ldflags -checklinkname=0" -o "$root_dir/app/src/main/jniLibs/$target/libxray.so" ./main
done
printf 'Built Xray %s (%s) for arm64-v8a and x86_64\n' "$core_version" "$build_marker"
