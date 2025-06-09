package client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import entities.Message;
import entities.Message.MessageType;
import ocsf.client.ObservableClient;
import client.controllers.*;
import java.io.*;

public class ParkBClientApp extends Application {
    private static ParkBClient client;
    private static String serverIP = "localhost";
    private static int serverPort = 5555;
    
    // Current user info
    private static String currentUser;
    private static String userType; // "sub", "emp", "mng"
    
    // Controllers
    private static LoginController loginController;
    
    @Override
    public void start(Stage primaryStage) throws Exception {
        // Start with login screen
        showLoginScreen(primaryStage);
    }
    
    private void showLoginScreen(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/gui/Login.fxml"));
        Parent root = loader.load();
        
        // Get the controller
        loginController = loader.getController();
        
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/client/resources/css/ParkBStyle.css").toExternalForm());
        stage.setTitle("ParkB - Login");
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();
    }
    
    public static void connectToServer() {
        try {
            client = new ParkBClient(serverIP, serverPort);
            client.openConnection();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void switchToMainScreen(String userType) {
        try {
            Stage stage = new Stage();
            Parent root = null;
            
            switch (userType) {
                case "sub":
                    FXMLLoader subLoader = new FXMLLoader(ParkBClientApp.class.getResource("/client/gui/SubscriberMain.fxml"));
                    root = subLoader.load();
                    stage.setTitle("ParkB - Subscriber Portal");
                    break;
                    
                case "emp":
                    FXMLLoader empLoader = new FXMLLoader(ParkBClientApp.class.getResource("/client/gui/AttendantMain.fxml"));
                    root = empLoader.load();
                    stage.setTitle("ParkB - Attendant Portal");
                    break;
                    
                case "mng":
                    FXMLLoader mngLoader = new FXMLLoader(ParkBClientApp.class.getResource("/client/gui/ManagerMain.fxml"));
                    root = mngLoader.load();
                    stage.setTitle("ParkB - Manager Portal");
                    break;
            }
            
            if (root != null) {
                Scene scene = new Scene(root);
                stage.setScene(scene);
                stage.show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Client communication class
    static class ParkBClient extends ObservableClient {
        public ParkBClient(String host, int port) {
            super(host, port);
        }
        
        @Override
        protected void handleMessageFromServer(Object msg) {
            Platform.runLater(() -> {
                try {
                    if (msg instanceof byte[]) {
                        msg = ClientMessageHandler.deserialize(msg);
                    }
                    
                    if (msg instanceof Message) {
                        ClientMessageHandler.handleMessage((Message) msg);
                    } else if (msg instanceof String) {
                        ClientMessageHandler.handleStringMessage((String) msg);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
        
        @Override
        protected void connectionClosed() {
            Platform.runLater(() -> {
                System.out.println("Connection closed");
                // Show reconnect dialog
            });
        }
        
        @Override
        protected void connectionException(Exception exception) {
            Platform.runLater(() -> {
                System.out.println("Connection error: " + exception.getMessage());
                // Show error dialog
            });
        }
    }
    
    // Utility methods for sending messages
    public static void sendMessage(Message msg) {
        try {
            if (client != null && client.isConnected()) {
                client.sendToServer(ClientMessageHandler.serialize(msg));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void sendStringMessage(String msg) {
        try {
            if (client != null && client.isConnected()) {
                client.sendToServer(msg);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Getters and setters
    public static String getCurrentUser() {
        return currentUser;
    }
    
    public static void setCurrentUser(String user) {
        currentUser = user;
    }
    
    public static String getUserType() {
        return userType;
    }
    
    public static void setUserType(String type) {
        userType = type;
    }
    
    public static void setServerIP(String ip) {
        serverIP = ip;
    }
    
    public static void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.sendToServer("ClientDisconnect");
                client.closeConnection();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    @Override
    public void stop() throws Exception {
        // Clean up when application closes
        disconnect();
        super.stop();
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}