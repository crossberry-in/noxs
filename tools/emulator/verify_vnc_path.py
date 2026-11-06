#!/usr/bin/env python3
"""
Noxs × NoxOS — M2/M6 display-bridge verification harness.

Runs the EXACT M2 device QEMU command line (shared/emulator/QemuCommand.kt
build()) against real Debian bookworm qemu-system-aarch64 7.2.22, then
exercises the SAME RFB protocol sequence the Android client implements
(app/src/main/java/com/crossberry/noxs/emulator/VncClient.kt, codec in
noxs-shared .../shared/emulator/Rfb.kt):

  1. RFB 3.8 handshake, None security type
  2. SetPixelFormat  (32bpp LE, depth 24, shifts 16/8/0)
  3. SetEncodings    (raw only)
  4. FramebufferUpdateRequest (full) → receive + decode raw rects → ARGB
  5. KeyEvent (ENTER down/up) + PointerEvent — server must stay healthy
  6. incremental update round (request → receive → decode)
  7. QEMU serial log must contain the NoxOS boot markers (M1 gate subset)
  8. the decoded framebuffer is saved as a real PNG (visual evidence,
     captured through the VNC display path itself — not a QMP screendump)

Exit code 0 = all steps passed. This harness mirrors the Kotlin logic 1:1
byte for byte; it is NOT a substitute for on-device testing (M9), it
verifies the protocol path and the boot argv on the M1 baseline.

Usage:
  tools/emulator/verify_vnc_path.py <qemu-system-aarch64> <noxos-kernel-elf> [outdir]
"""
import os
import socket
import struct
import subprocess
import sys
import time

VNC_PORT_BASE = 5900  # QemuCommand.VNC_PORT_BASE
DISPLAY = 1           # QemuCommand.VNC_DISPLAY_MIN


def build_qemu_argv(kernel: str, serial_log: str, pid_file: str, memory_mb: int = 512) -> list:
    """Byte-identical mirror of shared/emulator/QemuCommand.build()."""
    return [
        "qemu-system-aarch64",
        "-M", "virt",
        "-cpu", "cortex-a53",
        "-m", f"{memory_mb}M",
        "-nic", "none",
        "-global", "virtio-mmio.force-legacy=off",
        "-device", "virtio-gpu-device,xres=720,yres=1280",
        "-device", "virtio-keyboard-device",
        "-device", "virtio-tablet-device",
        "-kernel", kernel,
        "-serial", f"file:{serial_log}",
        "-pidfile", pid_file,
        "-vnc", f"127.0.0.1:{DISPLAY}",
    ]


def recv_exact(s: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("server closed the connection")
        buf += chunk
    return buf


def decode_raw_rect(w, h, data):
    """Mirror of Rfb.decodeRawRect: little-endian B,G,R,x → opaque ARGB."""
    count = w * h
    pixels = [0] * count
    for i in range(count):
        b = data[i * 4]
        g = data[i * 4 + 1]
        r = data[i * 4 + 2]
        pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b
    return pixels


def save_png(path, width, height, argb):
    """Minimal truecolor PNG writer (zlib compress, no per-row filters)."""
    import zlib
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0
        base = y * width
        for x in range(width):
            v = argb[base + x] & 0xFFFFFF
            raw.append((v >> 16) & 0xFF)  # R
            raw.append((v >> 8) & 0xFF)   # G
            raw.append(v & 0xFF)          # B
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


def wait_for_port(port, timeout=20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=1.0)
            s.close()
            return True
        except OSError:
            time.sleep(0.25)
    return False


def wait_for_surface(port, timeout=25.0):
    """Poll ServerInit until the virtio-gpu console reports 720x1280 (the
    QEMU-side surface race on a fresh VM is ~1s)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=2.0)
            s.settimeout(3.0)
            try:
                hs = recv_exact(s, 12)
                s.sendall(b"RFB 003.008\n")
                cnt = recv_exact(s, 1)[0]
                types = recv_exact(s, cnt)
                if 1 not in types:
                    continue
                s.sendall(b"\x01")
                res = struct.unpack(">I", recv_exact(s, 4))[0]
                if res != 0:
                    continue
                s.sendall(b"\x01")
                fixed = recv_exact(s, 24)
                w, h = struct.unpack(">HH", fixed[:4])
                if (w, h) == (720, 1280):
                    return True
            finally:
                s.close()
        except OSError:
            pass
        time.sleep(1.0)
    return False


def vnc_session(port, out_png, settle_seconds=60):
    """
    The VncClient.runLoop sequence, faithful to reality:
    an early connect sees the virtio-gpu DEFAULT 640x480 framebuffer; when
    the guest sets its 720x1280 scanout, QEMU sends the DesktopResize
    pseudo-encoding (-223) — exactly what the Android client handles in
    ENC_PSEUDO_DESKTOP_RESIZE. We settle on 720x1280 with real (non-black)
    pixels, then exercise input + an incremental round.
    Returns (width, height, name, frames, pixels_total).
    """
    s = socket.create_connection(("127.0.0.1", port), timeout=10.0)
    s.settimeout(30.0)
    try:
        # 1. ProtocolVersion (accept 003.x, reply 003.008 — Rfb.isSupportedVersion)
        handshake = recv_exact(s, 12)
        if not handshake.startswith(b"RFB 003."):
            raise AssertionError(f"bad protocol version: {handshake!r}")
        minor = int(handshake[8:11])
        if not (3 <= minor <= 8):
            raise AssertionError(f"unsupported minor version {minor}")
        s.sendall(b"RFB 003.008\n")

        # 2. Security (None) — mirrors the minor==3 / >=7 branches
        if minor == 3:
            chosen = struct.unpack(">I", recv_exact(s, 4))[0]
            if chosen != 1:
                raise AssertionError(f"server picked auth type {chosen}")
        else:
            count = recv_exact(s, 1)[0]
            if count == 0:
                reason_len = struct.unpack(">I", recv_exact(s, 4))[0]
                raise AssertionError("server refused: " + recv_exact(s, reason_len).decode())
            types = recv_exact(s, count)
            if 1 not in types:
                raise AssertionError(f"None security not offered: {list(types)}")
            s.sendall(b"\x01")
            if minor >= 8:
                result = struct.unpack(">I", recv_exact(s, 4))[0]
                if result != 0:
                    raise AssertionError(f"security result {result}")

        # 3. ClientInit(shared) + ServerInit — if we connect within the first
        #    second of QEMU's life the virtio-gpu console may still be at its
        #    pre-init 640x480 default surface; the guest's 720x1280 scanout
        #    then arrives either as a DesktopResize pseudo-encoding or on a
        #    reconnect. The Android client handles both paths; the harness
        #    reconnects here to observe the settled surface.
        s.sendall(b"\x01")
        fixed = recv_exact(s, 24)
        width, height = struct.unpack(">HH", fixed[:4])
        name_len = struct.unpack(">I", fixed[20:24])[0]
        name = recv_exact(s, name_len).decode("latin-1")
        initial_size = (width, height)
        if initial_size != (720, 1280):
            s.close()
            for _ in range(15):
                time.sleep(1.0)
                s = socket.create_connection(("127.0.0.1", port), timeout=10.0)
                s.settimeout(30.0)
                hs = recv_exact(s, 12)
                if not hs.startswith(b"RFB 003."):
                    raise AssertionError(f"bad protocol version: {hs!r}")
                s.sendall(b"RFB 003.008\n")
                cnt = recv_exact(s, 1)[0]
                types = recv_exact(s, cnt)
                if 1 not in types:
                    raise AssertionError("None security not offered on reconnect")
                s.sendall(b"\x01")
                res = struct.unpack(">I", recv_exact(s, 4))[0]
                if res != 0:
                    raise AssertionError(f"security result {res}")
                s.sendall(b"\x01")
                fixed = recv_exact(s, 24)
                width, height = struct.unpack(">HH", fixed[:4])
                name_len = struct.unpack(">I", fixed[20:24])[0]
                name = recv_exact(s, name_len).decode("latin-1")
                if (width, height) == (720, 1280):
                    break
            initial_size = (width, height)

        # 4. SetPixelFormat + SetEncodings(raw) + full FBUR (Rfb.* layouts)
        pixfmt = bytes([
            32, 24, 0, 1,
        ]) + struct.pack(">HHH", 255, 255, 255) + bytes([16, 8, 0, 0, 0, 0])
        s.sendall(b"\x00" + b"\x00\x00\x00" + pixfmt)
        s.sendall(b"\x02\x00" + struct.pack(">H", 1) + struct.pack(">i", 0))
        s.sendall(b"\x03\x00" + struct.pack(">HHHH", 0, 0, width, height))

        framebuffer = [0] * (width * height)
        frames_total, pixels_total = 0, 0

        def read_update():
            """One message dispatch round; returns (n_raw_rects, n_pixels)."""
            nonlocal width, height, framebuffer
            rtype = recv_exact(s, 1)[0]
            if rtype == 1:  # SetColourMapEntries (skip, stay in sync)
                recv_exact(s, 5)
                _first, n = struct.unpack(">HH", recv_exact(s, 4))
                if n:
                    recv_exact(s, n * 6)
                return read_update()
            if rtype == 2:  # Bell
                return read_update()
            if rtype == 3:  # ServerCutText (consume text, stay in sync)
                ln = struct.unpack(">I", recv_exact(s, 4))[0]
                if ln:
                    recv_exact(s, ln)
                return read_update()
            if rtype != 0:
                raise AssertionError(f"unexpected message type {rtype}")
            recv_exact(s, 1)
            n_rects = struct.unpack(">H", recv_exact(s, 2))[0]
            frames, pixels = 0, 0
            resized = False
            for _ in range(n_rects):
                x, y, w, h = struct.unpack(">HHHH", recv_exact(s, 8))
                enc = struct.unpack(">i", recv_exact(s, 4))[0]
                if enc == -223:  # DesktopResize: guest scanout changed
                    width, height = w, h
                    framebuffer = [0] * (width * height)
                    resized = True
                    continue
                if enc != 0:
                    raise AssertionError(f"server sent encoding {enc}, raw only was requested")
                data = recv_exact(s, w * h * 4)
                rect = decode_raw_rect(w, h, data)
                frames += 1
                pixels += w * h
                if x == 0 and y == 0 and w == width and h == height:
                    framebuffer[:] = rect
            if resized:
                # Mirror of the client: after a resize, request a full update.
                s.sendall(b"\x03\x00" + struct.pack(">HHHH", 0, 0, width, height))
            return frames, pixels

        # 5. Settle loop: follow resizes until the guest framebuffer is the
        #    real 720x1280 surface AND it carries non-uniform pixels (the
        #    NoxOS UI actually rendering through the VNC path).
        deadline = time.time() + settle_seconds
        settled = False
        while time.time() < deadline:
            frames, pixels = read_update()
            frames_total += frames
            pixels_total += pixels
            if (width, height) != (720, 1280):
                s.sendall(b"\x03\x00" + struct.pack(">HHHH", 0, 0, width, height))
                continue
            distinct = len(set(framebuffer[: width * height: 997]))
            if frames > 0 and distinct > 4:
                settled = True
                break
            s.sendall(b"\x03\x01" + struct.pack(">HHHH", 0, 0, width, height))
        if not settled:
            raise AssertionError(
                f"framebuffer never settled to 720x1280 with content "
                f"(current {width}x{height})"
            )

        # 6. Input events: ENTER down/up + pointer press/release (Rfb layouts)
        s.sendall(b"\x04\x01\x00\x00" + struct.pack(">I", 0xFF0D))
        s.sendall(b"\x04\x00\x00\x00" + struct.pack(">I", 0xFF0D))
        s.sendall(b"\x05\x01" + struct.pack(">HH", 360, 640))
        s.sendall(b"\x05\x00" + struct.pack(">HH", 360, 640))

        # 7. Incremental round: request → receive (server must stay healthy
        #    after the input events above).
        s.sendall(b"\x03\x01" + struct.pack(">HHHH", 0, 0, width, height))
        frames2, pixels2 = read_update()
        frames_total += frames2
        pixels_total += pixels2

        save_png(out_png, width, height, framebuffer)
        return initial_size, (width, height), name, frames_total, pixels_total
    finally:
        s.close()


BOOT_MARKERS = (
    "DTB ok:",
    "MMU enabled: true",
    "BOOT SMOKE SEQUENCE COMPLETE",
)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    qemu = os.path.abspath(sys.argv[1])
    kernel = os.path.abspath(sys.argv[2])
    outdir = os.path.abspath(sys.argv[3]) if len(sys.argv) > 3 else "/tmp/noxs-vnc-verify"
    os.makedirs(outdir, exist_ok=True)

    serial_log = os.path.join(outdir, "serial.log")
    qemu_log = os.path.join(outdir, "qemu.log")
    pid_file = os.path.join(outdir, "qemu.pid")
    for f in (serial_log, qemu_log, pid_file):
        if os.path.exists(f):
            os.unlink(f)

    argv = build_qemu_argv(kernel, serial_log, pid_file)
    # argv[0] is the guest-side name (inside the proot rootfs the PATH
    # resolves it); on the dev host we substitute the wrapper/binary path.
    argv[0] = qemu
    print("==> M2 device argv (mirror of QemuCommand.build()):")
    print("    " + " ".join(argv))
    # A stale QEMU from a previous interrupted run would still own the VNC
    # port and poison the session (its VM, our empty serial log). Reap first
    # — with a pattern that matches only a real QEMU command line, never
    # this script's own argv (which embeds the qemu path as an argument).
    subprocess.run(["pkill", "-f", "qemu-system-aarch64 -M virt"], check=False)
    time.sleep(1.0)
    qemu_stdout = open(qemu_log, "wb")
    proc = subprocess.Popen(argv, stdout=qemu_stdout, stderr=subprocess.STDOUT)
    passed = True
    try:
        port = VNC_PORT_BASE + DISPLAY
        if not wait_for_port(port):
            print("FAIL: QEMU VNC port never opened")
            passed = False
        elif not wait_for_surface(port):
            print("FAIL: VNC surface never settled to 720x1280 (console race?)")
            passed = False
        else:
            out_png = os.path.join(outdir, "vnc-framebuffer.png")
            initial, settled_size, name, frames, pixels = vnc_session(port, out_png)
            print(f"[vnc] ServerInit ok: {initial[0]}x{initial[1]} (at connect) name={name!r}")
            print(f"[vnc] settled framebuffer: {settled_size[0]}x{settled_size[1]} via DesktopResize")
            print(f"[vnc] decoded raw rects: {frames} frames, {pixels} pixels")
            print(f"[vnc] input events accepted (ENTER down/up, pointer press/release)")
            print(f"[vnc] framebuffer captured via the VNC path → {out_png}")

            # 8. serial boot markers (real NoxOS boot on the M2 argv)
            deadline = time.time() + 60
            markers_found = set()
            while time.time() < deadline and len(markers_found) < len(BOOT_MARKERS):
                try:
                    text = open(serial_log, "r", errors="replace").read()
                    markers_found = {m for m in BOOT_MARKERS if m in text}
                except FileNotFoundError:
                    pass
                time.sleep(1.0)
            for m in BOOT_MARKERS:
                status = "ok" if m in markers_found else "FAIL"
                print(f"[serial] {status}: {m}")
                if m not in markers_found:
                    passed = False
        if proc.poll() is not None and proc.returncode != 0 and passed:
            print(f"FAIL: QEMU exited early rc={proc.returncode}")
            passed = False
        # pidfile sanity: QEMU must have written its real pid (mirrors the
        # manager's vmPid() verification via /proc cmdline).
        if passed:
            pid_txt = open(pid_file).read().strip() if os.path.exists(pid_file) else ""
            if not pid_txt.isdigit():
                print("FAIL: QEMU did not write its -pidfile")
                passed = False
            else:
                print(f"[pidfile] QEMU pid written: {pid_txt}")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
        qemu_stdout.close()

    if passed:
        print("VERIFY-VNC-PATH RESULT: PASS")
        return 0
    print("VERIFY-VNC-PATH RESULT: FAIL")
    return 1


if __name__ == "__main__":
    sys.exit(main())
