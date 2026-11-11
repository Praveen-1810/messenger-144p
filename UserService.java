import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Creates accounts and checks logins. Passwords are never stored, only a salted hash. */
public class UserService {
    private static final int ITERATIONS = 65000;
    private static final int KEY_BITS = 256;

    // PBKDF2 turns a password + random salt into a hash that is slow to brute-force
    private static byte[] hash(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    /** Returns null on success, or an error message to show the user. */
    public static String register(String username, String password) {
        if (!username.matches("[A-Za-z0-9_]{3,20}"))
            return "Username must be 3-20 characters: letters, numbers or underscore.";
        if (password.length() < 4)
            return "Password must be at least 4 characters.";

        try (Connection c = Database.connect()) {
            try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM users WHERE username = ?")) {
                p.setString(1, username);
                if (p.executeQuery().next()) return "That username is already taken.";
            }

            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            String hashB64 = Base64.getEncoder().encodeToString(hash(password, salt));
            String saltB64 = Base64.getEncoder().encodeToString(salt);

            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO users (username, password_hash, salt) VALUES (?, ?, ?)")) {
                p.setString(1, username);
                p.setString(2, hashB64);
                p.setString(3, saltB64);
                p.executeUpdate();
            }

            // Everyone is a member of #general from the start
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO room_members (room_id, user_id) "
                  + "SELECT (SELECT id FROM rooms WHERE name='general'), id FROM users WHERE username = ?")) {
                p.setString(1, username);
                p.executeUpdate();
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return "Server error while registering.";
        }
    }

    /** Returns the user's id if the login is correct, or -1 if not. */
    public static int login(String username, String password) {
        try (Connection c = Database.connect();
             PreparedStatement p = c.prepareStatement(
                 "SELECT id, password_hash, salt FROM users WHERE username = ?")) {
            p.setString(1, username);
            ResultSet rs = p.executeQuery();
            if (!rs.next()) return -1;

            byte[] salt = Base64.getDecoder().decode(rs.getString("salt"));
            byte[] expected = Base64.getDecoder().decode(rs.getString("password_hash"));
            byte[] actual = hash(password, salt);
            return MessageDigest.isEqual(expected, actual) ? rs.getInt("id") : -1;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }
}