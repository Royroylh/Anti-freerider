package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Local HTTP server: JSON API, static dark UI, sessions, and a reminder ticker. */
public final class Server {
    private static Path distDir;
    private static final AtomicLong REQUEST_IDS = new AtomicLong();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("af.root", ".")).toAbsolutePath().normalize();
        Path dataDir = Path.of(System.getenv().getOrDefault("AF_DATA_DIR",
                root.resolve("data").toString())).toAbsolutePath().normalize();
        distDir = Path.of(System.getenv().getOrDefault("AF_DIST_DIR",
                root.resolve("dist").toString())).toAbsolutePath().normalize();
        int port = Integer.parseInt(System.getenv().getOrDefault("AF_PORT", "8765"));
        Files.createDirectories(dataDir);
        Db.init(dataDir.resolve("af.db"));
        Auth.purgeExpiredSessions();
        int resolved = Reminders.resolveAcceptedReminders();
        if (resolved > 0) System.out.println("[reminders] closed " + resolved + " reminder(s) for already accepted tasks");
        boolean seedDemo = System.getenv("AF_SEED_DEMO") != null || List.of(args).contains("--seed-demo");
        if (seedDemo) Seed.seed();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", Server::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("[server] Anti-freerider v1 listening on http://127.0.0.1:" + port + "/");
        System.out.println("[server] data: " + dataDir.resolve("af.db"));
        System.out.println("[server] static: " + distDir);
        System.out.println("[server] AI mode: " + Evidence.status().get("label").getAsString());
        if (System.getenv("AF_CLOCK_FILE") != null) {
            System.out.println("[server] test clock file: " + System.getenv("AF_CLOCK_FILE"));
        }

        Thread ticker = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(30_000);
                    int changes = Reminders.evaluateAll();
                    if (changes > 0) System.out.println("[reminders] applied " + changes + " checkpoint transition(s)");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    System.err.println("[reminders] tick failed: " + ex.getMessage());
                }
            }
        }, "af-reminder-ticker");
        ticker.setDaemon(true);
        ticker.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    private static void handle(HttpExchange ex) throws IOException {
        long requestId = REQUEST_IDS.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        try {
            securityHeaders(ex);
            if (path.startsWith("/api/")) {
                handleApi(ex, path);
            } else {
                handleStatic(ex, path);
            }
        } catch (ApiException e) {
            if (path.startsWith("/api/")) json(ex, e.status, error(e.getMessage()));
            else text(ex, e.status, e.getMessage());
        } catch (Exception e) {
            System.err.println("[error] request " + requestId + " " + ex.getRequestMethod() + " " + path + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (path.startsWith("/api/")) json(ex, 500, error("Unexpected server error"));
            else text(ex, 500, "Unexpected server error");
        } finally {
            // Path only: bodies and query strings never enter the application log.
            System.out.println("[req " + requestId + "] " + ex.getRequestMethod() + " " + path);
            ex.close();
        }
    }

    private static void handleApi(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        if (!"GET".equals(method) && !"POST".equals(method) && !"HEAD".equals(method)) {
            throw new ApiException(405, "Method not allowed");
        }
        if ("POST".equals(method)) checkOrigin(ex);
        if (path.equals("/api/health")) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("time", Clock.nowIso());
            json(ex, 200, o);
            return;
        }
        if (path.equals("/api/auth/login") && "POST".equals(method)) {
            login(ex);
            return;
        }
        if (path.equals("/api/auth/register") && "POST".equals(method)) {
            register(ex);
            return;
        }
        String token = cookie(ex, "af_session");
        Auth.User user = Auth.sessionUser(token);
        if (user == null) {
            throw new ApiException(401, "Sign in to continue");
        }
        if (path.equals("/api/me") && "GET".equals(method)) {
            JsonObject o = new JsonObject();
            o.add("user", user.json());
            json(ex, 200, o);
            return;
        }
        if (path.equals("/api/auth/logout") && "POST".equals(method)) {
            Auth.destroySession(token);
            expireCookie(ex);
            json(ex, 200, new JsonObject());
            return;
        }
        if (path.equals("/api/projects") && "GET".equals(method)) {
            JsonObject o = new JsonObject();
            o.add("projects", Projects.list(user.id()));
            json(ex, 200, o);
            return;
        }
        if (path.equals("/api/projects") && "POST".equals(method)) {
            json(ex, 200, Projects.create(user, readJson(ex)));
            return;
        }
        if (path.equals("/api/projects/join") && "POST".equals(method)) {
            json(ex, 200, Projects.join(user, readJson(ex)));
            return;
        }

        String[] p = path.split("/");
        if (p.length == 4 && "invites".equals(p[2]) && "GET".equals(method)) {
            json(ex, 200, Projects.previewInvite(user, p[3]));
            return;
        }
        // ["", "api", "projects", "{pid}", ...]
        if (p.length < 4 || !"projects".equals(p[2])) throw new ApiException(404, "Not found");
        int projectId = parseInt(p[3], "project id");
        if (p.length == 4 && "GET".equals(method)) {
            json(ex, 200, Projects.get(user, projectId));
            return;
        }
        if (p.length == 5 && "board".equals(p[4]) && "GET".equals(method)) {
            json(ex, 200, Projects.board(user, projectId));
            return;
        }
        if (p.length == 5 && "branches".equals(p[4]) && "POST".equals(method)) {
            json(ex, 200, Projects.addBranch(user, projectId, readJson(ex)));
            return;
        }
        if (p.length == 5 && "plan".equals(p[4]) && "POST".equals(method)) {
            json(ex, 200, Projects.revisePlan(user, projectId, readJson(ex)));
            return;
        }
        if (p.length == 5 && "leave".equals(p[4]) && "POST".equals(method)) {
            json(ex, 200, Projects.leave(user, projectId));
            return;
        }
        if (p.length == 5 && "messages".equals(p[4])) {
            if ("GET".equals(method)) {
                JsonObject o = new JsonObject();
                o.add("messages", Projects.messages(user, projectId));
                json(ex, 200, o);
            }
            else if ("POST".equals(method)) json(ex, 200, Projects.postMessage(user, projectId, readJson(ex)));
            else throw new ApiException(405, "Method not allowed");
            return;
        }
        if (p.length == 5 && "ai".equals(p[4])) {
            if ("GET".equals(method)) {
                json(ex, 200, Evidence.status());
            } else if ("POST".equals(method)) {
                JsonObject body = readJson(ex);
                json(ex, 200, Evidence.ask(user, projectId, Util.str(body, "question"),
                        Util.integer(body, "taskId", 0, 0, Integer.MAX_VALUE),
                        Util.integer(body, "submissionId", 0, 0, Integer.MAX_VALUE)));
            } else {
                throw new ApiException(405, "Method not allowed");
            }
            return;
        }
        if (p.length == 6 && "ai".equals(p[4]) && "status".equals(p[5]) && "GET".equals(method)) {
            json(ex, 200, Evidence.status());
            return;
        }
        if (p.length == 5 && "report".equals(p[4]) && "GET".equals(method)) {
            Map<String, String> q = query(ex);
            json(ex, 200, Reports.buildReportData(projectId, user.id(), flag(q, "tasks"),
                    flag(q, "timeline"), flag(q, "disputes"), flag(q, "reminders")));
            return;
        }
        if (p.length == 6 && "report".equals(p[4]) && "export".equals(p[5]) && "GET".equals(method)) {
            Map<String, String> q = query(ex);
            String html = Reports.exportHtml(projectId, user.id(), flag(q, "tasks"),
                    flag(q, "timeline"), flag(q, "disputes"), flag(q, "reminders"));
            String projectName;
            try {
                projectName = Projects.get(user, projectId).get("name").getAsString();
            } catch (Exception ex2) {
                projectName = "project";
            }
            Headers headers = ex.getResponseHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            headers.set("Content-Disposition", "attachment; filename=\""
                    + Util.safeFilename(projectName) + "-closing-report.html\"");
            headers.set("Cache-Control", "no-store");
            bytes(ex, 200, html.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (p.length == 5 && "timeline".equals(p[4]) && "GET".equals(method)) {
            JsonObject board = Projects.board(user, projectId);
            JsonArray timeline = board.getAsJsonArray("timeline");
            Map<String, String> q = query(ex);
            String branch = q.get("branchId");
            if (branch != null && !branch.isBlank()) {
                int branchId = parseInt(branch, "branch id");
                JsonArray filtered = new JsonArray();
                for (var e : timeline) {
                    if (e.getAsJsonObject().get("branchId").getAsInt() == branchId) filtered.add(e);
                }
                timeline = filtered;
            }
            JsonObject o = new JsonObject();
            o.add("timeline", timeline);
            json(ex, 200, o);
            return;
        }
        if (p.length == 5 && "reports".equals(p[4])) {
            if ("GET".equals(method)) {
                JsonObject o = new JsonObject();
                try (var c = Db.open()) {
                    Auth.requireMember(c, projectId, user.id());
                    o.add("reports", Reports.projectSummaries(c, projectId, user.id()));
                }
                json(ex, 200, o);
            } else if ("POST".equals(method)) {
                JsonObject body = readJson(ex);
                int taskId = Util.integer(body, "taskId", -1, -1, Integer.MAX_VALUE);
                json(ex, 200, Reports.create(user, projectId, taskId, body));
            } else {
                throw new ApiException(405, "Method not allowed");
            }
            return;
        }
        if (p.length == 6 && "reports".equals(p[4]) && "GET".equals(method)) {
            int reportId = parseInt(p[5], "report id");
            try (var c = Db.open()) {
                json(ex, 200, Reports.reportDetail(c, projectId, reportId, user.id()));
            }
            return;
        }
        if (p.length == 7 && "reports".equals(p[4])) {
            int reportId = parseInt(p[5], "report id");
            if ("respond".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Reports.respond(user, projectId, reportId, readJson(ex)));
                return;
            }
            if ("review".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Reports.review(user, projectId, reportId, readJson(ex)));
                return;
            }
            if ("close".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Reports.close(user, projectId, reportId));
                return;
            }
        }
        if (p.length == 5 && "tasks".equals(p[4]) && "POST".equals(method)) {
            json(ex, 200, Tasks.create(user, projectId, readJson(ex)));
            return;
        }
        if (p.length >= 6 && "tasks".equals(p[4])) {
            int taskId = parseInt(p[5], "task id");
            if (p.length == 6 && "GET".equals(method)) {
                json(ex, 200, Tasks.detail(user, projectId, taskId));
                return;
            }
            if (p.length == 7 && "submissions".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Tasks.submit(user, projectId, taskId, readJson(ex)));
                return;
            }
            if (p.length == 9 && "submissions".equals(p[6])) {
                int submissionId = parseInt(p[7], "submission id");
                if ("withdraw".equals(p[8]) && "POST".equals(method)) {
                    json(ex, 200, Tasks.withdraw(user, projectId, taskId, submissionId, readJson(ex)));
                    return;
                }
                if ("attachment".equals(p[8]) && ("GET".equals(method) || "HEAD".equals(method))) {
                    Tasks.Attachment attachment = Tasks.attachment(user, projectId, taskId, submissionId);
                    Headers headers = ex.getResponseHeaders();
                    headers.set("Content-Type", "application/pdf");
                    headers.set("Content-Disposition", "inline; filename=\"report.pdf\"; filename*=UTF-8''"
                            + java.net.URLEncoder.encode(attachment.filename(), StandardCharsets.UTF_8).replace("+", "%20"));
                    headers.set("Cache-Control", "private, no-store");
                    if ("HEAD".equals(method)) {
                        headers.set("Content-Length", Integer.toString(attachment.pdf().length));
                        ex.sendResponseHeaders(200, -1);
                    } else {
                        bytes(ex, 200, attachment.pdf());
                    }
                    return;
                }
                if ("confirm".equals(p[8]) && "POST".equals(method)) {
                    json(ex, 200, Tasks.confirm(user, projectId, taskId, submissionId));
                    return;
                }
                if ("vote".equals(p[8]) && "POST".equals(method)) {
                    json(ex, 200, Tasks.vote(user, projectId, taskId, submissionId, readJson(ex)));
                    return;
                }
            }
            if (p.length == 7 && "support".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Tasks.addSupport(user, projectId, taskId, readJson(ex)));
                return;
            }
            if (p.length == 9 && "support".equals(p[6]) && "resolve".equals(p[8]) && "POST".equals(method)) {
                int supportId = parseInt(p[7], "support id");
                json(ex, 200, Tasks.resolveSupport(user, projectId, taskId, supportId));
                return;
            }
            if (p.length == 7 && "note".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Tasks.note(user, projectId, taskId, readJson(ex)));
                return;
            }
            if (p.length == 7 && "extension".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Tasks.requestExtension(user, projectId, taskId, readJson(ex)));
                return;
            }
            if (p.length == 9 && "extension".equals(p[6]) && "decide".equals(p[8]) && "POST".equals(method)) {
                int changeId = parseInt(p[7], "extension id");
                json(ex, 200, Tasks.decideExtension(user, projectId, taskId, changeId, readJson(ex)));
                return;
            }
            if (p.length == 7 && "authors".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Tasks.editAuthors(user, projectId, taskId, readJson(ex)));
                return;
            }
            if (p.length == 7 && "reassign".equals(p[6]) && "POST".equals(method)) {
                json(ex, 200, Tasks.reassign(user, projectId, taskId, readJson(ex)));
                return;
            }
        }
        throw new ApiException(404, "Not found");
    }

    private static void login(HttpExchange ex) throws Exception {
        JsonObject body = readJsonOrForm(ex);
        String email = Util.str(body, "email").trim();
        String password = Util.str(body, "password");
        boolean remember = Util.bool(body, "remember", false);
        Auth.User user = Auth.login(email, password);
        if (user == null) throw new ApiException(401, "Email or password is incorrect");
        String token = Auth.createSession(user.id(), remember);
        setCookie(ex, token, remember);
        if (isForm(ex)) {
            redirect(ex, "/dashboard.html");
            return;
        }
        JsonObject o = new JsonObject();
        o.add("user", user.json());
        json(ex, 200, o);
    }

    private static void register(HttpExchange ex) throws Exception {
        JsonObject body = readJsonOrForm(ex);
        Auth.User user = Auth.register(Util.str(body, "name"), Util.str(body, "email"), Util.str(body, "password"));
        boolean remember = Util.bool(body, "remember", true);
        String token = Auth.createSession(user.id(), remember);
        setCookie(ex, token, remember);
        if (isForm(ex)) {
            redirect(ex, "/dashboard.html");
            return;
        }
        JsonObject o = new JsonObject();
        o.add("user", user.json());
        json(ex, 200, o);
    }

    private static void handleStatic(HttpExchange ex, String path) throws Exception {
        if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
            throw new ApiException(405, "Method not allowed");
        }
        String token = cookie(ex, "af_session");
        Auth.User user = Auth.sessionUser(token);
        if (path.equals("/") || path.equals("/index.html")) {
            if (user != null) {
                String rawQuery = ex.getRequestURI().getRawQuery();
                redirect(ex, "/dashboard.html" + (rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery));
                return;
            }
            serveFile(ex, distDir.resolve("index.html"));
            return;
        }
        if (path.equals("/dashboard.html")) {
            if (user == null) {
                redirect(ex, "/");
                return;
            }
            serveFile(ex, distDir.resolve("dashboard.html"));
            return;
        }
        if (path.equals("/styles.css") || path.equals("/dashboard.css")) {
            serveFile(ex, distDir.resolve(path.substring(1)));
            return;
        }
        if (path.startsWith("/assets/")) {
            Path base = distDir.resolve("assets").normalize();
            Path file = distDir.resolve(path.substring(1)).normalize();
            if (!file.startsWith(base)) throw new ApiException(404, "Not found");
            serveFile(ex, file);
            return;
        }
        throw new ApiException(404, "Not found");
    }

    private static void serveFile(HttpExchange ex, Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new ApiException(404, "Not found");
        String name = file.getFileName().toString();
        String type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            default -> "application/octet-stream";
        };
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", type);
        headers.set("Cache-Control", "no-store");
        byte[] body = Files.readAllBytes(file);
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(200, -1);
            return;
        }
        bytes(ex, 200, body);
    }

    private static JsonObject readJson(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body.isBlank()) return new JsonObject();
        try {
            return Util.parseObject(body);
        } catch (Exception e) {
            throw new ApiException(400, "Invalid JSON body");
        }
    }

    private static JsonObject readJsonOrForm(HttpExchange ex) throws IOException {
        if (isForm(ex)) {
            Map<String, String> form = Util.parseForm(readBody(ex));
            JsonObject o = new JsonObject();
            for (var e : form.entrySet()) o.addProperty(e.getKey(), e.getValue());
            return o;
        }
        return readJson(ex);
    }

    private static boolean isForm(HttpExchange ex) {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        return type != null && type.toLowerCase().contains("application/x-www-form-urlencoded");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length) > 2_000_000) throw new ApiException(413, "Request body too large");
            } catch (NumberFormatException ignored) {
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] data = in.readNBytes(2_000_001);
            if (data.length > 2_000_000) throw new ApiException(413, "Request body too large");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static void json(HttpExchange ex, int status, JsonObject body) throws IOException {
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        bytes(ex, status, Util.GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void text(HttpExchange ex, int status, String body) throws IOException {
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        bytes(ex, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        Headers headers = ex.getResponseHeaders();
        headers.set("Location", location);
        headers.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(303, -1);
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }

    private static void securityHeaders(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; "
                + "script-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    }

    private static void checkOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return;
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null) throw new ApiException(403, "Missing Host");
        if (!origin.equals("http://" + host) && !origin.equals("https://" + host)) {
            throw new ApiException(403, "Cross-origin write refused");
        }
    }

    private static String cookie(HttpExchange ex, String name) {
        List<String> headers = ex.getRequestHeaders().get("Cookie");
        if (headers == null) return null;
        for (String header : headers) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) return kv[1];
            }
        }
        return null;
    }

    private static void setCookie(HttpExchange ex, String token, boolean persistent) {
        String value = "af_session=" + token + "; Path=/; HttpOnly; SameSite=Lax";
        if (persistent) value += "; Max-Age=2592000";
        ex.getResponseHeaders().add("Set-Cookie", value);
    }

    private static void expireCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", "af_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    private static int parseInt(String raw, String what) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            throw new ApiException(400, "Invalid " + what);
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                out.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static boolean flag(Map<String, String> q, String key) {
        String value = q.get(key);
        return value == null || !value.equals("0");
    }
}
