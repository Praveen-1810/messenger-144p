import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.*;
import javax.swing.border.*;

public class Client {

    private static final String ROOM_PREFIX = "\u0001ROOM:";
    private static final String CODE_PREFIX = "\u0001CODE:";
    private static final String DM_EVENT_PREFIX = "\u0001DM:";
    private static final String DM_CONTACT_PREFIX = "\u0001DMC:";
    private static final String DM_LIST_END = "\u0001DMC_END";

    private static final int SERVER_PORT = 5000;
    private static final int DISCOVERY_PORT = 5001;
    private static final String DISCOVERY_REQUEST = "144P_DISCOVER";
    private static final String DISCOVERY_RESPONSE = "144P_SERVER:" + SERVER_PORT;

    private static final Color BACKGROUND = new Color(19, 17, 29);
    private static final Color SIDEBAR = new Color(25, 22, 38);
    private static final Color PANEL = new Color(31, 27, 46);
    private static final Color PURPLE = new Color(139, 92, 246);
    private static final Color TEXT = new Color(238, 234, 248);
    private static final Color MUTED = new Color(160, 151, 180);
    private static final Color GREEN = new Color(74, 222, 128);

    private JFrame frame;
    private CardLayout cardLayout;
    private JPanel mainPanel;

    private JTextField usernameField;
    private JPasswordField passwordField;
    private JLabel loginStatus;

    private JPanel messagesPanel;
    private JScrollPane chatScroll;

    private JTextField messageField;
    private JLabel roomLabel;
    private JLabel connectionLabel;
    private JLabel userLabel;

    private JPanel roomsListPanel;
    private JPanel dmListPanel;

    private final Map<String, Integer> dmUnreadCounts =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private final Map<String, RoomInfo> rooms =
            new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private JWindow dmPopup;
    private Timer popupTimer;

    // Incoming shared-code messages arrive as several socket lines.
    private boolean receivingCode = false;
    private String incomingCodeSender = "";
    private String incomingCodeLanguage = "";
    private StringBuilder incomingCode = new StringBuilder();

    // null means the client should find the server automatically.
    private String serverHost = null;
    private String currentUsername = "";
    private String currentRoom = "general";

    private Socket socket;
    private BufferedReader fromServer;
    private PrintWriter toServer;

    private volatile String authMode;
    private volatile String enteredUsername;
    private volatile String enteredPassword;

    private volatile boolean loggedIn = false;
    private volatile boolean connecting = false;

    private static class RoomInfo {
        String name;
        int members;
        boolean joined;
        boolean current;

        RoomInfo(String name, int members, boolean joined, boolean current) {
            this.name = name;
            this.members = members;
            this.joined = joined;
            this.current = current;
        }
    }

    public Client() {
        createWindow();
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : null;
        SwingUtilities.invokeLater(() -> new Client().showLogin(host));
    }

    private Image loadAppLogo() {
        ImageIcon icon = loadLogoIcon(64, 64);
        return icon == null ? null : icon.getImage();
    }

    private ImageIcon loadLogoIcon(int width, int height) {
    java.net.URL logoURL = Client.class.getResource("/144p_logo.png");

    Image originalImage = null;

    if (logoURL != null) {
        originalImage = new ImageIcon(logoURL).getImage();
    } else {
        File logoFile = new File("144p_logo.png");

        if (logoFile.isFile()) {
            originalImage = new ImageIcon(
                    logoFile.getAbsolutePath()).getImage();
        }
    }

    if (originalImage == null) {
        System.out.println("144P logo not found!");
        return null;
    }

    Image scaled = originalImage.getScaledInstance(
            width, height, Image.SCALE_SMOOTH);

    return new ImageIcon(scaled);
}

    // --------------------------------------------------
    // WINDOW
    // --------------------------------------------------

    private void createWindow() {
        frame = new JFrame("144P - Low resolution. High connection.");

        Image appLogo = loadAppLogo();
        if (appLogo != null) {
            frame.setIconImage(appLogo);
        }

        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1150, 750);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);

        mainPanel.add(createLoginPanel(), "LOGIN");
        mainPanel.add(createChatPanel(), "CHAT");

        frame.setContentPane(mainPanel);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
                frame.dispose();
            }
        });
    }

    private void showLogin(String host) {
        serverHost = host;
        frame.setVisible(true);
        loginStatus.setText(serverHost == null ? "The server will be found automatically." : "Connecting to " + serverHost + "...");
        loginStatus.setForeground(MUTED);
    }

    // --------------------------------------------------
    // LOGIN PANEL
    // --------------------------------------------------

    

private JPanel createLoginPanel() {
    JPanel root = new JPanel(new GridLayout(1, 2));
    root.setBackground(BACKGROUND);

    // LEFT PANEL: IMAGE ONLY
    ImageIcon sourceIcon = loadLogoIcon(800, 800);

Image sourceImage = sourceIcon != null
        ? sourceIcon.getImage()
        : null;

    JPanel imagePanel = new JPanel() {
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (sourceImage == null) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();

        g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        g2.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);

        int panelWidth = getWidth();
        int panelHeight = getHeight();

        int imageWidth = sourceImage.getWidth(this);
        int imageHeight = sourceImage.getHeight(this);

        if (imageWidth > 0 && imageHeight > 0) {
            double scale = Math.max(
                    (double) panelWidth / imageWidth,
                    (double) panelHeight / imageHeight);

            int drawWidth = (int) (imageWidth * scale);
            int drawHeight = (int) (imageHeight * scale);

            int x = (panelWidth - drawWidth) / 2;
            int y = (panelHeight - drawHeight) / 2;

            g2.drawImage(
                    sourceImage,
                    x, y,
                    drawWidth, drawHeight,
                    this);
        }

        g2.dispose();
    }
};

imagePanel.setBackground(BACKGROUND);

    // RIGHT PANEL: LOGIN FORM
    JPanel formArea = new JPanel(new GridBagLayout());
    formArea.setBackground(BACKGROUND);
    formArea.setBorder(new EmptyBorder(30, 25, 30, 25));

    JPanel card = new JPanel();
    card.setBackground(PANEL);
    card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

    card.setBorder(new CompoundBorder(
            new LineBorder(new Color(68, 55, 96), 1, true),
            new EmptyBorder(38, 38, 34, 38)
    ));

    JLabel welcome = new JLabel("Welcome back");
    welcome.setFont(new Font("SansSerif", Font.BOLD, 29));
    welcome.setForeground(TEXT);
    welcome.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel intro = new JLabel("Sign in to continue to 144P.");
    intro.setFont(new Font("SansSerif", Font.PLAIN, 13));
    intro.setForeground(MUTED);
    intro.setAlignmentX(Component.LEFT_ALIGNMENT);

    usernameField = createTextField("Username");
    usernameField.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 48));

    passwordField = new JPasswordField();
    styleTextField(passwordField);
    passwordField.setEchoChar('●');
    passwordField.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 48));

    JLabel user = createFieldLabel("USERNAME");
    JLabel pass = createFieldLabel("PASSWORD");

    loginStatus = new JLabel(
            "Connect to your messenger server.");
    loginStatus.setForeground(MUTED);
    loginStatus.setFont(new Font("SansSerif", Font.PLAIN, 12));
    loginStatus.setAlignmentX(Component.LEFT_ALIGNMENT);

    JButton loginButton = createButton("SIGN IN  →", PURPLE);
    loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    loginButton.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 48));

    JButton registerButton = createButton(
            "CREATE AN ACCOUNT",
            new Color(53, 45, 73));

    registerButton.setAlignmentX(Component.LEFT_ALIGNMENT);
    registerButton.setMaximumSize(
            new Dimension(Integer.MAX_VALUE, 44));

    loginButton.addActionListener(
            e -> startAuthentication("login"));

    registerButton.addActionListener(
            e -> startAuthentication("register"));

    card.add(welcome);
    card.add(Box.createVerticalStrut(8));
    card.add(intro);
    card.add(Box.createVerticalStrut(38));

    card.add(user);
    card.add(Box.createVerticalStrut(9));
    card.add(usernameField);
    card.add(Box.createVerticalStrut(22));

    card.add(pass);
    card.add(Box.createVerticalStrut(9));
    card.add(passwordField);
    card.add(Box.createVerticalStrut(28));

    card.add(loginButton);
    card.add(Box.createVerticalStrut(13));
    card.add(registerButton);
    card.add(Box.createVerticalStrut(22));
    card.add(loginStatus);

    card.setPreferredSize(new Dimension(430, 500));
    formArea.add(card);

    root.add(imagePanel);
    root.add(formArea);

    return root;
}


private JPanel createBrandFeature(
        String symbol, String heading, String description) {

    JPanel feature = new JPanel();
    feature.setOpaque(false);
    feature.setLayout(new BoxLayout(feature, BoxLayout.Y_AXIS));
    feature.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel title = new JLabel(symbol + "   " + heading);
    title.setForeground(Color.WHITE);
    title.setFont(new Font("SansSerif", Font.BOLD, 15));
    title.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel detail = new JLabel(description);
    detail.setForeground(new Color(213, 202, 235));
    detail.setFont(new Font("SansSerif", Font.PLAIN, 12));
    detail.setBorder(new EmptyBorder(5, 28, 0, 0));
    detail.setAlignmentX(Component.LEFT_ALIGNMENT);

    feature.add(title);
    feature.add(detail);

    return feature;
}


    // --------------------------------------------------
    // CHAT PANEL
    // --------------------------------------------------

    private JPanel createChatPanel() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BACKGROUND);

        root.add(createSidebar(), BorderLayout.WEST);
        root.add(createConversationPanel(), BorderLayout.CENTER);

        return root;
    }

    // --------------------------------------------------
    // SIDEBAR
    // --------------------------------------------------

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(SIDEBAR);
        sidebar.setPreferredSize(new Dimension(255, 0));
        sidebar.setBorder(new MatteBorder(
                0, 0, 0, 1, new Color(58, 49, 76)));

        // TOP WORKSPACE SECTION
        JPanel top = new JPanel();
        top.setBackground(SIDEBAR);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(20, 15, 10, 15));

        JLabel brand = new JLabel("✦  144P");
        brand.setForeground(PURPLE);
        brand.setFont(new Font("SansSerif", Font.BOLD, 22));

        JLabel subtitle = new JLabel("LOW RESOLUTION. HIGH CONNECTION.");
        subtitle.setForeground(MUTED);
        subtitle.setFont(new Font("SansSerif", Font.BOLD, 10));

        top.add(brand);
        top.add(Box.createVerticalStrut(3));
        top.add(subtitle);
        top.add(Box.createVerticalStrut(20));

        JLabel workspace = createSectionLabel("WORKSPACE");

        top.add(workspace);
        top.add(Box.createVerticalStrut(8));

        JButton general = createSideButton("#  General");
        JButton unread = createSideButton("●  Unread messages");
        JButton direct = createSideButton("✉  New direct message");

        general.addActionListener(e -> sendCommand("/switch general"));
        unread.addActionListener(e -> sendCommand("/unread"));

        direct.addActionListener(e -> {
            String target = JOptionPane.showInputDialog(
                    frame,
                    "Enter username:",
                    "New direct message",
                    JOptionPane.PLAIN_MESSAGE);

            if (target != null && !target.trim().isEmpty()) {
                openDirectMessage(target.trim());
            }
        });

        top.add(general);
        top.add(Box.createVerticalStrut(4));
        top.add(unread);
        top.add(Box.createVerticalStrut(4));
        top.add(direct);

        sidebar.add(top, BorderLayout.NORTH);

        // MIDDLE: ROOMS AND DMS
        JPanel middle = new JPanel();
        middle.setBackground(SIDEBAR);
        middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));

        // ROOMS SECTION
        JPanel roomsSection = new JPanel(new BorderLayout(0, 5));
        roomsSection.setBackground(SIDEBAR);
        roomsSection.setBorder(new EmptyBorder(8, 12, 8, 12));

        JPanel roomsHeader = new JPanel(new BorderLayout());
        roomsHeader.setBackground(SIDEBAR);

        JLabel roomsHeading = createSectionLabel("ROOMS");

        JButton refreshRooms = createSmallActionButton("↻");
        refreshRooms.setToolTipText("Refresh rooms");
        refreshRooms.addActionListener(e -> requestRooms());

        roomsHeader.add(roomsHeading, BorderLayout.WEST);
        roomsHeader.add(refreshRooms, BorderLayout.EAST);

        roomsListPanel = new JPanel();
        roomsListPanel.setLayout(new BoxLayout(roomsListPanel, BoxLayout.Y_AXIS));
        roomsListPanel.setBackground(SIDEBAR);

        JScrollPane roomsScroll = new JScrollPane(roomsListPanel);
        roomsScroll.setBorder(null);
        roomsScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        roomsScroll.getViewport().setBackground(SIDEBAR);
        roomsScroll.setPreferredSize(new Dimension(220, 155));
        roomsScroll.getVerticalScrollBar().setUnitIncrement(12);

        JButton createRoom = createSideButton("+  Create room");
        createRoom.addActionListener(e -> createRoomDialog());

        roomsSection.add(roomsHeader, BorderLayout.NORTH);
        roomsSection.add(roomsScroll, BorderLayout.CENTER);
        roomsSection.add(createRoom, BorderLayout.SOUTH);

        middle.add(roomsSection);

        JSeparator separator = new JSeparator();
        separator.setForeground(new Color(58, 49, 76));
        separator.setBackground(new Color(58, 49, 76));
        middle.add(separator);

        // DIRECT MESSAGES SECTION
        JPanel dmSection = new JPanel(new BorderLayout(0, 5));
        dmSection.setBackground(SIDEBAR);
        dmSection.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel dmHeading = createSectionLabel("DIRECT MESSAGES");

        dmListPanel = new JPanel();
        dmListPanel.setLayout(new BoxLayout(dmListPanel, BoxLayout.Y_AXIS));
        dmListPanel.setBackground(SIDEBAR);

        JScrollPane dmScroll = new JScrollPane(dmListPanel);
        dmScroll.setBorder(null);
        dmScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        dmScroll.getViewport().setBackground(SIDEBAR);
        dmScroll.getVerticalScrollBar().setUnitIncrement(12);

        dmSection.add(dmHeading, BorderLayout.NORTH);
        dmSection.add(dmScroll, BorderLayout.CENTER);

        middle.add(dmSection);

        sidebar.add(middle, BorderLayout.CENTER);

        // BOTTOM SECTION
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(new Color(35, 30, 49));
        bottom.setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel status = new JLabel("●  Connected");
        status.setForeground(GREEN);
        status.setFont(new Font("SansSerif", Font.BOLD, 12));

        userLabel = new JLabel(" ");
        userLabel.setForeground(MUTED);
        userLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));

        JPanel userPanel = new JPanel();
        userPanel.setBackground(new Color(35, 30, 49));
        userPanel.setLayout(new BoxLayout(userPanel, BoxLayout.Y_AXIS));
        userPanel.add(status);
        userPanel.add(Box.createVerticalStrut(5));
        userPanel.add(userLabel);

        JButton logout = new JButton("Logout");
        styleSmallButton(logout);

        logout.addActionListener(e -> {
            sendCommand("/quit");
            disconnect();
            cardLayout.show(mainPanel, "LOGIN");
            loginStatus.setText("You have disconnected.");
            loginStatus.setForeground(MUTED);
        });

        bottom.add(userPanel, BorderLayout.CENTER);
        bottom.add(logout, BorderLayout.SOUTH);

        sidebar.add(bottom, BorderLayout.SOUTH);

        refreshRoomsList();
        refreshDmList();

        return sidebar;
    }

    // --------------------------------------------------
    // ROOMS
    // --------------------------------------------------

    private void requestRooms() {
        if (!loggedIn) return;

        rooms.clear();
        refreshRoomsList();
        sendCommand("/rooms");
    }

    private void createRoomDialog() {
        if (!loggedIn) return;

        String name = JOptionPane.showInputDialog(
                frame,
                "Enter a room name:",
                "Create room",
                JOptionPane.PLAIN_MESSAGE);

        if (name == null || name.trim().isEmpty()) {
            return;
        }

        String roomName = name.trim().toLowerCase();

        if (roomName.startsWith("dm_") || roomName.contains(" ")) {
            JOptionPane.showMessageDialog(
                    frame,
                    "Use a room name without spaces. Names starting with dm_ are reserved.");
            return;
        }

        sendCommand("/create " + roomName);
    }

    private void refreshRoomsList() {
        if (roomsListPanel == null) return;

        roomsListPanel.removeAll();

        if (rooms.isEmpty()) {
            JLabel empty = new JLabel("  No rooms loaded");
            empty.setForeground(MUTED);
            empty.setFont(new Font("SansSerif", Font.PLAIN, 11));
            empty.setBorder(new EmptyBorder(8, 5, 8, 5));
            roomsListPanel.add(empty);
        } else {
            for (RoomInfo room : rooms.values()) {

                String label = room.current
                        ? "●  # " + room.name
                        : "   # " + room.name;

                if (room.joined) {
                    label += "  (" + room.members + ")";
                } else {
                    label += "  +";
                }

                JButton roomButton = createSideButton(label);

                roomButton.setToolTipText(
                        room.joined
                                ? "Switch to #" + room.name
                                : "Join #" + room.name);

                if (room.current) {
                    roomButton.setForeground(PURPLE);
                }

                roomButton.addActionListener(e -> {
                    if (room.joined) {
                        sendCommand("/switch " + room.name);
                    } else {
                        sendCommand("/join " + room.name);
                    }
                });

                roomsListPanel.add(roomButton);
                roomsListPanel.add(Box.createVerticalStrut(2));
            }
        }

        roomsListPanel.revalidate();
        roomsListPanel.repaint();
    }

    /*
     * Server /rooms response example:
     *
     * Group rooms (> current, * joined):
     *   >* #general (3 members)
     *    * #java (4 members)
     *      #python (2 members)
     */
    private void processRoomListLine(String line) {
        if (line == null) return;

        String trimmed = line.trim();

        if (!trimmed.contains("#") || !trimmed.contains("members)")) {
            return;
        }

        int hashIndex = trimmed.indexOf('#');
        int openParen = trimmed.lastIndexOf('(');
        int closeParen = trimmed.lastIndexOf(" members)");

        if (hashIndex < 0 || openParen < 0 || closeParen < openParen) {
            return;
        }

        String prefix = trimmed.substring(0, hashIndex);
        String name = trimmed.substring(hashIndex + 1, openParen).trim();

        if (name.isEmpty()) return;

        int memberCount;

        try {
            String count = trimmed.substring(openParen + 1, closeParen).trim();
            memberCount = Integer.parseInt(count);
        } catch (NumberFormatException e) {
            return;
        }

        boolean isCurrent = prefix.contains(">");
        boolean isJoined = prefix.contains("*");

        rooms.put(name, new RoomInfo(
                name, memberCount, isJoined, isCurrent));

        refreshRoomsList();
    }

    // --------------------------------------------------
    // DIRECT MESSAGES
    // --------------------------------------------------

    private void refreshDmList() {
        if (dmListPanel == null) return;

        dmListPanel.removeAll();

        if (dmUnreadCounts.isEmpty()) {
            JLabel empty = new JLabel("  No conversations yet");
            empty.setForeground(MUTED);
            empty.setFont(new Font("SansSerif", Font.PLAIN, 11));
            empty.setBorder(new EmptyBorder(8, 5, 8, 5));
            dmListPanel.add(empty);
        } else {
            for (Map.Entry<String, Integer> entry
                    : dmUnreadCounts.entrySet()) {

                String username = entry.getKey();
                int unread = entry.getValue();

                String label = "✉  " + username;

                if (unread > 0) {
                    label += "   (" + unread + ")";
                }

                JButton contact = createSideButton(label);
                contact.setToolTipText("Open chat with " + username);

                contact.addActionListener(e ->
                        openDirectMessage(username));

                dmListPanel.add(contact);
                dmListPanel.add(Box.createVerticalStrut(3));
            }
        }

        dmListPanel.revalidate();
        dmListPanel.repaint();
    }

    private void addOrUpdateDM(String username, int unread) {
        if (username == null || username.trim().isEmpty()) return;

        dmUnreadCounts.put(username, Math.max(0, unread));
        refreshDmList();
    }

    private void openDirectMessage(String username) {
        if (!loggedIn || username == null || username.trim().isEmpty()) {
            return;
        }

        sendCommand("/msg " + username.trim());
    }

    // --------------------------------------------------
    // DM POPUP
    // --------------------------------------------------

    private void showDMNotification(String sender) {
        if (sender == null || sender.trim().isEmpty()) return;

        int unread = dmUnreadCounts.getOrDefault(sender, 0);
        addOrUpdateDM(sender, unread + 1);

        hideDMPopup();

        dmPopup = new JWindow(frame);

        JButton popupButton = new JButton("✉  Msg from " + sender);
        popupButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        popupButton.setForeground(TEXT);
        popupButton.setBackground(new Color(55, 43, 82));
        popupButton.setFocusPainted(false);
        popupButton.setBorder(new CompoundBorder(
                new LineBorder(PURPLE, 1, true),
                new EmptyBorder(8, 12, 8, 12)));
        popupButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        popupButton.setHorizontalAlignment(SwingConstants.LEFT);

        popupButton.addActionListener(e -> {
            hideDMPopup();
            openDirectMessage(sender);
        });

        dmPopup.setContentPane(popupButton);
        dmPopup.setSize(205, 42);

        Point location = frame.getLocationOnScreen();

        int x = location.x + frame.getWidth() - dmPopup.getWidth() - 20;
        int y = location.y + 65;

        dmPopup.setLocation(x, y);
        dmPopup.setVisible(true);

        popupTimer = new Timer(3000, e -> hideDMPopup());
        popupTimer.setRepeats(false);
        popupTimer.start();
    }

    private void hideDMPopup() {
        if (popupTimer != null) {
            popupTimer.stop();
            popupTimer = null;
        }

        if (dmPopup != null) {
            dmPopup.setVisible(false);
            dmPopup.dispose();
            dmPopup = null;
        }
    }

    // --------------------------------------------------
    // CONVERSATION PANEL
    // --------------------------------------------------

    private JPanel createConversationPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BACKGROUND);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PANEL);
        header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(58, 49, 76)),
                new EmptyBorder(18, 24, 18, 24)));

        roomLabel = new JLabel("# general");
        roomLabel.setForeground(TEXT);
        roomLabel.setFont(new Font("SansSerif", Font.BOLD, 19));

        connectionLabel = new JLabel("● Connected");
        connectionLabel.setForeground(GREEN);
        connectionLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

        header.add(roomLabel, BorderLayout.WEST);
        header.add(connectionLabel, BorderLayout.EAST);

        messagesPanel = new JPanel();
        messagesPanel.setLayout(new BoxLayout(messagesPanel, BoxLayout.Y_AXIS));
        messagesPanel.setBackground(BACKGROUND);
        messagesPanel.setBorder(new EmptyBorder(18, 22, 18, 22));

        chatScroll = new JScrollPane(
                messagesPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        chatScroll.setBorder(null);
        chatScroll.getViewport().setBackground(BACKGROUND);
        chatScroll.getVerticalScrollBar().setUnitIncrement(18);

        JPanel inputPanel = new JPanel(new BorderLayout(10, 0));
        inputPanel.setBackground(PANEL);
        inputPanel.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, new Color(58, 49, 76)),
                new EmptyBorder(15, 18, 15, 18)));

        messageField = new JTextField();
        messageField.setBackground(new Color(42, 36, 58));
        messageField.setForeground(TEXT);
        messageField.setCaretColor(Color.WHITE);
        messageField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        messageField.setBorder(new CompoundBorder(
                new LineBorder(new Color(78, 65, 105), 1, true),
                new EmptyBorder(12, 14, 12, 14)));

        JButton codeButton = createButton(" { } ", new Color(75, 59, 108));
        JButton sendButton = createButton("SEND", PURPLE);

        sendButton.setPreferredSize(new Dimension(100, 43));
        codeButton.setPreferredSize(new Dimension(65, 43));

        sendButton.addActionListener(e -> sendMessage());
        messageField.addActionListener(e -> sendMessage());
        codeButton.addActionListener(e -> openCodeDialog());

        inputPanel.add(codeButton, BorderLayout.WEST);
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);

        panel.add(header, BorderLayout.NORTH);
        panel.add(chatScroll, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.SOUTH);

        return panel;
    }

    // --------------------------------------------------
    // AUTHENTICATION
    // --------------------------------------------------

    private void startAuthentication(String mode) {
        if (connecting) return;

        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            loginStatus.setText("Enter your username and password.");
            loginStatus.setForeground(new Color(248, 113, 113));
            return;
        }

        enteredUsername = username;
        enteredPassword = password;
        authMode = mode;
        connecting = true;

        loginStatus.setText("Connecting to server...");
        loginStatus.setForeground(PURPLE);

        new Thread(() -> {
            try {
                String host = serverHost;

                if (host == null || host.trim().isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        loginStatus.setText("Searching for 144P server on your Wi-Fi...");
                        loginStatus.setForeground(PURPLE);
                    });

                    host = discoverServer();
                }

                socket = new Socket(host, SERVER_PORT);

                fromServer = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));

                toServer = new PrintWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8), true);

                startReaderThread();

            } catch (IOException e) {
                connecting = false;
                SwingUtilities.invokeLater(() -> {
                    loginStatus.setText(
                            "Could not connect. Check that the server is running.");
                    loginStatus.setForeground(new Color(248, 113, 113));
                });
            }
        }, "Messenger-Connector").start();
    }

    /*
     * Finds a 144P server on the same Wi-Fi network.
     * The server listens for UDP discovery requests on port 5001
     * and replies directly to this computer with its TCP port.
     */
    private String discoverServer() throws IOException {
        byte[] requestData = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
        byte[] responseData = new byte[128];

        try (DatagramSocket discoverySocket = new DatagramSocket()) {
            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(1200);

            DatagramPacket request = new DatagramPacket(
                    requestData,
                    requestData.length,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT);

            // Retry a few times in case the first broadcast is lost.
            for (int attempt = 0; attempt < 4; attempt++) {
                discoverySocket.send(request);

                try {
                    DatagramPacket response = new DatagramPacket(
                            responseData, responseData.length);

                    discoverySocket.receive(response);

                    String reply = new String(
                            response.getData(),
                            response.getOffset(),
                            response.getLength(),
                            StandardCharsets.UTF_8).trim();

                    if (reply.equals(DISCOVERY_RESPONSE)) {
                        return response.getAddress().getHostAddress();
                    }
                } catch (SocketTimeoutException ignored) {
                    // Send the discovery request again.
                }
            }
        }

        // Also allow the client to run on the same computer as the server.
        try (Socket localTest = new Socket("127.0.0.1", SERVER_PORT)) {
            return "127.0.0.1";
        } catch (IOException ignored) {
            throw new IOException(
                    "Could not find a 144P server. Make sure the server is running "
                    + "and both computers are connected to the same Wi-Fi.");
        }
    }

    private void startReaderThread() {
        Thread reader = new Thread(() -> {
            try {
                String line;

                while ((line = fromServer.readLine()) != null) {
                    processServerLine(line);
                }

            } catch (IOException ignored) {
            }

            connecting = false;
            loggedIn = false;

            SwingUtilities.invokeLater(() -> {
                connectionLabel.setText("● Disconnected");
                connectionLabel.setForeground(new Color(248, 113, 113));
                hideDMPopup();
            });
        }, "Messenger-Reader");

        reader.setDaemon(true);
        reader.start();
    }

    // --------------------------------------------------
    // SERVER RESPONSE HANDLING
    // --------------------------------------------------

    private void processServerLine(String line) {

        // Code messages are sent by the server as a header, code lines,
        // and an END CODE footer. Collect them before displaying the card.
        if (receivingCode) {
            if (line.trim().equals("========== END CODE ==========")) {
                String code = incomingCode.toString();
                String sender = incomingCodeSender;
                String language = incomingCodeLanguage;

                receivingCode = false;
                incomingCode.setLength(0);
                incomingCodeSender = "";
                incomingCodeLanguage = "";

                SwingUtilities.invokeLater(() ->
                        addCodeMessage(sender, language, code));
            } else {
                if (incomingCode.length() > 0) {
                    incomingCode.append("\n");
                }
                incomingCode.append(line);
            }
            return;
        }

        // Code history is prefixed by a timestamp on its own socket line.
        // Ignore that timestamp-only line; the following line is the code header.
        String trimmedLine = line.trim();
        if (trimmedLine.matches("\\[.*\\]\\s*")) {
            return;
        }

        // Example: ========== loki shared JAVA code ==========
        String codeHeader = trimmedLine;
        if (codeHeader.startsWith("========== ")
                && codeHeader.endsWith(" code ==========")
                && codeHeader.contains(" shared ")) {

            int sharedIndex = codeHeader.indexOf(" shared ");
            int codeSuffix = codeHeader.length() - " code ==========".length();

            if (sharedIndex > "========== ".length()
                    && codeSuffix > sharedIndex + " shared ".length()) {
                incomingCodeSender = codeHeader.substring(
                        "========== ".length(), sharedIndex).trim();
                incomingCodeLanguage = codeHeader.substring(
                        sharedIndex + " shared ".length(), codeSuffix).trim();
                receivingCode = true;
                incomingCode.setLength(0);
                return;
            }
        }

        if ("Choose:".equals(line)) {
            sendLine("register".equals(authMode) ? "1" : "2");
            return;
        }

        if ("Choose a username:".equals(line)
                || "Username:".equals(line)) {
            sendLine(enteredUsername);
            return;
        }

        if ("Choose a password:".equals(line)
                || "Password:".equals(line)) {
            sendLine(enteredPassword);
            return;
        }

        if (line.startsWith(DM_CONTACT_PREFIX)) {
            processDMContact(line);
            return;
        }

        if (line.equals(DM_LIST_END)) {
            return;
        }

        if (line.startsWith(DM_EVENT_PREFIX)) {
            processDMEvent(line);
            return;
        }

        if (line.startsWith("Account created!")) {
            authMode = "login";

            SwingUtilities.invokeLater(() -> {
                loginStatus.setText("Account created! Now logging in...");
                loginStatus.setForeground(GREEN);
            });
            return;
        }

        if (line.startsWith("Error:")) {
            SwingUtilities.invokeLater(() -> {
                if (!loggedIn) {
                    loginStatus.setText(line);
                    loginStatus.setForeground(new Color(248, 113, 113));
                } else {
                    appendMessage(line, "system");
                }
            });
            return;
        }

        if (line.startsWith("Logged in as ")) {
            currentUsername = enteredUsername;
            loggedIn = true;
            connecting = false;

            SwingUtilities.invokeLater(() -> {
                userLabel.setText("  " + currentUsername);
                cardLayout.show(mainPanel, "CHAT");
                appendMessage("Welcome to 144P! Low resolution. High connection.", "system");
                appendMessage("You are logged in as " + enteredUsername, "system");
                requestRooms();
            });
            return;
        }

        if (line.startsWith(ROOM_PREFIX)) {
            currentRoom = line.substring(ROOM_PREFIX.length());

            SwingUtilities.invokeLater(() -> {
                if (currentRoom.startsWith("DM:")) {
                    String contact = currentRoom.substring(3);
                    roomLabel.setText("✉  " + contact);
                    dmUnreadCounts.put(contact, 0);
                    refreshDmList();
                } else {
                    roomLabel.setText("#  " + currentRoom);
                }

                requestRooms();
            });
            return;
        }

        if (line.equals("Goodbye!")) return;

        if (loggedIn) {
            processRoomListLine(line);

            // Room list lines are handled by the sidebar, not chat.
            if (isRoomListLine(line)) {
                return;
            }

            SwingUtilities.invokeLater(() -> appendMessage(line, "normal"));

            // Refresh the room list after successful room operations.
            if (line.startsWith("Room #")
                    || line.startsWith("You left #")
                    || line.startsWith("Error: Room")
                    || line.startsWith("Error: you haven't joined")) {

                SwingUtilities.invokeLater(this::requestRooms);
            }

        } else {
            SwingUtilities.invokeLater(() -> {
                if (!line.isBlank()) loginStatus.setText(line);
            });
        }
    }

    private boolean isRoomListLine(String line) {
        String trimmed = line.trim();

        return trimmed.startsWith("Group rooms")
                || (trimmed.contains("#") && trimmed.contains("members)"));
    }

    private void processDMContact(String line) {
        try {
            String data = line.substring(DM_CONTACT_PREFIX.length());
            int separator = data.lastIndexOf(':');

            if (separator < 0) return;

            String encodedUsername = data.substring(0, separator);
            int unread = Integer.parseInt(data.substring(separator + 1));

            String username = new String(
                    Base64.getDecoder().decode(encodedUsername),
                    StandardCharsets.UTF_8);

            SwingUtilities.invokeLater(() ->
                    addOrUpdateDM(username, unread));

        } catch (Exception e) {
            System.out.println("Invalid DM contact event: " + e.getMessage());
        }
    }

    private void processDMEvent(String line) {
        try {
            String encodedUsername =
                    line.substring(DM_EVENT_PREFIX.length());

            String sender = new String(
                    Base64.getDecoder().decode(encodedUsername),
                    StandardCharsets.UTF_8);

            SwingUtilities.invokeLater(() -> {
                if (!currentRoom.equalsIgnoreCase("DM:" + sender)) {
                    showDMNotification(sender);
                }
            });

        } catch (Exception e) {
            System.out.println("Invalid DM notification: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // SEND MESSAGES
    // --------------------------------------------------

    private void sendMessage() {
        String message = messageField.getText().trim();

        if (message.isEmpty() || !loggedIn) return;

        sendLine(message);
        messageField.setText("");
    }

    private void sendCommand(String command) {
        if (loggedIn) sendLine(command);
    }

    private void sendLine(String message) {
        PrintWriter writer = toServer;

        if (writer != null && !writer.checkError()) {
            synchronized (writer) {
                writer.println(message);
            }
        }
    }

    // --------------------------------------------------
    // CODE SHARING
    // --------------------------------------------------

    private void openCodeDialog() {
        if (!loggedIn) return;

        JDialog dialog = new JDialog(frame, "Share code", true);
        dialog.setSize(650, 500);
        dialog.setLocationRelativeTo(frame);
        dialog.getContentPane().setBackground(PANEL);
        dialog.setLayout(new BorderLayout(12, 12));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.setBackground(PANEL);
        top.setBorder(new EmptyBorder(12, 12, 0, 12));

        JLabel languageLabel = new JLabel("Language:");
        languageLabel.setForeground(TEXT);

        String[] languages = {
            "java", "python", "c", "cpp",
            "javascript", "html", "css", "sql"
        };

        JComboBox<String> languageBox = new JComboBox<>(languages);
        languageBox.setBackground(new Color(42, 36, 58));
        languageBox.setForeground(TEXT);

        top.add(languageLabel);
        top.add(languageBox);

        JTextArea codeArea = new JTextArea();
        codeArea.setBackground(new Color(16, 14, 24));
        codeArea.setForeground(new Color(211, 190, 255));
        codeArea.setCaretColor(Color.WHITE);
        codeArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        codeArea.setTabSize(4);
        codeArea.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane codeScroll = new JScrollPane(codeArea);
        codeScroll.setBorder(new EmptyBorder(0, 12, 0, 12));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.setBackground(PANEL);
        bottom.setBorder(new EmptyBorder(0, 12, 12, 12));

        JButton cancel = createButton("CANCEL", new Color(65, 55, 82));
        JButton share = createButton("SHARE CODE", PURPLE);

        cancel.addActionListener(e -> dialog.dispose());

        share.addActionListener(e -> {
            String code = codeArea.getText();
            String language = (String) languageBox.getSelectedItem();

            if (code.isBlank()) {
                JOptionPane.showMessageDialog(dialog, "Enter some code first.");
                return;
            }

            String encoded = Base64.getEncoder().encodeToString(
                    code.getBytes(StandardCharsets.UTF_8));

            sendLine(CODE_PREFIX + language + ":" + encoded);
            dialog.dispose();
        });

        bottom.add(cancel);
        bottom.add(share);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(codeScroll, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    // --------------------------------------------------
    // MESSAGE DISPLAY
    // --------------------------------------------------

    private void appendMessage(String message, String type) {
        if (messagesPanel == null || message == null || message.isEmpty()) return;

        String sender = "";
        String body = message;
        boolean chatMessage = false;

        if (!"system".equals(type)
                && !message.startsWith("* ")
                && !message.startsWith("Error:")
                && !message.startsWith("---")
                && !message.startsWith("==========")
                && !message.startsWith("  ")
                && !message.startsWith("No unread")
                && !message.startsWith("Total unread")) {

            String candidate = message;

            if (candidate.startsWith("[")) {
                int timestampEnd = candidate.indexOf("] ");
                if (timestampEnd >= 0) {
                    candidate = candidate.substring(timestampEnd + 2);
                }
            }

            int separator = candidate.indexOf(": ");

            if (separator > 0) {
                String possibleSender =
                        candidate.substring(0, separator).trim();

                if (!possibleSender.isEmpty()
                        && possibleSender.length() <= 40
                        && !possibleSender.contains(" ")) {
                    sender = possibleSender;
                    body = candidate.substring(separator + 2);
                    chatMessage = true;
                }
            }
        }

        if (chatMessage) {
            addChatBubble(sender, body,
                    sender.equalsIgnoreCase(currentUsername));
        } else {
            addSystemMessage(message);
        }
    }

    // --------------------------------------------------
    // SHARED CODE MESSAGE CARD
    // --------------------------------------------------

    private void addCodeMessage(String sender, String language, String code) {
        if (messagesPanel == null) return;

        int viewportWidth = chatScroll.getViewport().getWidth();
        if (viewportWidth <= 0) viewportWidth = 700;

        int cardWidth = Math.max(260, Math.min(760, viewportWidth - 70));

        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(new Color(27, 23, 39));
        card.setBorder(new CompoundBorder(
                new LineBorder(new Color(91, 67, 132), 1, true),
                new EmptyBorder(12, 14, 12, 14)));
        card.setPreferredSize(new Dimension(cardWidth, 250));
        card.setMinimumSize(new Dimension(220, 170));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 420));

        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);

        JLabel title = new JLabel(sender + "  shared  " + language.toUpperCase());
        title.setForeground(new Color(192, 132, 252));
        title.setFont(new Font("SansSerif", Font.BOLD, 12));

        JPanel headingActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headingActions.setOpaque(false);

        JLabel codeLabel = new JLabel("CODE");
        codeLabel.setForeground(MUTED);
        codeLabel.setFont(new Font("SansSerif", Font.BOLD, 9));

        JButton copyButton = createButton("COPY", new Color(75, 59, 108));
        copyButton.setFont(new Font("SansSerif", Font.BOLD, 10));
        copyButton.setBorder(new EmptyBorder(6, 12, 6, 12));

        copyButton.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new StringSelection(code), null);

                copyButton.setText("COPIED!");

                Timer timer = new Timer(
                        1500, event -> copyButton.setText("COPY"));

                timer.setRepeats(false);
                timer.start();

            } catch (IllegalStateException ex) {
                JOptionPane.showMessageDialog(
                        frame,
                        "Clipboard is currently unavailable.",
                        "Copy failed",
                        JOptionPane.WARNING_MESSAGE);
            }
        });

        headingActions.add(codeLabel);
        headingActions.add(copyButton);

        heading.add(title, BorderLayout.WEST);
        heading.add(headingActions, BorderLayout.EAST);

        JTextArea codeArea = new JTextArea(code);
        codeArea.setEditable(false);
        codeArea.setFocusable(true);
        codeArea.setLineWrap(false);
        codeArea.setWrapStyleWord(false);
        codeArea.setTabSize(4);
        codeArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        codeArea.setForeground(new Color(225, 213, 250));
        codeArea.setBackground(new Color(15, 13, 22));
        codeArea.setCaretColor(Color.WHITE);
        codeArea.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane codeScroll = new JScrollPane(
                codeArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        codeScroll.setBorder(new LineBorder(new Color(57, 48, 75), 1));
        codeScroll.getViewport().setBackground(new Color(15, 13, 22));
        codeScroll.getVerticalScrollBar().setUnitIncrement(14);
        codeScroll.setPreferredSize(new Dimension(cardWidth - 30, 205));

        card.add(heading, BorderLayout.NORTH);
        card.add(codeScroll, BorderLayout.CENTER);

        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BACKGROUND);
        row.setBorder(new EmptyBorder(7, 4, 7, 4));
        row.add(card, BorderLayout.WEST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 280));

        messagesPanel.add(row);
        messagesPanel.add(Box.createVerticalStrut(5));

        refreshMessages();
    }

    // --------------------------------------------------
    // CHAT BUBBLES
    // --------------------------------------------------

    private void addChatBubble(String sender, String message, boolean outgoing) {

        int viewportWidth = chatScroll.getViewport().getWidth();
        if (viewportWidth <= 0) viewportWidth = 700;

        int maxBubbleWidth = Math.min(480, Math.max(180, viewportWidth - 100));
        int horizontalPadding = 28;
        int maxTextWidth = maxBubbleWidth - horizontalPadding;

        Font font = new Font("SansSerif", Font.PLAIN, 13);
        FontMetrics metrics = messagesPanel.getFontMetrics(font);
        int naturalWidth = 75;

        String[] originalLines = message.split("\n", -1);

        for (String line : originalLines) {
            naturalWidth = Math.max(
                    naturalWidth, metrics.stringWidth(line) + 2);
        }

        int textWidth = Math.min(maxTextWidth, naturalWidth);
        textWidth = Math.max(40, textWidth);

        JTextArea text = new JTextArea();
        text.setText(message);
        text.setFont(font);
        text.setForeground(TEXT);
        text.setBackground(new Color(0, 0, 0, 0));
        text.setOpaque(false);
        text.setEditable(false);
        text.setFocusable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBorder(null);
        text.setMargin(new Insets(0, 0, 0, 0));

        text.setSize(new Dimension(textWidth, Short.MAX_VALUE));

        Dimension calculated = text.getPreferredSize();
        int textHeight = calculated.height + 8;

        text.setPreferredSize(new Dimension(textWidth, textHeight));
        text.setMinimumSize(new Dimension(textWidth, textHeight));
        text.setMaximumSize(new Dimension(textWidth, textHeight));

        int bubbleWidth = textWidth + horizontalPadding;
        int bubbleHeight = textHeight + 24;

        Color bubbleColor = outgoing
                ? new Color(91, 61, 151)
                : new Color(43, 37, 59);

        RoundedPanel bubble = new RoundedPanel(bubbleColor, 18);
        bubble.setLayout(new BorderLayout());
        bubble.setBorder(new EmptyBorder(12, 14, 12, 14));

        bubble.setPreferredSize(new Dimension(bubbleWidth, bubbleHeight));
        bubble.setMinimumSize(new Dimension(bubbleWidth, bubbleHeight));
        bubble.setMaximumSize(new Dimension(bubbleWidth, bubbleHeight));

        bubble.add(text, BorderLayout.CENTER);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        if (!outgoing) {
            JLabel senderLabel = new JLabel(sender);
            senderLabel.setForeground(new Color(190, 170, 225));
            senderLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
            senderLabel.setBorder(new EmptyBorder(0, 5, 4, 5));
            senderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            content.add(senderLabel);
        }

        content.add(bubble);

        int contentHeight = bubbleHeight + (outgoing ? 0 : 19);

        content.setPreferredSize(new Dimension(bubbleWidth, contentHeight));
        content.setMinimumSize(new Dimension(bubbleWidth, contentHeight));
        content.setMaximumSize(new Dimension(bubbleWidth, contentHeight));

        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BACKGROUND);
        row.setBorder(new EmptyBorder(5, 4, 5, 4));

        if (outgoing) {
            row.add(content, BorderLayout.EAST);
        } else {
            row.add(content, BorderLayout.WEST);
        }

        int rowHeight = contentHeight + 10;

        row.setPreferredSize(new Dimension(
                Math.max(100, viewportWidth - 44), rowHeight));
        row.setMinimumSize(new Dimension(100, rowHeight));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowHeight));

        messagesPanel.add(row);
        messagesPanel.add(Box.createVerticalStrut(5));

        refreshMessages();
    }

    // --------------------------------------------------
    // SYSTEM MESSAGES
    // --------------------------------------------------

    private void addSystemMessage(String message) {
        JLabel label = new JLabel(
                "<html><div style='text-align:center;'>"
                + escapeHtml(message).replace("\n", "<br>")
                + "</div></html>");

        label.setForeground(systemMessageColor(message));
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setBorder(new EmptyBorder(7, 8, 7, 8));

        messagesPanel.add(label);
        messagesPanel.add(Box.createVerticalStrut(4));

        refreshMessages();
    }

    private Color systemMessageColor(String message) {
        if (message.startsWith("Error:")) {
            return new Color(248, 113, 113);
        }

        if (message.startsWith("* ")
                || message.startsWith("---")
                || message.startsWith("==========")) {
            return new Color(192, 132, 252);
        }

        return MUTED;
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // --------------------------------------------------
    // REFRESH AND SCROLL
    // --------------------------------------------------

    private void refreshMessages() {
        messagesPanel.revalidate();
        messagesPanel.repaint();

        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = chatScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // --------------------------------------------------
    // ROUNDED PANEL
    // --------------------------------------------------

    private static class RoundedPanel extends JPanel {

        private final Color backgroundColor;
        private final int cornerRadius;

        RoundedPanel(Color backgroundColor, int cornerRadius) {
            this.backgroundColor = backgroundColor;
            this.cornerRadius = cornerRadius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(backgroundColor);
            g2.fillRoundRect(
                    0, 0, getWidth(), getHeight(),
                    cornerRadius, cornerRadius);
                
                
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // --------------------------------------------------
    // UI HELPERS
    // --------------------------------------------------

    private JLabel createSectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font("SansSerif", Font.BOLD, 10));
        return label;
    }

    private JButton createSmallActionButton(String text) {
        JButton button = new JButton(text);
        button.setForeground(MUTED);
        button.setBackground(SIDEBAR);
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(3, 8, 3, 8));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return button;
    }

    private JTextField createTextField(String placeholder) {
        JTextField field = new JTextField();
        field.setToolTipText(placeholder);
        styleTextField(field);
        return field;
    }

    private void styleTextField(JTextField field) {
        field.setPreferredSize(new Dimension(300, 43));
        field.setMaximumSize(new Dimension(300, 43));
        field.setBackground(new Color(42, 36, 58));
        field.setForeground(TEXT);
        field.setCaretColor(Color.WHITE);
        field.setFont(new Font("SansSerif", Font.PLAIN, 14));

        field.setBorder(new CompoundBorder(
                new LineBorder(new Color(78, 65, 105), 1, true),
                new EmptyBorder(10, 12, 10, 12)));
    }

    private JLabel createFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font("SansSerif", Font.BOLD, 10));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JButton createButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(Color.WHITE);
        button.setFont(new Font("SansSerif", Font.BOLD, 12));
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(12, 18, 12, 18));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setOpaque(true);
        return button;
    }

    private JButton createSideButton(String text) {
        JButton button = new JButton(text);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setBackground(SIDEBAR);
        button.setForeground(MUTED);
        button.setFont(new Font("SansSerif", Font.PLAIN, 12));
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(9, 9, 9, 5));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setMaximumSize(new Dimension(225, 38));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(48, 40, 68));
                if (!button.getForeground().equals(PURPLE)) {
                    button.setForeground(TEXT);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(SIDEBAR);
                if (!button.getForeground().equals(PURPLE)) {
                    button.setForeground(MUTED);
                }
            }
        });

        return button;
    }

    private void styleSmallButton(JButton button) {
        button.setBackground(new Color(55, 44, 73));
        button.setForeground(TEXT);
        button.setFont(new Font("SansSerif", Font.BOLD, 11));
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(8, 12, 8, 12));
    }

    // --------------------------------------------------
    // DISCONNECT
    // --------------------------------------------------

    private void disconnect() {
        loggedIn = false;
        connecting = false;

        hideDMPopup();

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }

        socket = null;
        fromServer = null;
        toServer = null;
    }
}