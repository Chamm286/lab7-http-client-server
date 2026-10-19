# Lab 7 — HTTP Server / Client

Java HTTP server and browser client for demonstrating HTTP over TCP/IP, file transfer, service endpoints, persistent file storage, and access control.

## Run the separate JavaFX applications

The server and client are separate programs with focused interfaces:

- Start `ServerApp` (VS Code: **Run Server UI**) on the host computer. Configure the TCP port and shared document root, then start the server. Share the displayed LAN URL with the client; share the write token only if the client needs protected methods.
- Start `ClientApp` (VS Code: **Run Client UI**) on the client computer. Enter the server's LAN URL in the HTTP Client tab and send requests. The client has no local server controls and does not start a server.
- For a one-computer demonstration, `App` (VS Code: **Run Combined Demo**) retains the combined interface.

For a two-computer demo, both computers must be on the same LAN/Wi-Fi and the server computer's firewall must allow the selected TCP port. Do not enter `localhost` in the client on a different computer; use the server's displayed LAN URL.

The browser client is served at `/` from the server. Its **HTTP Request** view supports the assignment's three methods. Enter a full URL (or a path on the current host); the browser page is restricted to the server's own origin by browser CORS rules. GET and POST parse HTML responses and show the received byte length, decoded UTF-16 character count, and counts of `<p>`, `<div>`, `<span>`, and standard `<img>` tags. HEAD shows the status and resource metadata without a response body. The separate JavaFX Client accepts full `http://` and `https://` URLs for requests to other hosts. File upload/download management is kept separate from the browser request-method selector.

The JavaFX **Server UI** provides the local administration workbench: TCP port/document-root controls, start/stop actions, LAN URL and write-token copy actions, a live uptime and `/__health` check, request counters, request filtering, runtime logs with export, and a searchable-by-selection view of shared/uploaded resources. The hosted Render service is headless; its internal JavaFX administration window is not exposed publicly.

## Run the server without the JavaFX desktop UI

Install a JDK (21 or newer), then from the project directory run:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot"
.\run-server.bat 8080 www
```

The server binds to all network interfaces. On the server computer, open the printed loopback/LAN URL. On another computer on the same network, open `http://<server-LAN-IPv4>:8080/`. Allow the selected TCP port through the server computer's firewall. The generated `WRITE_TOKEN` is printed once at startup; enter it in the client page or share it with a trusted client only. Restarting the server creates a new token.

For a server host that provides a `PORT` environment variable:

```text
java -cp out server.ServerMain
```

An optional first argument overrides the port and a second argument selects the document root. `PORT` and `LAB7_ROOT` are used when arguments are omitted.

## Publish a demo URL with Render

The project includes a Docker setup for a hosted server. The container serves the browser client and HTTP endpoints on the host-provided `PORT`; `/__health` is available as a health-check path. The Docker image includes only `www/index.html`, not files from your local `www\uploads` folder.

1. Create a GitHub repository and push the project source. Do not add `www\uploads` or `lab7-data`; `.gitignore` excludes these local files.
2. Create a **Web Service** in Render and connect that repository. Choose **Docker** as the runtime and the repository-root `Dockerfile`.
3. Set the health-check path to `/__health`, then deploy. Render will provide a public `https://...onrender.com` address; open it to show the hosted Client and Server running together.
4. For uploaded files and audit history to survive restarts/redeploys, attach a persistent disk mounted at `/var/data`. Without a disk, these files are temporary. Render's free web services can sleep when idle and local file changes are lost; persistent disks require a paid service.
5. The write token is generated on startup and printed in the service logs as `WRITE_TOKEN=...`. Keep it private; enter it in the page only when demonstrating protected POST/upload/audit actions. A restart can generate a new token.

The Web Client hosted on this address can request this same server. Browser CORS rules still prevent it from acting as an unrestricted proxy to arbitrary websites. Use the separate JavaFX Client to try other HTTP/HTTPS URLs. A public service account and its deployment are not created automatically from this project.

## Services on the same host and port

| Method and path | Purpose | Permission |
| --- | --- | --- |
| `GET /` and `GET /<file>` | Serve the web client and public files | Public |
| `GET /__files` | List files in the document root | Public |
| `GET /__health` | JSON service status and uptime | Public |
| `GET /__audit` | Read the latest persisted request events | Bearer token |
| `POST /__submit` | Receive and display a URL-encoded form | Bearer token |
| `POST /__upload` | Create an uploaded file, using `X-File-Name` | Bearer token |
| `PUT /uploads/<name>` | Create or replace an uploaded file | Bearer token |
| `DELETE /uploads/<name>` | Delete an uploaded file | Bearer token |
| `HEAD` / `OPTIONS` | Inspect resource headers / allowed methods (`HEAD /__audit` is protected) | See endpoint permission |

Every request uses a TCP connection. Responses include an HTTP status, headers, and (except for `HEAD`) a response body. Unsupported methods return `501`; unsupported operations for a resource return `405` with `Allow`; missing files return `404`; missing/incorrect credentials return `401`; invalid paths are rejected with `400` or `403`; oversized request bodies return `413`.

If the operating system denies a file write/delete or the disk is full, the server returns `500 Internal Server Error` and records the failure in the server log. Failure to read the protected audit store is also returned as `500`; audit write failures are reported in the server log and do not expose the log directory to clients.

## Persistent storage

Uploaded files are stored under `www\uploads` (or `<document-root>\uploads` when a different root is selected). Request audit entries are stored outside the public document root in the sibling `lab7-data\audit.jsonl` and `lab7-data\audit.previous.jsonl` files. The audit is capped by rotating the active log at 5 MiB; it records time, client address, method, path, status, duration, and response bytes, but never records authorization headers or request bodies.

## Where to get the token

- JavaFX app: start the server, then copy the value shown in the left-side `TOKEN QUYỀN GHI` field. Use **Sao chép token cho client** to copy it.
- Standalone server: copy the value printed after `WRITE_TOKEN=` in the server console.
- Client: paste it into `QUYỀN GHI / AUDIT` (JavaFX Client tab) or `BEARER TOKEN` (browser page). The browser client keeps it only in the current page memory and provides show/hide and clear controls.

## Security and demo notes

- File reads are confined to the configured document root; writes and deletes are confined to `uploads`.
- File writes, deletes, and audit reads require the random per-run Bearer token. `GET /__health` and ordinary file reads remain public.
- Upload request bodies are limited to 50 MiB, and each client socket has a 15-second read timeout.
- The built-in server uses plain HTTP. For a public classroom demo, use the host's HTTPS endpoint/reverse proxy, protect the token, and avoid uploading private files. The Docker setup is ready to deploy, but a hosting account and deployment must be provisioned separately.

## Error and permission demonstrations

1. `GET /missing.txt` → `404 Not Found`.
2. `POST /__submit` without the token → `401 Unauthorized`.
3. `PUT /` → `405 Method Not Allowed` with an `Allow` header.
4. `GET /__health` → `200 OK` with service JSON.
5. `GET /__audit` without the token → `401`; enter the token and retry to inspect persisted events.
