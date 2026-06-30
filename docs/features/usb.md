---
layout: default
title: USB device forwarding
---

# USB device forwarding

On an unrooted Android phone, only the app holding the runtime USB permission can
open a device — `/dev/bus/usb` is denied to both the proot guest and the adb
shell. Haven turns that into a feature: it **brokers** the attached USB/OTG
device through Android's `UsbManager` and re-exposes it three ways, with no root.
This is the same pattern as the rest of Haven's bridge work — when something
downstream can't reach a capability directly, Haven brokers the Android privilege
and re-exposes it (see [Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md)).

The spark was a **YubiKey**: plug it into the phone and use it for SSH/FIDO on a
machine that has no key of its own, with the **touch happening on the phone**.

## Device-class support

What reaches each consumer depends on the device class. **Guest** = the local
proot Linux guest, via the `haven-usb` shim; **Remote** = a Linux host over
USB/IP. (✅ works · ⚠️ feasible but not yet built · ❌ not feasible by this route.)

| Device class | Guest (proot) | Remote (USB/IP) | Notes |
|---|:---:|:---:|---|
| HID — keyboards, FIDO/CTAPHID security keys | ✅ `hidraw` shim | ✅ | Verified: YubiKey FIDO/SSH |
| CDC-ACM serial — USB-serial, ESP32 | ✅ serial shim | ✅ | Verified: ESP32-S3 `esptool` |
| Other bulk/control — printers, scanners, SDR, MTP/PTP cameras, `fastboot` | ⚠️ needs a libusb/usbfs shim | ✅ | Userspace-driven; see [Extending guest support](#extending-guest-support) |
| Mass storage — flash drives | ❌ no block layer | ✅ real kernel | Guest: read files via Android mount + `/storage` |
| Network — RNDIS / CDC-ECM / NCM | ❌ no netdev | ✅ | |
| Webcam (UVC) / Audio (UAC) | ❌ | ❌ | **Isochronous** — no Android `UsbDeviceConnection` API |

### Why these limits

The broker moves **control and bulk/interrupt** transfers over Android's
`UsbDeviceConnection` — that's all the Android API exposes. Two hard walls follow:

- **Kernel-object classes.** Mass storage (`/dev/sd*` + `mount`), network
  (a netdev), webcams (`/dev/video`), and audio (ALSA) need a kernel **class
  driver** to materialise a kernel object. proot has no kernel, so they can't
  appear in the guest. The **remote USB/IP** path sidesteps this — it attaches
  the device to a *real* Linux kernel, so any non-isochronous class works there.
  For mass storage specifically, the **on-device VM** route does the same thing
  locally — see [Reading USB drives](usb-drives.md).
- **Isochronous transfers.** `UsbDeviceConnection` has no isochronous API, so
  UVC webcams and UAC audio — which live on isochronous endpoints — can't be
  bridged on *either* path, guest or remote.

### Extending guest support

Today the guest shim emulates two specific char devices (`hidraw`, serial). The
general next step is a **libusb / usbfs shim** — emulate `/dev/bus/usb/*` (or
interpose libusb) and route its URBs over the same proxy. `UsbDeviceConnection`
already provides exactly libusb's primitives (descriptors, `claimInterface`,
control, bulk), so one shim would unlock **any userspace-driven class** —
printers (CUPS), scanners (SANE), SDR, MTP/PTP (libgphoto2/libmtp), `fastboot` —
with no per-class code. It does **not** move the two walls above: kernel-object
classes still need the remote path, and isochronous still has no Android API.

## To the agent (MCP)

`list_usb_devices`, `request_usb_permission`, `usb_control_transfer`, and
`usb_bulk_transfer` let an AI agent enumerate the attached USB/OTG devices and run
raw control/bulk transfers. Each device-open and transfer asks for consent.

## To the local Linux guest

With **Settings → "Expose USB devices to the Linux guest"** turned on (off by
default), `usb_attach_to_guest` opens the device and binds a small userspace USB
proxy on a socket the proot guest can reach. A bundled `haven-usb` shim then makes
the device appear inside the guest as a **character** device — for a HID-class
device, a normal `/dev/hidraw*` node (CDC-serial devices surface as a serial
endpoint instead):

- **native Linux apps** via `LD_PRELOAD` (the shim interposes
  `open`/`ioctl`/`read`/`write`);
- **Mono / .NET apps** (anything built on HidSharp) via a Mono **DllMap** config —
  the shim fakes the `libudev` enumeration and a non-crashing hotplug monitor and
  routes the HID I/O over the proxy. `usb_attach_to_guest` returns the
  ready-to-use config to drop beside the app's `HidSharp.dll`.

All USB I/O stays in the Android layer (`UsbDeviceConnection`), so the guest never
needs a real device node or root. The shim is built for both glibc and musl, so it
works the same on every distro Haven offers.

> **Not block storage.** A USB drive (mass-storage class) can't appear as a disk
> here, and `fdisk` isn't possible — see [Device-class support](#device-class-support)
> for why. To use a drive's *files*, either let Android mount it (USB-OTG storage)
> and read/write them under `/storage` (bound into every guest), or — for a
> Linux-formatted drive (ext4/GPT) Android can't open — use
> [Reading USB drives](usb-drives.md), which mounts it in a small on-device VM
> that *does* have a kernel. Partitioning is still host-side.

## To a remote host over USB/IP

Haven can also export a phone-attached USB device over the network with a
**userspace USB/IP server**, so a stock `usbip attach` on a remote Linux host
imports it as a real local device node. Every program there (`ssh`, `libfido2`,
browsers) sees an ordinary USB device; the **touch still happens on the phone**.

> Why a *userspace* server, and why remote-only: the Android kernel ships no
> `usbip-host`/`vhci-hcd` modules, so Haven can't use the stock `usbipd` daemon
> and the proot guest can't be a USB/IP *client*. Haven reimplements the USB/IP
> server in userspace and bridges each URB onto `UsbDeviceConnection` transfers.
> The remote host (a normal Linux box with `vhci-hcd` + `usbip`) is the client.

### Use case — a phone-hosted YubiKey for a remote host

Forward the phone's YubiKey to a server and mint a hardware-backed SSH key there:

```bash
# on the remote host, with the key forwarded and attached (see below):
ssh-keygen -t ed25519-sk -O device=/dev/hidraw14 -O application=ssh:example
```

The `makeCredential` and assertion run on the phone's key; you press the gold
contact on the phone to authorise.

### Auto-forward on connect (per profile)

In an SSH connection's editor there's a **"USB device forwarding"** picker
(below *Post-login command*). Pick the device — it's pinned by VID:PID, so it
survives a replug — and Haven, every time that profile connects:

1. resolves the VID:PID to the live device and opens it (USB permission prompt
   the first time),
2. starts the USB/IP server on the phone's loopback,
3. binds a remote `-R 3240` port-forward so the host reaches the phone's server
   at its own `127.0.0.1:3240`, and
4. best-effort runs `usbip attach` on the host so the device comes up as a real
   node (e.g. `/dev/hidraw14`).

On disconnect it tears the forward down (which detaches the remote device as the
socket closes), stops the server, and closes the device.

### Hands-off attach (host sudoers)

Step 4 runs `sudo usbip attach` on the host, so for a fully automatic attach the
host needs passwordless sudo for just those commands. Drop this in
`/etc/sudoers.d/haven-usbip` (replace `ian` with your user; validate with
`sudo visudo -cf <file>` before installing):

```
Cmnd_Alias HAVEN_USBIP = /usr/sbin/modprobe vhci_hcd, \
                         /usr/bin/usbip attach *, \
                         /usr/bin/usbip detach *, \
                         /usr/bin/usbip port

ian ALL=(root) NOPASSWD: HAVEN_USBIP
```

It grants passwordless sudo for **only** those four commands — not general root.
`usbip` and `modprobe vhci_hcd` have a small blast radius (attach a remote USB
device / load one fixed module). Paths may differ on your distro (`command -v
usbip modprobe`).

### Without the sudoers — manual attach

If the host has no passwordless sudo for `usbip`, the connect still brings the
export and the tunnel up; it just can't attach for you. Haven logs the exact
command to run on the host:

```bash
sudo bash -c 'modprobe vhci_hcd; usbip attach -r 127.0.0.1 -b <busid>'
```

(`<busid>` is shown in the log, e.g. `1-2`.) Detach again with `sudo usbip detach
-p <port>`, or just disconnect the Haven profile.

### Caveats

- **`ssh-keygen` needs `-O device=/dev/hidrawN`.** OpenSSH's `sk-helper` does a
  multi-device "touch to select" probe when no device is named, and that probe
  stalls over the bridge. Naming the device skips it. `fido2-cred -M
  /dev/hidrawN` and `fido2-token` work without the flag.
- **Verified for FIDO/CTAPHID (YubiKey) and CDC serial (ESP32-S3
  USB-Serial/JTAG).** A composite FIDO key exports interface 0 only — its CCID
  interface would starve FIDO on Android's serialized connection — but every
  other device exports all its interfaces, so a USB-serial adapter binds
  `cdc_acm` on the host and `esptool flash_id` connects, uploads the stub, and
  reads flash over the bridge. Other device classes are still untested.
- **Remote-only.** This path needs the host's `vhci-hcd` kernel module; it's not
  a proot-guest path (the guest uses the broker above).

### Security

Every device-open is consent-gated, and the sudoers grant is narrowly scoped to
the attach/detach/port/modprobe commands — never general root. Nothing in the
USB/IP path bypasses Android's USB-permission model.

---

[← All features](../FEATURES.md) · [Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md)
