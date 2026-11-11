
import java.sql.*;

public class Database {
    private static final String URL = "jdbc:sqlite:messenger.db";

    public static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(URL);

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }

        return connection;
    }

    public static void init() throws SQLException {
        try (Connection c = connect();
             Statement s = c.createStatement()) {

            s.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "username TEXT NOT NULL UNIQUE,"
                    + "password_hash TEXT NOT NULL,"
                    + "salt TEXT NOT NULL,"
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");

            s.execute("CREATE TABLE IF NOT EXISTS rooms ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL UNIQUE,"
                    + "type TEXT NOT NULL CHECK (type IN ('group','dm')),"
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP)");

            // Safe migration for existing databases.
            ensureColumn(c, "rooms", "created_by", "INTEGER");

            s.execute("CREATE TABLE IF NOT EXISTS room_members ("
                    + "room_id INTEGER NOT NULL,"
                    + "user_id INTEGER NOT NULL,"
                    + "last_read_id INTEGER NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (room_id, user_id),"
                    + "FOREIGN KEY (room_id) REFERENCES rooms(id),"
                    + "FOREIGN KEY (user_id) REFERENCES users(id))");

            ensureColumn(c, "room_members", "last_read_id",
                    "INTEGER NOT NULL DEFAULT 0");

            // A kicked user cannot rejoin unless a future unban feature is added.
            s.execute("CREATE TABLE IF NOT EXISTS room_bans ("
                    + "room_id INTEGER NOT NULL,"
                    + "user_id INTEGER NOT NULL,"
                    + "banned_at TEXT DEFAULT CURRENT_TIMESTAMP,"
                    + "PRIMARY KEY (room_id, user_id),"
                    + "FOREIGN KEY (room_id) REFERENCES rooms(id),"
                    + "FOREIGN KEY (user_id) REFERENCES users(id))");

            s.execute("CREATE TABLE IF NOT EXISTS messages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "room_id INTEGER NOT NULL,"
                    + "sender_id INTEGER NOT NULL,"
                    + "type TEXT NOT NULL DEFAULT 'text' "
                    + "CHECK (type IN ('text','code')),"
                    + "language TEXT,"
                    + "content TEXT NOT NULL,"
                    + "created_at TEXT DEFAULT CURRENT_TIMESTAMP,"
                    + "FOREIGN KEY (room_id) REFERENCES rooms(id),"
                    + "FOREIGN KEY (sender_id) REFERENCES users(id))");

            s.execute("CREATE INDEX IF NOT EXISTS idx_messages_room "
                    + "ON messages(room_id, id)");

            s.execute("INSERT OR IGNORE INTO rooms (name, type) "
                    + "VALUES ('general', 'group')");

            // Assign an owner to old rooms using their earliest existing member.
            s.executeUpdate(
                    "UPDATE rooms SET created_by = ("
                    + "SELECT user_id FROM room_members "
                    + "WHERE room_members.room_id = rooms.id "
                    + "ORDER BY rowid LIMIT 1"
                    + ") WHERE type = 'group' AND created_by IS NULL");
        }
    }

    private static void ensureColumn(
            Connection connection,
            String table,
            String column,
            String definition) throws SQLException {

        boolean exists = false;

        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "PRAGMA table_info(" + table + ")")) {

            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }

        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table
                        + " ADD COLUMN " + column + " " + definition);
            }
        }
    }
}
