package server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import controllers.ParkingController;
import controllers.ReportController;
import entities.Message;
import entities.Message.MessageType;
import entities.ParkingOrder;
import entities.ParkingSubscriber;
import entities.ParkingReport;
import ocsf.server.AbstractServer;
import ocsf.server.ConnectionToClient;
import serverGUI.ServerPortFrame;

/**
 * ParkingServer - Main server for the ParkB automatic parking management system
 * Now includes auto-cancellation service shutdown
 */
public class ParkingServer extends AbstractServer {
    // Class variables *************************************************
    
    /**
     * The default port to listen on.
     */
    final public static Integer DEFAULT_PORT = 5555;
    
    // Controllers (following your pattern)
    public static ParkingController parkingController;
    public static ReportController reportController;
    public static ServerPortFrame spf;
    
    // Connection management
    public Map<ConnectionToClient, String> clientsMap = new HashMap<>();
    public static String serverIp;
    
    // Connection pool with timer for cleanup
    private ScheduledExecutorService connectionPoolTimer;
    private final int POOL_SIZE = 5;
    private final int TIMER_INTERVAL = 30; // 30 seconds
    
    // Constructors ****************************************************
    
    /**
     * Constructs an instance of the parking server.
     * @param port The port number to connect on.
     */
    public ParkingServer(int port) {
        super(port);
        try {
            serverIp = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            e.printStackTrace();
        }
        initializeConnectionPool();
    }
    
    /**
     * Initialize connection pool with timer for monitoring
     */
    private void initializeConnectionPool() {
        connectionPoolTimer = Executors.newScheduledThreadPool(POOL_SIZE);
        
        // Start connection pool monitoring timer
        connectionPoolTimer.scheduleAtFixedRate(() -> {
            synchronized (clientsMap) {
                System.out.println("Connection Pool Status - Active connections: " + clientsMap.size());
                cleanupInactiveConnections();
            }
        }, 0, TIMER_INTERVAL, TimeUnit.SECONDS);
    }
    
    /**
     * Cleanup inactive connections
     */
    private synchronized void cleanupInactiveConnections() {
        clientsMap.entrySet().removeIf(entry -> {
            ConnectionToClient client = entry.getKey();
            return !client.isAlive();
        });
    }

    // Instance methods ************************************************

    /**
     * This method handles any messages received from the client.
     * Following your exact handleMessageFromClient pattern
     */
    public synchronized void handleMessageFromClient(Object msg, ConnectionToClient client) {
        System.out.println("Message received: " + msg + " from " + client);
        
        try {
            // Check if the message is in byte array form (following your pattern)
            if (msg instanceof byte[]) {
                msg = deserialize(msg);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        
        // Handle Message objects (following your pattern)
        if (msg instanceof Message) {
            handleMessageObject((Message) msg, client);
        }
        
        // Handle String messages (following your pattern)
        if (msg instanceof String) {
            handleStringMessage((String) msg, client);
        }
    }
    
    /**
     * Handle Message objects (following your Message handling pattern)
     */
    private synchronized void handleMessageObject(Message message, ConnectionToClient client) {
        Message ret;
        
        try {
            switch (message.getType()) {
            case SUBSCRIBER_LOGIN:
                String subscriberCode = (String) message.getContent();
                ParkingSubscriber subscriber = parkingController.getUserInfo(subscriberCode);
                ret = new Message(MessageType.SUBSCRIBER_LOGIN_RESPONSE, subscriber);
                client.sendToClient(serialize(ret));
                break;
                
            case CHECK_PARKING_AVAILABILITY:
                int availableSpots = parkingController.getAvailableParkingSpots();
                ret = new Message(MessageType.PARKING_AVAILABILITY_RESPONSE, availableSpots);
                client.sendToClient(serialize(ret));
                break;
                
            case RESERVE_PARKING:
                String[] reservationData = ((String) message.getContent()).split(",");
                String userName = reservationData[0];
                String reservationDate = reservationData[1];
                String reservationResult = parkingController.makeReservation(userName, reservationDate);
                ret = new Message(MessageType.RESERVATION_RESPONSE, reservationResult);
                client.sendToClient(serialize(ret));
                break;
                
            case REGISTER_SUBSCRIBER:
                String registrationData = (String) message.getContent();
                String[] regParts = registrationData.split(",");
                
                if (regParts.length >= 5) {
                    // Format: name,phone,email,carNumber,userName
                    String registrationResult = parkingController.registerNewSubscriber(
                        regParts[0].trim(), 
                        regParts[1].trim(), 
                        regParts[2].trim(), 
                        regParts[3].trim(), 
                        regParts[4].trim()
                    );
                    ret = new Message(MessageType.REGISTRATION_RESPONSE, registrationResult);
                } else {
                    ret = new Message(MessageType.REGISTRATION_RESPONSE, "Invalid registration data format");
                }
                client.sendToClient(serialize(ret));
                break;
                
            case GENERATE_USERNAME:
                String baseName = (String) message.getContent();
                String generatedUsername = parkingController.generateUniqueUsername(baseName);
                ret = new Message(MessageType.USERNAME_RESPONSE, generatedUsername);
                client.sendToClient(serialize(ret));
                break;    
                
                
            case ENTER_PARKING:
                String enterData = (String) message.getContent();
                String enterResult = parkingController.enterParking(enterData);
                ret = new Message(MessageType.ENTER_PARKING_RESPONSE, enterResult);
                client.sendToClient(serialize(ret));
                break;
                
            case EXIT_PARKING:
                String exitData = (String) message.getContent();
                String exitResult = parkingController.exitParking(exitData);
                ret = new Message(MessageType.EXIT_PARKING_RESPONSE, exitResult);
                client.sendToClient(serialize(ret));
                break;
                
            case EXTEND_PARKING:
                String[] extendData = ((String) message.getContent()).split(",");
                String parkingCode = extendData[0];
                int additionalHours = Integer.parseInt(extendData[1]);
                String extendResult = parkingController.extendParkingTime(parkingCode, additionalHours);
                ret = new Message(MessageType.EXTEND_PARKING_RESPONSE, extendResult);
                client.sendToClient(serialize(ret));
                break;
                
            case REQUEST_LOST_CODE:
                String userNameForCode = (String) message.getContent();
                String lostCodeResult = parkingController.sendLostParkingCode(userNameForCode);
                ret = new Message(MessageType.LOST_CODE_RESPONSE, lostCodeResult);
                client.sendToClient(serialize(ret));
                break;
                
            case GET_PARKING_HISTORY:
                String userNameForHistory = (String) message.getContent();
                ArrayList<ParkingOrder> history = parkingController.getParkingHistory(userNameForHistory);
                ret = new Message(MessageType.PARKING_HISTORY_RESPONSE, history);
                client.sendToClient(serialize(ret));
                break;
                
            case MANAGER_GET_REPORTS:
                String reportType = (String) message.getContent();
                ArrayList<ParkingReport> reports = reportController.getParkingReports(reportType);
                ret = new Message(MessageType.MANAGER_SEND_REPORTS, reports);
                client.sendToClient(serialize(ret));
                break;
                
            case GET_ACTIVE_PARKINGS:
                ArrayList<ParkingOrder> activeParkings = parkingController.getActiveParkings();
                ret = new Message(MessageType.ACTIVE_PARKINGS_RESPONSE, activeParkings);
                client.sendToClient(serialize(ret));
                break;
                
            case UPDATE_SUBSCRIBER_INFO:
                String updateResult = parkingController.updateSubscriberInfo((String) message.getContent());
                ret = new Message(MessageType.UPDATE_SUBSCRIBER_RESPONSE, updateResult);
                client.sendToClient(serialize(ret));
                break;
                
            case GENERATE_MONTHLY_REPORTS:
                String monthYear = (String) message.getContent();
                ArrayList<ParkingReport> monthlyReports = reportController.generateMonthlyReports(monthYear);
                ret = new Message(MessageType.MONTHLY_REPORTS_RESPONSE, monthlyReports);
                client.sendToClient(serialize(ret));
                break;
                
            default:
                System.out.println("Unknown message type: " + message.getType());
                break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Handle String messages (following your string handling pattern)
     */
    private synchronized void handleStringMessage(String message, ConnectionToClient client) {
        String[] arr = message.split("\\s");
        
        try {
            switch (arr[0]) {
            case "ClientDisconnect":
                disconnect(client);
                break;
                
            case "login:":
                String loginResult = parkingController.checkLogin(arr[1], arr.length > 2 ? arr[2] : "");
                client.sendToClient("login: " + loginResult);
                break;
                
            case "LoggedOut":
                parkingController.logoutUser(arr[1]);
                break;
                
            case "getParkingSpots":
                int availableSpots = parkingController.getAvailableParkingSpots();
                client.sendToClient("availableSpots " + availableSpots);
                break;
                
            case "enterParking":
                String enterResult = parkingController.enterParking(arr[1]);
                client.sendToClient("enterResult " + enterResult);
                break;
                
            case "enterWithReservation":
                String reservationResult = parkingController.enterParkingWithReservation(Integer.parseInt(arr[1]));
                client.sendToClient("reservationResult " + reservationResult);
                break;
                
            case "exitParking":
                String exitResult = parkingController.exitParking(arr[1]);
                client.sendToClient("exitResult " + exitResult);
                break;
                
            case "extendParking":
                String extendResult = parkingController.extendParkingTime(arr[1], Integer.parseInt(arr[2]));
                client.sendToClient("extendResult " + extendResult);
                break;
                
            case "getLostCode":
                String lostCode = parkingController.sendLostParkingCode(arr[1]);
                client.sendToClient("parkingCode " + lostCode);
                break;
                
            case "makeReservation":
                // Format: makeReservation userName reservationDate
                String makeReservationResult = parkingController.makeReservation(arr[1], arr[2]);
                client.sendToClient("reservationResult " + makeReservationResult);
                break;
                
            case "cancelReservation":
                String cancelResult = parkingController.cancelReservation(Integer.parseInt(arr[1]));
                client.sendToClient("cancelResult " + cancelResult);
                break;
                
            case "getReports":
                // This could be enhanced to return actual report data
                client.sendToClient("reports " + "Available reports: parking_time, subscriber_status");
                break;
                
            default:
                System.out.println("Unknown string command: " + arr[0]);
                break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                client.sendToClient("error " + e.getMessage());
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        }
    }

    /**
     * Serializes a Message object to byte array (following your pattern)
     */
    private byte[] serialize(Message msg) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteStream);
            out.writeObject(msg);
            out.flush();
            return byteStream.toByteArray();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }
    
    /**
     * Deserializes byte array to Message object (following your pattern)
     */
    private Object deserialize(Object msg) {
        try {
            byte[] messageBytes = (byte[]) msg;
            ByteArrayInputStream byteStream = new ByteArrayInputStream(messageBytes);
            ObjectInputStream objectStream = new ObjectInputStream(byteStream);
            return objectStream.readObject();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    /**
     * This method overrides the one in the superclass. Called when the server
     * starts listening for connections.
     */
    protected void serverStarted() {
        System.out.println("ParkB Server listening for connections on port " + getPort());
        // Initialize parking spots if needed
        parkingController.initializeParkingSpots();
    }

    /**
     * This method overrides the one in the superclass. Called when the server stops
     * listening for connections.
     * MODIFIED: Now includes auto-cancellation service shutdown
     */
    protected void serverStopped() {
        System.out.println("ParkB Server has stopped listening for connections.");
        
        // Stop auto-cancellation service cleanly
        if (parkingController != null) {
            parkingController.shutdown();
            System.out.println("Auto-cancellation service shut down successfully");
        }
        
        if (connectionPoolTimer != null) {
            connectionPoolTimer.shutdown();
        }
    }

    /**
     * Client connected handler (following your pattern)
     */
    @Override
    protected synchronized void clientConnected(ConnectionToClient client) {
        String clientIP = client.getInetAddress().getHostAddress();
        String clientHostName = client.getInetAddress().getHostName();
        String connectionStatus = "ClientIP: " + clientIP + " Client Host Name: " + clientHostName
                + " status: connected";

        // Check if IP already exists
        synchronized (clientsMap) {
            boolean ipExists = false;
            ConnectionToClient existingClient = null;

            for (Map.Entry<ConnectionToClient, String> entry : clientsMap.entrySet()) {
                if (entry.getValue().contains(clientIP)) {
                    ipExists = true;
                    existingClient = entry.getKey();
                    break;
                }
            }

            if (!ipExists) {
                clientsMap.put(client, connectionStatus);
            } else {
                clientsMap.put(existingClient, connectionStatus);
            }
        }
        
        if (spf != null) {
            spf.printConnection(clientsMap);
        }
    }

    /**
     * Client disconnect handler (following your pattern)
     */
    protected synchronized void disconnect(ConnectionToClient client) {
        String clientIP = client.getInetAddress().getHostAddress();
        String clientHostName = client.getInetAddress().getHostName();
        String disconnectionStatus = "ClientIP: " + clientIP + " Client Host Name: " + clientHostName
                + " status: disconnected";

        synchronized (clientsMap) {
            boolean ipExists = false;
            ConnectionToClient existingClient = null;

            for (Map.Entry<ConnectionToClient, String> entry : clientsMap.entrySet()) {
                if (entry.getValue().contains(clientIP)) {
                    ipExists = true;
                    existingClient = entry.getKey();
                    break;
                }
            }

            if (ipExists) {
                clientsMap.put(existingClient, disconnectionStatus);
            }
        }

        if (spf != null) {
            spf.printConnection(clientsMap);
        }
    }

    // Class methods ***************************************************

    /**
     * This method is responsible for the creation of the server instance.
     * Following your main method pattern
     */
    public static void main(String[] args) {
        int port = 0;

        try {
            port = Integer.parseInt(args[0]);
        } catch (Throwable t) {
            port = DEFAULT_PORT;
        }

        ParkingServer sv = new ParkingServer(port);

        try {
            sv.listen();
        } catch (Exception ex) {
            System.out.println("ERROR - Could not listen for clients!");
        }
    }
    
    /**
     * Shutdown the server properly
     * MODIFIED: Now includes auto-cancellation service shutdown
     */
    public synchronized void shutdown() {
        // Stop auto-cancellation service first
        if (parkingController != null) {
            parkingController.shutdown();
        }
        
        if (connectionPoolTimer != null) {
            connectionPoolTimer.shutdown();
        }
        try {
            close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}