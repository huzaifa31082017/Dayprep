import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * DayPrep backend. Uses only the HTTP server that ships with Java (no Spring, no frameworks).
 * It also serves frontend/index.html, so the whole app runs at http://localhost:8080
 */
public class Server {

    public static void main(String[] args) throws Exception {
        Db.init();

        // "localhost" only. Change to new InetSocketAddress(8080) to allow other devices on your network.
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8080), 0);
        server.createContext("/api/", Server::handleApi);
        server.createContext("/", Server::handlePage);
        server.start();
        System.out.println("DayPrep is running at http://localhost:8080");
    }

    // ---------- serves the website ----------

    private static void handlePage(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        Path file = Path.of("frontend/index.html");
        if (!Files.exists(file)) {
            byte[] msg = "frontend/index.html not found. Run the server from the project folder.".getBytes();
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            ex.close();
            return;
        }
        byte[] html = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        ex.getResponseBody().write(html);
        ex.close();
    }

    // ---------- API routes ----------

    private static void handleApi(HttpExchange ex) throws IOException {
        // CORS headers so the page also works if you open index.html straight from your disk
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");

        String method = ex.getRequestMethod();
        if (method.equals("OPTIONS")) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }

        try {
            switch (method + " " + ex.getRequestURI().getPath()) {
                case "POST /api/signup":    signup(ex); break;
                case "POST /api/login":     login(ex); break;
                case "GET /api/me":         me(ex); break;
                case "POST /api/logout":    logout(ex); break;
                case "PUT /api/data":       saveData(ex); break;
                case "POST /api/history":   addHistory(ex); break;
                case "DELETE /api/history": clearHistory(ex); break;
                case "POST /api/password":  changePassword(ex); break;
                case "DELETE /api/account": deleteAccount(ex); break;
                default: Util.sendError(ex, 404, "Not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            Util.sendError(ex, 500, "Server error. Please try again.");
        }
    }

    private static void signup(HttpExchange ex) throws Exception {
        Map<String, String> form = Util.parseForm(Util.readBody(ex));
        String email = form.getOrDefault("email", "").trim().toLowerCase();
        String password = form.getOrDefault("password", "");

        if (!email.contains("@") || email.length() > 254) {
            Util.sendError(ex, 400, "Enter a valid email address.");
            return;
        }
        if (password.length() < 6 || password.length() > 200) {
            Util.sendError(ex, 400, "Password must be at least 6 characters.");
            return;
        }
        int userId = Db.createUser(email, password);
        if (userId == -1) {
            Util.sendError(ex, 409, "That email already has an account. Sign in instead.");
            return;
        }
        Util.send(ex, 200, sessionJson(Db.createSession(userId), userId));
    }

    private static void login(HttpExchange ex) throws Exception {
        Map<String, String> form = Util.parseForm(Util.readBody(ex));
        String email = form.getOrDefault("email", "").trim().toLowerCase();
        String password = form.getOrDefault("password", "");

        int userId = Db.checkLogin(email, password);
        if (userId == -1) {
            Util.sendError(ex, 401, "Email or password is incorrect.");
            return;
        }
        Util.send(ex, 200, sessionJson(Db.createSession(userId), userId));
    }

    /** The browser calls this on page load to check that its saved token still works. */
    private static void me(HttpExchange ex) throws Exception {
        int userId = currentUser(ex);
        if (userId == -1) return;
        Util.send(ex, 200, sessionJson(tokenOf(ex), userId));
    }

    private static void logout(HttpExchange ex) throws Exception {
        String token = tokenOf(ex);
        if (token != null) Db.deleteSession(token);
        Util.send(ex, 200, "{\"ok\":true}");
    }

    private static void saveData(HttpExchange ex) throws Exception {
        int userId = currentUser(ex);
        if (userId == -1) return;
        String json = Util.readBody(ex).trim();
        if (!json.startsWith("{") || !json.endsWith("}")) {
            Util.sendError(ex, 400, "Invalid data.");
            return;
        }
        Db.saveData(userId, json);
        Util.send(ex, 200, "{\"ok\":true}");
    }

    private static void addHistory(HttpExchange ex) throws Exception {
        int userId = currentUser(ex);
        if (userId == -1) return;
        String text = Util.parseForm(Util.readBody(ex)).getOrDefault("t", "").trim();
        if (text.isEmpty()) {
            Util.sendError(ex, 400, "Nothing to save.");
            return;
        }
        if (text.length() > 200) text = text.substring(0, 200);
        Db.addHistory(userId, text);
        Util.send(ex, 200, "{\"ok\":true}");
    }

    private static void clearHistory(HttpExchange ex) throws Exception {
        int userId = currentUser(ex);
        if (userId == -1) return;
        Db.clearHistory(userId);
        Util.send(ex, 200, "{\"ok\":true}");
    }

    private static void changePassword(HttpExchange ex) throws Exception {
        int userId = currentUser(ex);
        if (userId == -1) return;
        Map<String, String> form = Util.parseForm(Util.readBody(ex));
        String next = form.getOrDefault("next", "");
        if (next.length() < 6 || next.length() > 200) {
            Util.sendError(ex, 400, "New password must be at least 6 characters.");
            return;
        }
        if (!Db.changePassword(userId, form.getOrDefault("current", ""), next)) {
            Util.sendError(ex, 400, "Current password is incorrect.");
            return;
        }
        Util.send(ex, 200, "{\"ok\":true}");
    }

    private static void deleteAccount(HttpExchange ex) throws Exception {
        int userId = currentUser(ex);
        if (userId == -1) return;
        Db.deleteUser(userId);
        Util.send(ex, 200, "{\"ok\":true}");
    }

    // ---------- helpers ----------

    /** Reads "Authorization: Bearer <token>" from the request. Returns null if missing. */
    private static String tokenOf(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return header.substring(7).trim();
    }

    /** Returns the logged-in user's id. If not logged in, sends a 401 reply and returns -1. */
    private static int currentUser(HttpExchange ex) throws Exception {
        String token = tokenOf(ex);
        int userId = (token == null) ? -1 : Db.userIdForToken(token);
        if (userId == -1) Util.sendError(ex, 401, "Please log in again.");
        return userId;
    }

    /** What the browser receives after login, signup, or page load. */
    private static String sessionJson(String token, int userId) throws Exception {
        String data = Db.getData(userId);
        return "{\"token\":" + Util.jsonString(token)
                + ",\"email\":" + Util.jsonString(Db.getEmail(userId))
                + ",\"data\":" + (data == null ? "null" : data)
                + ",\"history\":" + Db.getHistoryJson(userId) + "}";
    }
}
