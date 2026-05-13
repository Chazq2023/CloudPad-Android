# Remote Connection

Connect to your PS4 or PS5 from outside your home network. There are two methods: **via PSN** (recommended, no router config needed) and **manual** (port forwarding, for networks where PSN holepunching fails).

---

## Remote Connection via PSN

**IPv4 only** — PlayStation only supports IPv4 for Remote Play.

Pylux uses PSN servers as a go-between to initiate a direct connection between your device and your PlayStation using [UDP hole punching](https://en.wikipedia.org/wiki/UDP_hole_punching){target="_blank" rel="noopener"}, replicating the behavior of Sony's official Remote Play app.

### Requirements

1. Console must be on the latest firmware
2. Console must be registered locally before using remote connection via PSN
3. The console must not be reachable on your local network — remote connection is only shown when the console isn't found locally
4. **PS4 only:** remote connection via PSN works only with your PSN account's main registered console *(Sony limitation, not specific to Pylux)*

!!! warning "Not All Networks Supported"

    UDP hole punching doesn't work on all network types. If you consistently see *Couldn't contact PlayStation over established connection, likely unsupported network type* after 5+ attempts, your network is unsupported and you'll need to use the [manual connection](#manual-remote-connection) instead.

    If you see *Connection over PSN failed closing ...*, please open an issue or add your logs to an existing one on the [Pylux GitHub](https://github.com/ForWard-Technologies-LLC/Pylux/issues){target="_blank" rel="noopener"}.

### Setup

1. Open Pylux and go to **Settings** (gear icon on the main page)
2. Go to the **Config** tab and click **Login to PSN**
3. A login dialog opens with two options:

    === "QR Code (recommended)"

        1. A QR code is displayed — scan it with your phone
        2. Complete the PSN sign-in on your phone
        3. Back in Pylux, click **Check Status** to confirm the login went through

    === "Login on This Device"

        Click **Login on This Device** to use a 3-step browser flow instead:

        **Step 1 — Open Login Page**

        Click **Open Login Page**. Your external browser opens the PSN login page. Sign in to your PlayStation account. After logging in, Remote Play redirects to a blank page — that's expected, just come back to Pylux.

        **Step 2 — Open NPSSO Page**

        Click **Open NPSSO Page**. Your browser opens the NPSSO page, which displays your `npsso` token value. Copy it.

        !!! tip "Can't find the token on the page?"
            You can also grab it from your browser's cookie storage: **DevTools → Application/Storage → Cookies → ca.account.sony.com → npsso**

        **Step 3 — Paste Token**

        Paste the token into the field in Pylux (or click **Paste from Clipboard**), then click **Connect**.

4. Once authenticated, Pylux refreshes the token automatically. You'll only need to repeat this if PSN requires a new one.

### Connecting

1. Make sure the console is registered (done locally) and not currently reachable on your local network
2. Your console will appear in the main list showing **Remote Connection via PSN** — click it to connect

    !!! question "Console not showing up?"

        - Confirm the console is registered locally
        - Confirm it's not reachable on your current network (e.g., you're on cellular or a different Wi-Fi)
        - Click **Refresh PSN hosts** to query PSN again

3. Wait for the connection to establish, then play

!!! tip "Testing before you leave home"

    Use a cellular hotspot to simulate a remote network and test the connection before relying on it away from home.

---

## Manual Remote Connection

Use this method if PSN-based connection doesn't work on your network.

### 1. Set a Static IP for Your Console

Reserve a static IP (DHCP reservation) for your PlayStation in your router settings so that port forwarding rules don't break if your console reconnects to the network. Search for *"DHCP reservation [your router brand]"* for router-specific instructions. Alternatively, assign a hostname to your console and use that instead of an IP.

### 2. Forward Ports

Forward the following ports for your console on your router using [portforward.com](https://portforward.com/router.htm){target="_blank" rel="noopener"} as a guide.

=== "PS5"

    | Port | Protocol |
    |------|----------|
    | 9295 | UDP/TCP  |
    | 9296 | UDP      |
    | 9297 | UDP      |
    | 9302 | UDP      |

=== "PS4"

    | Port | Protocol |
    |------|----------|
    | 987  | UDP      |
    | 9295 | UDP/TCP  |
    | 9296 | UDP      |
    | 9297 | UDP      |

### 3. Find Your Router's Public IP

On a device connected to your home network (disconnect from any VPN first):

=== "Browser"

    Visit [whatismyip.com](https://www.whatismyip.com){target="_blank" rel="noopener"} and copy the displayed IP.

=== "Terminal"

    ```bash
    curl checkip.amazonaws.com
    ```

### 4. Add the Remote Console in Pylux

1. Click the **+** icon on Pylux's main screen
2. Enter your router's public IP (or hostname) in the remote IP field
3. Select the locally registered console you want to connect to remotely
4. Click **Add**, then connect to it from the main list

### Troubleshooting

!!! question "Port forwarding set up correctly but still not connecting?"

    You may be behind [CGNAT (carrier-grade NAT)](https://en.wikipedia.org/wiki/Carrier-grade_NAT){target="_blank" rel="noopener"}, where the router you need to forward ports on is owned by your ISP and inaccessible. In this case:

    - Try the **PSN-based remote connection** — it can still work even with CGNAT on compatible network types
    - If that also fails, you'll need to set up a VPN on your home network (e.g., WireGuard on a Raspberry Pi) to tunnel the connection

!!! question "Connection was working but stopped?"

    Check whether your router's public IP has changed by re-running the IP lookup above. If it has, update the IP in Pylux's remote connection entry.
