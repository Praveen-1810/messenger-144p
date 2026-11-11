import java.sql.*;

/** Step 1 check: creates the database and prints what it contains. */
public class DbTest {
    public static void main(String[] args) throws Exception {
        Database.init();
        System.out.println("Database ready: messenger.db\n");

        try (Connection c = Database.connect(); Statement s = c.createStatement()) {
            System.out.println("Tables:");
            ResultSet t = s.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
            while (t.next()) System.out.println("  - " + t.getString("name"));

            System.out.println("\nRooms:");
            ResultSet r = s.executeQuery("SELECT id, name, type FROM rooms");
            while (r.next())
                System.out.println("  #" + r.getString("name") + " (" + r.getString("type") + ", id " + r.getInt("id") + ")");
        }
    }
}