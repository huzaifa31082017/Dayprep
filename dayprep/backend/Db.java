import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** All database code lives here. Every method opens a connection, runs one query, and closes it. */
public class Db {

    private static final String URL = "jdbc:sqlite:dayprep.db?foreign_keys=on";

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    /** Creates the tables by running database/schema.sql. */
    public static void init() throws Exception {
        String sql = Files.readString(Path.of("database/schema.sql"));
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.trim().isEmpty()) st.execute(part);
            }
        }
    }

    // ---------- signup / login ----------

    /** Returns the new user's id, or -1 if the email is already registered. */
    public static int createUser(String email, String password) throws SQLException {
        if (findUserId(email) != -1) return -1;
        String salt = Util.newSalt();
        String hash = Util.hashPassword(password, salt);
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users(email, password_hash, salt) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public static int findUserId(String email) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    /** Returns the user's id if the email and password match, otherwise -1. */
    public static int checkLogin(String email, String password) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT id, password_hash, salt FROM users WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                String hash = Util.hashPassword(password, rs.getString("salt"));
                return Util.sameHash(hash, rs.getString("password_hash")) ? rs.getInt("id") : -1;
            }
        }
    }

    public static String getEmail(int userId) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT email FROM users WHERE id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---------- sessions ----------

    public static String createSession(int userId) throws SQLException {
        String token = Util.newToken();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("INSERT INTO sessions(token, user_id) VALUES(?,?)")) {
            ps.setString(1, token);
            ps.setInt(2, userId);
            ps.executeUpdate();
        }
        return token;
    }

    /** Sessions last 30 days. Returns the user's id, or -1 if the token is unknown or expired. */
    public static int userIdForToken(String token) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id FROM sessions WHERE token = ? "
                             + "AND created_at > strftime('%Y-%m-%dT%H:%M:%SZ','now','-30 days')")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    public static void deleteSession(String token) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }

    // ---------- saved data (favourites, plans, profile...) ----------

    /** Returns the saved JSON text, or null if this user has not saved anything yet. */
    public static String getData(int userId) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT json FROM user_data WHERE user_id = ?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public static void saveData(int userId, String json) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO user_data(user_id, json) VALUES(?,?) "
                             + "ON CONFLICT(user_id) DO UPDATE SET json = excluded.json, "
                             + "updated_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')")) {
            ps.setInt(1, userId);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }

    // ---------- history ----------

    public static void addHistory(int userId, String action) throws SQLException {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO history(user_id, action) VALUES(?,?)")) {
                ps.setInt(1, userId);
                ps.setString(2, action);
                ps.executeUpdate();
            }
            // keep only the newest 200 rows per user so the table cannot grow forever
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM history WHERE user_id = ? AND id NOT IN "
                            + "(SELECT id FROM history WHERE user_id = ? ORDER BY id DESC LIMIT 200)")) {
                ps.setInt(1, userId);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }
        }
    }

    /** Returns the latest 40 history rows as a JSON array: [{"t":"...","d":"..."}] */
    public static String getHistoryJson(int userId) throws SQLException {
        StringBuilder sb = new StringBuilder("[");
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT action, created_at FROM history WHERE user_id = ? ORDER BY id DESC LIMIT 40")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"t\":").append(Util.jsonString(rs.getString(1)))
                      .append(",\"d\":").append(Util.jsonString(rs.getString(2))).append("}");
                }
            }
        }
        return sb.append("]").toString();
    }

    public static void clearHistory(int userId) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM history WHERE user_id = ?")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
        }
    }

    // ---------- account ----------

    /** Returns true if the current password was correct and the new one was saved. */
    public static boolean changePassword(int userId, String current, String next) throws SQLException {
        String email = getEmail(userId);
        if (email == null || checkLogin(email, current) != userId) return false;
        String salt = Util.newSalt();
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("UPDATE users SET password_hash = ?, salt = ? WHERE id = ?")) {
            ps.setString(1, Util.hashPassword(next, salt));
            ps.setString(2, salt);
            ps.setInt(3, userId);
            ps.executeUpdate();
        }
        return true;
    }

    public static void deleteUser(int userId) throws SQLException {
        try (Connection c = connect()) {
            String[] sql = {
                    "DELETE FROM history WHERE user_id = ?",
                    "DELETE FROM user_data WHERE user_id = ?",
                    "DELETE FROM sessions WHERE user_id = ?",
                    "DELETE FROM users WHERE id = ?"
            };
            for (String q : sql) {
                try (PreparedStatement ps = c.prepareStatement(q)) {
                    ps.setInt(1, userId);
                    ps.executeUpdate();
                }
            }
        }
    }
}
