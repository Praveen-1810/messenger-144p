import java.sql.*;
import java.util.*;

public class RoomManager {

    private static final int HISTORY_LIMIT = 20;

    private static Connection connect() throws SQLException {
        return Database.connect();
    }

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static int getRoomId(Connection con, String room)
            throws SQLException {
        String sql = "SELECT id FROM rooms WHERE name = ?";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, room);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : -1;
            }
        }
    }

    private static int getUserId(Connection con, String username)
            throws SQLException {
        String sql = "SELECT id FROM users WHERE username = ?";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : -1;
            }
        }
    }

    private static boolean isMember(Connection con, int roomId, int userId)
            throws SQLException {
        String sql =
                "SELECT 1 FROM room_members WHERE room_id = ? AND user_id = ?";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setInt(2, userId);

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean isDirectRoomParticipant(String roomName, int userId) {
        if (roomName == null || !roomName.startsWith("dm_") || userId <= 0) {
            return false;
        }

        String[] parts = roomName.split("_");
        if (parts.length != 3) return false;

        try {
            int firstId = Integer.parseInt(parts[1]);
            int secondId = Integer.parseInt(parts[2]);
            return userId == firstId || userId == secondId;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isAuthorizedMember(
            Connection con, int roomId, String roomName, int userId)
            throws SQLException {

        if (roomId <= 0 || userId <= 0 || !isMember(con, roomId, userId)) {
            return false;
        }

        if (roomName != null && roomName.startsWith("dm_")) {
            return isDirectRoomParticipant(roomName, userId);
        }

        return true;
    }

    // --------------------------------------------------
    // CREATE ROOM
    // --------------------------------------------------

    public static String create(String room, int userId) {
        room = normalize(room);

        if (room.isEmpty() || room.startsWith("dm_")) {
            return "Invalid room name.";
        }

        try (Connection con = connect()) {
            String sql =
                    "INSERT INTO rooms(name, type, created_by) "
                    + "VALUES(?, 'group', ?)";

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, room);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }

            int roomId = getRoomId(con, room);

            String memberSql =
                    "INSERT INTO room_members(room_id, user_id) VALUES(?, ?)";

            try (PreparedStatement ps = con.prepareStatement(memberSql)) {
                ps.setInt(1, roomId);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }

            return null;

        } catch (SQLException e) {
            return "Room already exists or could not be created.";
        }
    }

    // --------------------------------------------------
    // JOIN ROOM
    // --------------------------------------------------

    public static String join(String room, int userId) {
        room = normalize(room);

        try (Connection con = connect()) {
            String type;

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT type FROM rooms WHERE name = ?")) {
                ps.setString(1, room);

                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return "Room does not exist.";
                    type = rs.getString("type");
                }
            }

            if ("dm".equals(type) || room.startsWith("dm_")) {
                return "Private chats can only be opened with /msg <username>.";
            }

            int roomId = getRoomId(con, room);
            if (roomId == -1) return "Room does not exist.";

            // A kicked user cannot rejoin.
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT 1 FROM room_bans WHERE room_id = ? AND user_id = ?")) {
                ps.setInt(1, roomId);
                ps.setInt(2, userId);

                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return "You have been removed from this room and cannot rejoin.";
                    }
                }
            }

            if (isMember(con, roomId, userId)) return null;

            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO room_members(room_id, user_id) VALUES(?, ?)")) {
                ps.setInt(1, roomId);
                ps.setInt(2, userId);
                ps.executeUpdate();
            }

            return null;

        } catch (SQLException e) {
            return "Could not join room.";
        }
    }

    // --------------------------------------------------
    // ROOM CREATOR / MANAGEMENT
    // --------------------------------------------------

    public static boolean isCreator(String room, int userId) {
        if (room == null || userId <= 0) return false;

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT created_by FROM rooms "
                     + "WHERE name = ? AND type = 'group'")) {

            ps.setString(1, normalize(room));

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;

                int creatorId = rs.getInt("created_by");
                return !rs.wasNull() && creatorId == userId;
            }

        } catch (SQLException e) {
            System.out.println("Could not check room creator: " + e.getMessage());
            return false;
        }
    }

    public static String deleteRoom(String room, int requesterId) {
        room = normalize(room);

        if (room.equals("general")) {
            return "The general room cannot be deleted.";
        }

        if (room.startsWith("dm_")) {
            return "Private chats cannot be deleted with this command.";
        }

        try (Connection con = connect()) {
            con.setAutoCommit(false);

            int roomId = getRoomId(con, room);

            if (roomId == -1) {
                con.rollback();
                return "Room does not exist.";
            }

            if (!isCreator(room, requesterId)) {
                con.rollback();
                return "Only the room creator can delete this room.";
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM messages WHERE room_id = ?")) {
                ps.setInt(1, roomId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM room_bans WHERE room_id = ?")) {
                ps.setInt(1, roomId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM room_members WHERE room_id = ?")) {
                ps.setInt(1, roomId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM rooms WHERE id = ?")) {
                ps.setInt(1, roomId);
                ps.executeUpdate();
            }

            con.commit();
            return null;

        } catch (SQLException e) {
            return "Could not delete room.";
        }
    }

    public static String kickMember(
            String room, String targetUsername, int requesterId) {

        room = normalize(room);

        if (room.equals("general")) {
            return "Members cannot be kicked from the general room.";
        }

        if (room.startsWith("dm_")) {
            return "Members cannot be kicked from private chats.";
        }

        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            return "Enter a username to kick.";
        }

        try (Connection con = connect()) {
            con.setAutoCommit(false);

            int roomId = getRoomId(con, room);

            if (roomId == -1) {
                con.rollback();
                return "Room does not exist.";
            }

            if (!isCreator(room, requesterId)) {
                con.rollback();
                return "Only the room creator can kick members.";
            }

            int targetId = getUserId(con, targetUsername.trim());

            if (targetId == -1) {
                con.rollback();
                return "User does not exist.";
            }

            if (targetId == requesterId) {
                con.rollback();
                return "You cannot kick yourself.";
            }

            if (!isMember(con, roomId, targetId)) {
                con.rollback();
                return "That user is not a member of this room.";
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT OR IGNORE INTO room_bans(room_id, user_id) "
                    + "VALUES(?, ?)")) {
                ps.setInt(1, roomId);
                ps.setInt(2, targetId);
                ps.executeUpdate();
            }

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM room_members WHERE room_id = ? AND user_id = ?")) {
                ps.setInt(1, roomId);
                ps.setInt(2, targetId);
                ps.executeUpdate();
            }

            con.commit();
            return null;

        } catch (SQLException e) {
            return "Could not kick that member.";
        }
    }

    public static List<String> roomMembers(String room, int requesterId) {
        List<String> members = new ArrayList<>();

        if (room == null || !isCreator(room, requesterId)) {
            return members;
        }

        String sql =
                "SELECT u.username FROM room_members rm "
                + "JOIN users u ON u.id = rm.user_id "
                + "JOIN rooms r ON r.id = rm.room_id "
                + "WHERE r.name = ? ORDER BY u.username";

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, normalize(room));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(rs.getString("username"));
                }
            }

        } catch (SQLException e) {
            System.out.println("Could not list room members: " + e.getMessage());
        }

        return members;
    }

    // --------------------------------------------------
    // LEAVE ROOM
    // --------------------------------------------------

    public static String leave(String room, int userId) {
        room = normalize(room);

        if (room.equals("general")) {
            return "You cannot leave the general room.";
        }

        if (room.startsWith("dm_")) {
            return "Use /end to leave a private chat.";
        }

        try (Connection con = connect()) {
            int roomId = getRoomId(con, room);

            if (roomId == -1) return "Room does not exist.";

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM room_members WHERE room_id = ? AND user_id = ?")) {

                ps.setInt(1, roomId);
                ps.setInt(2, userId);

                if (ps.executeUpdate() == 0) {
                    return "You have not joined this room.";
                }
            }

            return null;

        } catch (SQLException e) {
            return "Could not leave room.";
        }
    }

    // --------------------------------------------------
    // JOINED ROOMS
    // --------------------------------------------------

    public static Set<String> joinedRooms(int userId) {
        Set<String> rooms = new TreeSet<>();

        String sql =
                "SELECT r.name FROM rooms r "
                + "JOIN room_members rm ON r.id = rm.room_id "
                + "WHERE rm.user_id = ?";

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String roomName = rs.getString("name");

                    if (!roomName.startsWith("dm_")
                            || isDirectRoomParticipant(roomName, userId)) {
                        rooms.add(roomName);
                    }
                }
            }

        } catch (SQLException e) {
            System.out.println("Could not load joined rooms: " + e.getMessage());
        }

        return rooms;
    }

    // --------------------------------------------------
    // ALL GROUP ROOMS
    // --------------------------------------------------

    public static List<String[]> allRooms() {
        List<String[]> rooms = new ArrayList<>();

        String sql =
                "SELECT r.name, COUNT(rm.user_id) AS members "
                + "FROM rooms r "
                + "LEFT JOIN room_members rm ON r.id = rm.room_id "
                + "WHERE r.type = 'group' "
                + "GROUP BY r.id, r.name ORDER BY r.name";

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                rooms.add(new String[] {
                    rs.getString("name"),
                    String.valueOf(rs.getInt("members"))
                });
            }

        } catch (SQLException e) {
            System.out.println("Could not list rooms: " + e.getMessage());
        }

        return rooms;
    }

    // --------------------------------------------------
    // DIRECT ROOM
    // --------------------------------------------------

    public static class DirectRoom {
        String roomName;
        String otherUsername;
        String error;

        DirectRoom(String roomName, String otherUsername, String error) {
            this.roomName = roomName;
            this.otherUsername = otherUsername;
            this.error = error;
        }
    }

    public static DirectRoom openDirectRoom(int userId, String targetUsername) {
        try (Connection con = connect()) {
            int otherId = getUserId(con, targetUsername);

            if (otherId == -1) {
                return new DirectRoom(null, null, "User does not exist.");
            }

            if (otherId == userId) {
                return new DirectRoom(null, null, "You cannot message yourself.");
            }

            String actualUsername;

            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT username FROM users WHERE id = ?")) {

                ps.setInt(1, otherId);

                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    actualUsername = rs.getString("username");
                }
            }

            String room = "dm_" + Math.min(userId, otherId)
                    + "_" + Math.max(userId, otherId);

            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT OR IGNORE INTO rooms(name, type) VALUES(?, 'dm')")) {
                ps.setString(1, room);
                ps.executeUpdate();
            }

            int roomId = getRoomId(con, room);

            try (PreparedStatement ps = con.prepareStatement(
                    "DELETE FROM room_members WHERE room_id = ? "
                    + "AND user_id NOT IN (?, ?)")) {

                ps.setInt(1, roomId);
                ps.setInt(2, userId);
                ps.setInt(3, otherId);
                ps.executeUpdate();
            }

            String memberSql =
                    "INSERT OR IGNORE INTO room_members(room_id, user_id) "
                    + "VALUES(?, ?)";

            try (PreparedStatement ps = con.prepareStatement(memberSql)) {
                ps.setInt(1, roomId);
                ps.setInt(2, userId);
                ps.executeUpdate();

                ps.setInt(2, otherId);
                ps.executeUpdate();
            }

            return new DirectRoom(room, actualUsername, null);

        } catch (SQLException e) {
            return new DirectRoom(null, null, "Could not open private chat.");
        }
    }

    private static void notifyDirectMessage(ClientHandler recipient, String sender) {
        String encodedSender = java.util.Base64.getEncoder().encodeToString(
                sender.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        recipient.send(ClientHandler.DM_EVENT_PREFIX + encodedSender);
    }

    // --------------------------------------------------
    // CODE MESSAGE DELIVERY
    // --------------------------------------------------

    public static void deliverCode(
            String room, String sender, String language, String code) {

        if (room == null || sender == null || language == null || code == null) {
            return;
        }

        if (!language.matches("[a-zA-Z0-9+#.-]+")) {
            return;
        }

        int messageId;
        int senderId;
        String content = language + "\n" + code;

        try (Connection con = connect()) {
            int roomId = getRoomId(con, room);
            senderId = getUserId(con, sender);

            if (roomId == -1 || senderId == -1
                    || !isAuthorizedMember(con, roomId, room, senderId)) {
                return;
            }

            String sql = "INSERT INTO messages(room_id, sender_id, type, content) "
                    + "VALUES(?, ?, 'code', ?)";

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, roomId);
                ps.setInt(2, senderId);
                ps.setString(3, content);
                ps.executeUpdate();
            }

            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (!rs.next()) return;
                messageId = rs.getInt(1);
            }

        } catch (SQLException e) {
            System.out.println("Could not save code: " + e.getMessage());
            return;
        }

        String display = formatCode(sender, language, code);

        for (ClientHandler handler : Server.online.values()) {
            if (!handler.isMemberOf(room)) continue;

            if (room.startsWith("dm_")
                    && !isDirectRoomParticipant(room, handler.userIdValue())) {
                continue;
            }

            if (room.equals(handler.currentRoom())) {
                handler.send(display);
                markRead(room, handler.userIdValue(), messageId);
            } else if (room.startsWith("dm_")
                    && handler.userIdValue() != senderId) {
                notifyDirectMessage(handler, sender);
            }
        }
    }

    private static String formatCode(
            String sender, String language, String code) {
        return "\n========== " + sender + " shared "
                + language.toUpperCase(Locale.ROOT) + " code ==========\n"
                + code
                + "\n========== END CODE ==========";
    }

    // --------------------------------------------------
    // DELIVER MESSAGE
    // --------------------------------------------------

    public static void deliver(String room, String sender, String message) {
        int messageId = -1;
        int senderId;

        try (Connection con = connect()) {
            int roomId = getRoomId(con, room);
            senderId = getUserId(con, sender);

            if (roomId == -1 || senderId == -1
                    || !isAuthorizedMember(con, roomId, room, senderId)) {
                return;
            }

            String sql =
                    "INSERT INTO messages(room_id, sender_id, type, content) "
                    + "VALUES(?, ?, 'text', ?)";

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, roomId);
                ps.setInt(2, senderId);
                ps.setString(3, message);
                ps.executeUpdate();
            }

            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) messageId = rs.getInt(1);
            }

        } catch (SQLException e) {
            System.out.println("Could not save message: " + e.getMessage());
            return;
        }

        String display = sender + ": " + message;

        for (ClientHandler handler : Server.online.values()) {
            if (handler.isMemberOf(room)
                    && (!room.startsWith("dm_")
                    || isDirectRoomParticipant(room, handler.userIdValue()))) {

                if (room.equals(handler.currentRoom())) {
                    handler.send(display);
                    markRead(room, getHandlerUserId(handler), messageId);
                } else if (room.startsWith("dm_")
                        && handler.userIdValue() != senderId) {
                    notifyDirectMessage(handler, sender);
                }
            }
        }
    }

    private static int getHandlerUserId(ClientHandler handler) {
        return handler.userIdValue();
    }

    public static void notifyViewers(
            String room, String message, String excludedUsername) {

        if (room == null) return;

        for (ClientHandler handler : Server.online.values()) {
            if (handler.isMemberOf(room)
                    && (!room.startsWith("dm_")
                    || isDirectRoomParticipant(room, handler.userIdValue()))
                    && room.equals(handler.currentRoom())
                    && !handler.username().equals(excludedUsername)) {
                handler.send(message);
            }
        }
    }

    // --------------------------------------------------
    // HISTORY
    // --------------------------------------------------

    private static class HistoryMessage {
        final int id;
        final String display;

        HistoryMessage(int id, String display) {
            this.id = id;
            this.display = display;
        }
    }

    public static void sendHistory(
            String room, int userId, ClientHandler handler) {

        if (!canAccessRoom(room, userId)) {
            handler.send("Error: you do not have access to this room.");
            return;
        }

        List<HistoryMessage> history = getHistory(room, userId);

        if (history.isEmpty()) {
            handler.send("No previous messages from other users in this room.");
            return;
        }

        handler.send("--- Recent messages ---");

        int lastShownMessageId = 0;

        for (HistoryMessage message : history) {
            handler.send(message.display);
            lastShownMessageId = message.id;
        }

        handler.send("--- End of history ---");

        markRead(room, userId, lastShownMessageId);
    }

    private static List<HistoryMessage> getHistory(String room, int userId) {
        List<HistoryMessage> history = new ArrayList<>();

        String sql =
                "SELECT m.id, m.content, m.type, m.created_at, u.username "
                + "FROM messages m "
                + "JOIN rooms r ON r.id = m.room_id "
                + "JOIN room_members rm ON rm.room_id = r.id "
                + "JOIN users u ON u.id = m.sender_id "
                + "WHERE r.name = ? AND rm.user_id = ? AND m.sender_id <> ? "
                + "ORDER BY m.id DESC LIMIT ?";

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, room);
            ps.setInt(2, userId);
            ps.setInt(3, userId);
            ps.setInt(4, HISTORY_LIMIT);

            try (ResultSet rs = ps.executeQuery()) {
                List<HistoryMessage> reversed = new ArrayList<>();

                while (rs.next()) {
                    String sender = rs.getString("username");
                    String content = rs.getString("content");
                    String type = rs.getString("type");
                    String display;

                    if ("code".equalsIgnoreCase(type)) {
                        int separator = content.indexOf('\n');
                        if (separator > 0) {
                            String language = content.substring(0, separator);
                            String code = content.substring(separator + 1);
                            display = "[" + rs.getString("created_at") + "] "
                                    + formatCode(sender, language, code);
                        } else {
                            display = sender + " shared code:\n" + content;
                        }
                    } else {
                        display = "[" + rs.getString("created_at") + "] "
                                + sender + ": " + content;
                    }

                    reversed.add(new HistoryMessage(rs.getInt("id"), display));
                }

                Collections.reverse(reversed);
                history.addAll(reversed);
            }

        } catch (SQLException e) {
            System.out.println("Could not load history: " + e.getMessage());
        }

        return history;
    }

    private static void markRead(String room, int userId, int messageId) {
        if (userId <= 0 || messageId <= 0) return;

        try (Connection con = connect()) {
            int roomId = getRoomId(con, room);

            if (!isAuthorizedMember(con, roomId, room, userId)) return;

            String sql =
                    "UPDATE room_members "
                    + "SET last_read_id = MAX(last_read_id, ?) "
                    + "WHERE user_id = ? AND room_id = ?";

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, messageId);
                ps.setInt(2, userId);
                ps.setInt(3, roomId);
                ps.executeUpdate();
            }

        } catch (SQLException e) {
            System.out.println("Could not update read status: " + e.getMessage());
        }
    }

    public static boolean canAccessRoom(String room, int userId) {
        if (room == null || userId <= 0) return false;

        try (Connection con = connect()) {
            int roomId = getRoomId(con, room);
            return isAuthorizedMember(con, roomId, room, userId);

        } catch (SQLException e) {
            System.out.println("Could not verify room access: " + e.getMessage());
            return false;
        }
    }

    // --------------------------------------------------
    // DIRECT ROOM DISPLAY NAME
    // --------------------------------------------------

    private static String directRoomDisplayName(
            Connection con, String roomName, int userId) throws SQLException {

        String sql =
                "SELECT u.username FROM room_members rm "
                + "JOIN rooms r ON r.id = rm.room_id "
                + "JOIN users u ON u.id = rm.user_id "
                + "WHERE r.name = ? AND rm.user_id <> ? LIMIT 1";

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, roomName);
            ps.setInt(2, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "DM with " + rs.getString("username");
                }
            }
        }

        return "Private chat";
    }

    // --------------------------------------------------
    // UNREAD COUNTS
    // --------------------------------------------------

    /* Returns [contact username, unread count] rows for the DM sidebar. */
    public static List<String[]> directContacts(int userId) {
        List<String[]> contacts = new ArrayList<>();

        String sql =
                "SELECT u.username, " +
                "(SELECT COUNT(*) FROM messages m " +
                " WHERE m.room_id = r.id AND m.sender_id <> ? " +
                " AND m.id > mine.last_read_id) AS unread " +
                "FROM rooms r " +
                "JOIN room_members mine ON mine.room_id = r.id AND mine.user_id = ? " +
                "JOIN room_members other ON other.room_id = r.id AND other.user_id <> ? " +
                "JOIN users u ON u.id = other.user_id " +
                "WHERE r.type = 'dm' " +
                "ORDER BY u.username";

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setInt(2, userId);
            ps.setInt(3, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    contacts.add(new String[]{
                        rs.getString("username"),
                        String.valueOf(rs.getInt("unread"))
                    });
                }
            }
        } catch (SQLException e) {
            System.out.println("Could not load direct contacts: " + e.getMessage());
        }

        return contacts;
    }

    public static Map<String, Integer> unreadCounts(int userId) {
        Map<String, Integer> counts = new LinkedHashMap<>();

        String sql =
                "SELECT r.name, COUNT(m.id) AS unread "
                + "FROM room_members rm "
                + "JOIN rooms r ON r.id = rm.room_id "
                + "LEFT JOIN messages m ON m.room_id = r.id "
                + "AND m.id > rm.last_read_id "
                + "AND m.sender_id <> rm.user_id "
                + "WHERE rm.user_id = ? "
                + "GROUP BY r.id, r.name "
                + "ORDER BY r.name";

        try (Connection con = connect();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setInt(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int unread = rs.getInt("unread");

                    if (unread > 0) {
                        String roomName = rs.getString("name");

                        if (roomName.startsWith("dm_")
                                && !isDirectRoomParticipant(roomName, userId)) {
                            continue;
                        }

                        String displayName = roomName.startsWith("dm_")
                                ? directRoomDisplayName(con, roomName, userId)
                                : roomName;

                        counts.put(displayName, unread);
                    }
                }
            }

        } catch (SQLException e) {
            System.out.println("Could not get unread counts: " + e.getMessage());
        }

        return counts;
    }
}