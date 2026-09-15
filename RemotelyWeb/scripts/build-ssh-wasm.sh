#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
module_dir="$(cd -- "$script_dir/../src/main/go/browser-ssh" && pwd)"
output_dir="${1:?Output directory is required}"
toolchain="go1.27.1"

version="$(GOTOOLCHAIN="$toolchain" go version)"
if [[ "$version" != "go version $toolchain "* ]]; then
    echo "Browser SSH requires $toolchain" >&2
    exit 1
fi

mkdir -p "$output_dir"
(
    cd "$module_dir"
    GOTOOLCHAIN="$toolchain" GOOS=js GOARCH=wasm CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false -ldflags='-s -w' -o "$output_dir/restudio-ssh.wasm" .
)

go_root="$(GOTOOLCHAIN="$toolchain" go env GOROOT)"
runtime="$go_root/lib/wasm/wasm_exec.js"
if [[ ! -f "$runtime" ]]; then
    runtime="$go_root/misc/wasm/wasm_exec.js"
fi
if [[ ! -f "$runtime" ]]; then
    echo "Go WebAssembly runtime is unavailable" >&2
    exit 1
fi
cp "$runtime" "$output_dir/wasm_exec.js"
