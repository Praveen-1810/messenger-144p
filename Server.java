import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Starts the database, discovers clients over Wi-Fi, and accepts chat connections. */
public class Server {
    public static final int PORT = 5000;
    public static final int DISCOVERY_PORT = 5001;

    private static final String DISCOVERY_REQUEST = "144P_DISCOVER";
    private static final String DISCOVERY_RESPONSE = "144P_SERVER:" + PORT;

    // Everyone currently logged in: username -> their handler
    static final Map<String, ClientHandler> online = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Database.init();

        // Run UDP discovery separately so it does not block TCP chat connections.
        Thread discoveryThread = new Thread(
                Server::startDiscoveryService, "144P-Discovery");
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("144P server running on TCP port " + PORT);
            System.out.println("Wi-Fi discovery running on UDP port " + DISCOVERY_PORT);
            System.out.println("Waiting for connections...");

            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(new ClientHandler(socket)).start();
            }
        }
    }

    private static void startDiscoveryService() {
        byte[] buffer = new byte[128];

        try (DatagramSocket discoverySocket =
                     new DatagramSocket(DISCOVERY_PORT, (InetAddress) null)) {

            discoverySocket.setBroadcast(true);

            while (true) {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                discoverySocket.receive(request);

                String message = new String(
                        request.getData(),
                        request.getOffset(),
                        request.getLength(),
                        StandardCharsets.UTF_8).trim();

                if (DISCOVERY_REQUEST.equals(message)) {
                    byte[] responseData =
                            DISCOVERY_RESPONSE.getBytes(StandardCharsets.UTF_8);

                    DatagramPacket response = new DatagramPacket(
                            responseData,
                            responseData.length,
                            request.getAddress(),
                            request.getPort());

                    discoverySocket.send(response);
                    System.out.println("Found client: "
                            + request.getAddress().getHostAddress());
                }
            }
        } catch (Exception e) {
            System.err.println("144P discovery service stopped: " + e.getMessage());
        }
    }
}