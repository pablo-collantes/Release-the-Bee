# Getting audio from your phone to your machine

The app doesn't know or care what network it uses. You give it a URL, and anything that
lets the phone reach your receiver works. Pick the row that matches your setup.

| Setup | App destination | Works away from home | Open ports | Notes |
|---|---|---|---|---|
| [Tailscale / Headscale](#tailscale-or-headscale) | `ws://100.x.y.z:8740/` | yes | none | easiest |
| [WireGuard, OpenVPN, ZeroTier, Nebula](#other-vpns) | `ws://<vpn-ip>:8740/` | yes | your VPN's | |
| [Same Wi-Fi](#same-wi-fi) | `ws://192.168.1.20:8740/` | no | none | add "keep a copy on this phone" |
| [Cloudflare Tunnel](#cloudflare-tunnel) | `wss://rtb.example.com/` | yes | none | needs a domain on Cloudflare |
| [Your own reverse proxy](#reverse-proxy) | `wss://rtb.example.com/` | yes | 443 | Caddy, nginx, Traefik… |
| Cloudflare relay | `wss://…workers.dev/…` | yes | none | **planned**, see below |
| [Raw TCP](#raw-tcp) | `host` + `port` | depends | — | no auth, no metadata |

Inside a VPN tunnel, `ws://` is fine: the tunnel already encrypts the traffic. Use
`wss://` plus a token whenever the path crosses the open internet.

Start the receiver on your machine in every case:

```sh
python3 receiver/rtb_receiver.py --token "$(openssl rand -hex 16)"
```

Put the same token in the app's *Bearer token* field.

## Tailscale or Headscale

1. Install Tailscale on the phone and the machine, signed in to the same tailnet.
2. Find the machine's address: `tailscale ip -4`, or use its MagicDNS name.
3. Optionally bind the receiver to the tailnet only: `--ws 100.x.y.z:8740`.
4. In the app: `ws://100.x.y.z:8740/` (or `ws://my-machine:8740/`).

In Android's VPN settings, turn on *Always-on VPN* for Tailscale so the path survives
reboots.

## Other VPNs

WireGuard, OpenVPN, ZeroTier, Nebula and similar all work the same way. Use the
machine's address inside the VPN. Make sure that:

- the VPN is *Always-on* on the phone, and
- split tunnelling (if you use it) includes Release the Bee, or routes the receiver's
  subnet.

## Same Wi-Fi

Use the machine's LAN IP. The receiver prints candidate URLs when it starts. When the
phone leaves home the receiver is unreachable: the app buffers about 15 minutes, then
drops the oldest audio. Turn on **Also keep a copy on this phone** so nothing is lost.

## Cloudflare Tunnel

No open ports and no VPN app on the phone, but you need a domain on Cloudflare.

```sh
cloudflared tunnel login
cloudflared tunnel create rtb
cloudflared tunnel route dns rtb rtb.example.com
cloudflared tunnel run --url http://localhost:8740 rtb
```

Run the receiver bound to localhost, with a token:
`python3 receiver/rtb_receiver.py --ws 127.0.0.1:8740 --token …`. In the app, use
`wss://rtb.example.com/` and the same token. Tunnels pass WebSockets through unchanged.

Cloudflare Access service tokens need custom headers, which the app doesn't send yet.
Use the bearer token for now.

## Reverse proxy

Any TLS-terminating proxy that passes WebSocket upgrades works. With Caddy:

```
rtb.example.com {
    reverse_proxy 127.0.0.1:8740
}
```

## Cloudflare relay (planned)

A Worker plus a Durable Object that both sides dial out to: the phone sends to
`wss://<worker>/ingest/<channel>`, and the receiver connects to
`wss://<worker>/listen/<channel>`. It would need no domain (a `workers.dev` subdomain is
enough), no open ports, and no VPN. The phone side needs no changes, because it's
another `wss://` URL. Contributions welcome.

## Raw TCP

For the simplest possible pipe on a trusted network:

```sh
nc -l 8741 > rtb.aac                                   # or:
python3 receiver/rtb_receiver.py --ws '' --tcp 0.0.0.0:8741
```

In the app, choose *Raw TCP* and enter the host and port. You get bare AAC: no
sessions, no timestamps, no authentication.
