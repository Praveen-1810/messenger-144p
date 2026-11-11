import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler implements Runnable {

    private static final String ROOM_PREFIX = "\u0001ROOM:";
    private static final String CODE_PREFIX = "\u0001CODE:";
    static final String DM_EVENT_PREFIX = "\u0001DM:";
    static final String DM_CONTACT_PREFIX = "\u0001DMC:";
    static final String DM_LIST_END = "\u0001DMC_END";
    private static final String MEMBERS_PREFIX = "\u0001MEMBERS:";

    private final Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    private volatile String username;
    private int userId;
    private volatile String currentRoom;
    private String previousRoom;

    private final Set<String> joined = ConcurrentHashMap.newKeySet();

    ClientHandler(Socket socket) {
        this.socket = socket;
    }

    String username() {
        return username;
    }

    String currentRoom() {
        return currentRoom;
    }

    int userIdValue() {
        return userId;
    }

    boolean isMemberOf(String room) {
        return joined.contains(room);
    }

    void addJoinedRoom(String room) {
        joined.add(room);
    }

    synchronized void send(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    private String ask(String question) throws IOException {
        send(question);
        String answer = in.readLine();
        return answer == null ? null : answer.trim();
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));

            out = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);

            if (!authenticate()) {
                return;
            }

            chatLoop();

        } catch (IOException e) {
            // Client disconnected.
        } finally {
            if (username != null && Server.online.get(username) == this) {
                Server.online.remove(username);

                RoomManager.notifyViewers(
                        currentRoom, "* " + username + " went offline", username);

                System.out.println(username + " disconnected");
            }

            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean authenticate() throws IOException {
        send("=== Welcome to Messenger (alpha) ===");

        while (true) {
            String choice = ask(
                    "\n1. Register\n2. Login\n3. Quit\nChoose:");

            if (choice == null || choice.equals("3")) {
                return false;
            }

            if (choice.equals("1")) {
                String u = ask("Choose a username:");
                if (u == null) return false;

                String p = ask("Choose a password:");
                if (p == null) return false;

                String error = UserService.register(u, p);

                send(error == null
                        ? "Account created! Now choose 2 to log in."
                        : "Error: " + error);

            } else if (choice.equals("2")) {
                String u = ask("Username:");
                if (u == null) return false;

                String p = ask("Password:");
                if (p == null) return false;

                int id = UserService.login(u, p);

                if (id < 0) {
                    send("Error: wrong username or password.");

                } else if (Server.online.putIfAbsent(u, this) != null) {
                    send("Error: that account is already logged in.");

                } else {
                    username = u;
                    userId = id;

                    System.out.println(username + " logged in");
                    return true;
                }

            } else {
                send("Please type 1, 2 or 3.");
            }
        }
    }

    private synchronized void enterRoom(String room) {
        room = RoomManager.normalize(room);

        if (room.startsWith("dm_") || !RoomManager.canAccessRoom(room, userId)) {
            send("Error: you do not have access to that room.");
            return;
        }

        currentRoom = room;

        send("--- You are now in #" + room + " ---");
        send(ROOM_PREFIX + room);

        RoomManager.sendHistory(room, userId, this);
    }

    private boolean isDirectRoom(String room) {
        return room != null && room.startsWith("dm_");
    }

    private void sendDirectContacts() {
        for (String[] contact : RoomManager.directContacts(userId)) {
            String encoded = Base64.getEncoder().encodeToString(
                    contact[0].getBytes(StandardCharsets.UTF_8));

            send(DM_CONTACT_PREFIX + encoded + ":" + contact[1]);
        }

        send(DM_LIST_END);
    }

    private synchronized void enterDirectRoom(
            String room, String otherUsername) {

        if (room == null || !isDirectRoom(room)
                || !RoomManager.canAccessRoom(room, userId)) {

            send("Error: private chat access denied.");
            return;
        }

        if (!isDirectRoom(currentRoom)) {
            previousRoom = currentRoom;
        }

        currentRoom = room;
        joined.add(room);

        send("--- Private chat with " + otherUsername + " ---");
        send("Type /end to return to your previous room.");
        send(ROOM_PREFIX + "DM:" + otherUsername);

        RoomManager.sendHistory(room, userId, this);
    }

    // Called when a user is kicked or a room is deleted.
    synchronized void forceLeaveRoom(String room, String notice) {
        joined.remove(room);

        if (room.equals(currentRoom)) {
            currentRoom = null;
            previousRoom = null;

            send(notice);
            enterRoom("general");
        } else {
            send(notice);
        }
    }

    private void chatLoop() throws IOException {
        RoomManager.join("general", userId);
        joined.addAll(RoomManager.joinedRooms(userId));

        send("\nLogged in as " + username + ". Type /help for commands.");

        enterRoom("general");
        sendDirectContacts();

        RoomManager.notifyViewers(
                "general", "* " + username + " came online", username);

        String line;

        while ((line = in.readLine()) != null) {

            // The protocol marker is U+0001; trim() would remove it.
            if (line.startsWith(CODE_PREFIX)) {
                handleCodeMessage(line);
                continue;
            }

            line = line.trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("/")) {
                if (!handleCommand(line)) {
                    break;
                }
            } else {
                RoomManager.deliver(currentRoom, username, line);
            }
        }
    }

    private void handleCodeMessage(String line) {
        try {
            String payload = line.substring(CODE_PREFIX.length());
            int separator = payload.indexOf(':');

            if (separator <= 0 || separator == payload.length() - 1) {
                send("Error: invalid code message format.");
                return;
            }

            String language = payload.substring(0, separator).trim();
            String encodedCode = payload.substring(separator + 1).trim();

            byte[] decoded = Base64.getDecoder().decode(encodedCode);
            String code = new String(decoded, StandardCharsets.UTF_8);

            if (language.isEmpty() || code.isEmpty()) {
                send("Error: language and code cannot be empty.");
                return;
            }

            RoomManager.deliverCode(currentRoom, username, language, code);

        } catch (IllegalArgumentException e) {
            send("Error: the shared code could not be decoded.");
        }
    }

    private boolean handleCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();

        String arg = parts.length > 1
                ? RoomManager.normalize(parts[1]) : "";

        switch (cmd) {

            case "/quit":
                send("Goodbye!");
                return false;

            case "/help":
                send("Commands:\n"
                   + "  /rooms                  list group rooms\n"
                   + "  /create <name>          create a room\n"
                   + "  /join <name>            join a room\n"
                   + "  /switch <name>          switch rooms\n"
                   + "  /leave <name>           leave a room\n"
                   + "  /members <room>         list room members (creator only)\n"
                   + "  /kick <room> <username> kick a room member (creator only)\n"
                   + "  /delete <room>          delete a room (creator only)\n"
                   + "  /list                   list online room members\n"
                   + "  /unread                 show unread message counts\n"
                   + "  /msg <user>             start a private chat\n"
                   + "  /msg <user> <text>      start a private chat and send a message\n"
                   + "  /end                    end private chat\n"
                   + "  /quit                   log out");
                break;

            case "/unread": {
                Map<String, Integer> counts = RoomManager.unreadCounts(userId);

                if (counts.isEmpty()) {
                    send("You have no unread messages.");
                } else {
                    send("--- Unread messages ---");

                    int total = 0;

                    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                        String roomLabel = entry.getKey().startsWith("DM with ")
                                || entry.getKey().equals("Private chat")
                                ? entry.getKey()
                                : "#" + entry.getKey();

                        send("  " + roomLabel + ": "
                                + entry.getValue() + " unread");

                        total += entry.getValue();
                    }

                    send("Total unread messages: " + total);
                }

                break;
            }

            case "/msg": {
                String[] msgParts = line.split("\\s+", 3);

                if (msgParts.length < 2 || msgParts[1].isBlank()) {
                    send("Usage: /msg <username> [message]");
                    break;
                }

                String target = msgParts[1];

                RoomManager.DirectRoom dm =
                        RoomManager.openDirectRoom(userId, target);

                if (dm.error != null) {
                    send("Error: " + dm.error);
                    break;
                }

                ClientHandler other = Server.online.get(dm.otherUsername);

                if (other == null) {
                    send("Error: " + dm.otherUsername
                            + " is offline. They must log in first.");
                    break;
                }

                String dmRoom = dm.roomName;

                joined.add(dmRoom);
                other.addJoinedRoom(dmRoom);

                // Only the sender enters the DM. The recipient stays in their
                // current room and receives the updated DM contact list.
                enterDirectRoom(dmRoom, dm.otherUsername);
                other.sendDirectContacts();

                send("* Private chat started with " + dm.otherUsername);

                if (msgParts.length == 3 && !msgParts[2].isBlank()) {
                    RoomManager.deliver(dmRoom, username, msgParts[2]);
                }

                break;
            }

            case "/end": {
                if (!isDirectRoom(currentRoom)) {
                    send("You are not in a private chat.");
                    break;
                }

                String oldRoom = previousRoom;

                if (oldRoom == null || isDirectRoom(oldRoom)) {
                    oldRoom = "general";
                }

                previousRoom = null;
                enterRoom(oldRoom);
                break;
            }

            case "/list": {
                Set<String> names = new TreeSet<>();

                for (ClientHandler h : Server.online.values()) {
                    if (h.isMemberOf(currentRoom)
                            && (!isDirectRoom(currentRoom)
                                || (h.userIdValue() == userId
                                    || RoomManager.canAccessRoom(
                                            currentRoom, h.userIdValue())))) {

                        names.add(h.username());
                    }
                }

                send("Online in this room: " + String.join(", ", names));
                break;
            }

            case "/rooms":
                send("Group rooms (> current, * joined):");

                for (String[] r : RoomManager.allRooms()) {
                    String here = r[0].equals(currentRoom) ? ">" : " ";
                    String mine = joined.contains(r[0]) ? "*" : " ";

                    send("  " + here + mine + " #" + r[0]
                            + " (" + r[1] + " members)");
                }
                break;

            case "/create": {
                if (arg.isEmpty()) {
                    send("Usage: /create <name>");
                    break;
                }

                String err = RoomManager.create(arg, userId);

                if (err != null) {
                    send("Error: " + err);
                    break;
                }

                joined.add(arg);
                send("Room #" + arg + " created.");
                enterRoom(arg);
                break;
            }

            case "/join": {
                if (arg.isEmpty()) {
                    send("Usage: /join <name>");
                    break;
                }

                boolean already = joined.contains(arg);
                String err = RoomManager.join(arg, userId);

                if (err != null) {
                    send("Error: " + err);
                    break;
                }

                joined.add(arg);

                if (!already) {
                    RoomManager.notifyViewers(
                            arg, "* " + username + " joined #" + arg, username);
                }

                enterRoom(arg);
                break;
            }

            case "/switch":
                if (arg.isEmpty()) {
                    send("Usage: /switch <name>");
                    break;
                }

                if (arg.startsWith("dm_")) {
                    send("Use /msg <username> to open a private chat.");
                    break;
                }

                if (!joined.contains(arg)
                        || !RoomManager.canAccessRoom(arg, userId)) {

                    send("Error: you haven't joined #" + arg
                            + ". Use /join " + arg);
                    break;
                }

                enterRoom(arg);
                break;

            case "/leave": {
                if (arg.isEmpty()) {
                    send("Usage: /leave <name>");
                    break;
                }

                if (!joined.contains(arg)) {
                    send("Error: you're not in #" + arg);
                    break;
                }

                String err = RoomManager.leave(arg, userId);

                if (err != null) {
                    send("Error: " + err);
                    break;
                }

                joined.remove(arg);

                RoomManager.notifyViewers(
                        arg, "* " + username + " left #" + arg, username);

                send("You left #" + arg + ".");

                if (arg.equals(currentRoom)) {
                    enterRoom("general");
                }

                break;
            }

            case "/members": {
                if (arg.isEmpty()) {
                    send("Usage: /members <room>");
                    break;
                }

                if (!RoomManager.isCreator(arg, userId)) {
                    send("Error: only the room creator can manage members.");
                    break;
                }

                Set<String> members =
                        new TreeSet<>(RoomManager.roomMembers(arg, userId));

                String encodedRoom = Base64.getEncoder().encodeToString(
                        arg.getBytes(StandardCharsets.UTF_8));

                StringBuilder result = new StringBuilder(MEMBERS_PREFIX);
                result.append(encodedRoom).append(":");

                boolean first = true;

                for (String member : members) {
                    if (!first) result.append(",");
                    first = false;

                    result.append(Base64.getEncoder().encodeToString(
                            member.getBytes(StandardCharsets.UTF_8)));
                }

                send(result.toString());
                break;
            }

            case "/kick": {
                String[] kickParts = line.split("\\s+", 3);

                if (kickParts.length != 3) {
                    send("Usage: /kick <room> <username>");
                    break;
                }

                String room = RoomManager.normalize(kickParts[1]);
                String targetUsername = kickParts[2].trim();

                String err = RoomManager.kickMember(
                        room, targetUsername, userId);

                if (err != null) {
                    send("Error: " + err);
                    break;
                }

                ClientHandler target = Server.online.get(targetUsername);

                if (target != null) {
                    target.forceLeaveRoom(
                            room, "You were removed from #" + room
                                    + " by the room creator.");
                }

                RoomManager.notifyViewers(
                        room, "* " + targetUsername
                                + " was removed from #" + room, username);

                send("You removed " + targetUsername + " from #" + room + ".");
                break;
            }

            case "/delete": {
                if (arg.isEmpty()) {
                    send("Usage: /delete <room>");
                    break;
                }

                String room = arg;

                if (!RoomManager.isCreator(room, userId)) {
                    send("Error: only the room creator can delete this room.");
                    break;
                }

                Set<ClientHandler> affected = ConcurrentHashMap.newKeySet();

                for (ClientHandler handler : Server.online.values()) {
                    if (handler.isMemberOf(room)) {
                        affected.add(handler);
                    }
                }

                String err = RoomManager.deleteRoom(room, userId);

                if (err != null) {
                    send("Error: " + err);
                    break;
                }

                for (ClientHandler handler : affected) {
                    handler.forceLeaveRoom(
                            room, "Room #" + room + " was deleted by its creator.");
                }

                send("Room #" + room + " deleted.");
                break;
            }

            default:
                send("Unknown command. Type /help.");
        }

        return true;
    }
}