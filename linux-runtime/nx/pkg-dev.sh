# pkg-dev.sh — nx pkg build / release / info.
# Sourced by nx after pkg-lib.sh. shell=bash
# shellcheck shell=bash

# detect_language -> sets NX_LANG_ID; fails when zero or several build files
# are present (ambiguous projects must never be silently mis-built).
detect_language() {
    local n=0
    NX_LANG_ID=""
    if [ -f package.json ];    then NX_LANG_ID="nodejs"; n=$((n + 1)); fi
    if [ -f Cargo.toml ];      then NX_LANG_ID="rust";   n=$((n + 1)); fi
    if [ -f CMakeLists.txt ];  then NX_LANG_ID="cpp";    n=$((n + 1)); fi
    if [ -f pyproject.toml ];  then NX_LANG_ID="python"; n=$((n + 1)); fi
    if [ -f go.mod ];          then NX_LANG_ID="go";     n=$((n + 1)); fi
    [ "$n" -eq 1 ]
}

build_language() {  # build_language LANG -> executes the real build system
    local lang="$1"
    stage "Building package ($lang)..."
    case "$lang" in
        nodejs)
            command -v npm >/dev/null 2>&1 || die "npm is not installed. Install Node.js first."
            if [ -f package-lock.json ]; then
                npm ci --no-audit --no-fund || die "Build failed (npm ci)."
            else
                npm install --no-audit --no-fund || die "Build failed (npm install)."
            fi
            if [ -f src/main.js ] && node --check src/main.js; then
                stage "Compiling... done (syntax check OK)"
            else
                die "src/main.js not found or invalid JavaScript."
            fi
            ;;
        rust)
            command -v cargo >/dev/null 2>&1 || die "cargo is not installed. Install Rust first."
            stage "Compiling (cargo)..."
            cargo build --release || die "Build failed (cargo)."
            ;;
        cpp)
            command -v cmake >/dev/null 2>&1 || die "cmake is not installed. Install a C++ toolchain first."
            stage "Compiling (cmake)..."
            cmake -S . -B build -DCMAKE_BUILD_TYPE=Release || die "Configure failed (cmake)."
            cmake --build build --parallel || die "Build failed (cmake)."
            ;;
        python)
            command -v python3 >/dev/null 2>&1 || die "python3 is not installed."
            stage "Compiling (bytecode + wheel)..."
            python3 -m compileall -q src || die "Python sources do not compile."
            if python3 -m pip --version >/dev/null 2>&1; then
                if python3 -m pip wheel --no-deps -w build-wheel . >/dev/null 2>&1; then
                    stage "Wheel built: build-wheel/"
                else
                    msg "note: wheel build skipped (packaging backend missing)"
                fi
            fi
            ;;
        go)
            command -v go >/dev/null 2>&1 || die "go is not installed. Install Go first."
            stage "Compiling (go build)..."
            go build -o "$NX_PKG_NAME" . || die "Build failed (go)."
            ;;
    esac
}

# pkg_name_from_dir -> lowercased current directory name (matches repo name).
pkg_name_from_dir() {
    local base
    base=$(basename "$PWD")
    printf '%s' "$base" | tr '[:upper:]' '[:lower:]'
}

pkg_build_cmd() {
    detect_language || die "Unable to detect package language (need exactly one of package.json, Cargo.toml, CMakeLists.txt, pyproject.toml, go.mod)."
    NX_PKG_NAME=$(pkg_name_from_dir)
    build_language "$NX_LANG_ID"
    msg "Build finished."
}

pkg_release_cmd() {
    detect_language || die "Unable to detect package language (need exactly one of package.json, Cargo.toml, CMakeLists.txt, pyproject.toml, go.mod)."
    local name version tag arch

    name=$(pkg_name_from_dir)
    NX_PKG_NAME="$name"
    valid_name "$name" || die "Invalid package name from directory: $name"

    # --- VERSION (semantic MAJOR.MINOR.PATCH; invalid releases are rejected)
    [ -f VERSION ] || die "VERSION file is missing."
    version=$(tr -d '[:space:]' < VERSION)
    valid_version "$version" || die "Invalid package version: $version (expected MAJOR.MINOR.PATCH)"

    # --- registry.json must exist and parse
    [ -f registry.json ] || die "registry.json is missing."
    registry_parse registry.json
    [ "$RG_NAME" = "$name" ] || die "registry.json name ($RG_NAME) does not match the package directory ($name)."

    # --- source must build
    build_language "$NX_LANG_ID"

    # --- packaging pre-flight (same layout the CI workflow produces)
    arch=$(detect_arch)
    [ -n "$arch" ] || die "Unsupported architecture: $(uname -m)"
    make_workdir
    stage "Packaging $name.nx.pkg.$version.$arch.tar.xz..."
    local staging dist
    staging="$NX_WORK/staging"
    mkdir -p "$staging/usr/local/bin" "$staging/usr/local/share/doc/$name" || die "Cannot create staging layout."
    case "$NX_LANG_ID" in
        rust)
            local bin="target/release/$name"
            [ -f "$bin" ] || die "Cargo build output not found: $bin"
            cp "$bin" "$staging/usr/local/bin/$name" ;;
        cpp)
            [ -f "build/$name" ] || die "CMake build output not found: build/$name"
            cp "build/$name" "$staging/usr/local/bin/$name" ;;
        go)
            [ -f "$name" ] || die "Go build output not found: $name"
            cp "$name" "$staging/usr/local/bin/$name" ;;
        nodejs)
            mkdir -p "$staging/usr/local/lib/$name"
            cp -a src package.json "$staging/usr/local/lib/$name/" || die "Cannot collect Node.js sources."
            if [ -d node_modules ]; then cp -a node_modules "$staging/usr/local/lib/$name/"; fi
            printf '#!/bin/sh\nexec node "/usr/local/lib/%s/src/main.js" "$@"\n' "$name" > "$staging/usr/local/bin/$name"
            chmod +x "$staging/usr/local/bin/$name" ;;
        python)
            python3 -m pip install --no-deps --root-user-action=ignore \
                --target "$staging/usr/local/lib/noxs-py/$name" . >/dev/null \
                || die "Python packaging failed."
            rm -rf "$staging/usr/local/lib/noxs-py/$name/bin"
            {
                printf '#!/usr/bin/env python3\n'
                printf 'import sys\n'
                printf 'sys.path.insert(0, "/usr/local/lib/noxs-py/%s")\n' "$name"
                printf 'from main import main\n'
                printf 'sys.exit(main())\n'
            } > "$staging/usr/local/bin/$name"
            chmod +x "$staging/usr/local/bin/$name" ;;
    esac
    for f in README.md LICENSE VERSION; do
        [ -f "$f" ] && cp "$f" "$staging/usr/local/share/doc/$name/"
    done
    mkdir -p dist
    dist="dist/$name.nx.pkg.$version.$arch.tar.xz"
    tar -cJf "$dist" -C "$staging" usr || die "Packaging failed."
    sha256_of "$dist" > "$dist.sha256"
    check_tarball_members "$dist"
    stage "Packaging... done (dist/)"

    # --- release tag must be free; never overwrite existing releases
    tag="$name-v$version"
    if command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1 \
        && git remote get-url origin >/dev/null 2>&1; then
        if git ls-remote --exit-code origin "refs/tags/$tag" >/dev/null 2>&1; then
            die "Release already exists: $tag. Bump VERSION first."
        fi
    else
        msg "note: no git origin configured — the tag check will run in CI"
    fi

    cat <<EOF

Pre-flight passed:
  VERSION        $version
  registry.json  valid (latest: $RG_LATEST)
  build          $NX_LANG_ID OK
  artifact       $dist (+ .sha256)

To publish: git add . && git commit -m "release: $name $version" && git push
GitHub Actions will tag $tag, create the release and update registry.json.
EOF
}

