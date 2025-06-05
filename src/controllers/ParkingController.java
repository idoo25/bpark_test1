package controllers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Date;
import java.sql.Time;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Random;

import entities.ParkingOrder;
import entities.ParkingSubscriber;

/**
 * Enhanced ParkingController with advanced availability checking
 * Handles all database operations for the ParkB parking management system.
 */
public class ParkingController {
    protected Connection conn;
    public int successFlag;
    private static final int TOTAL_PARKING_SPOTS = 100;
    private static final double RESERVATION_THRESHOLD = 0.4;

    public ParkingController(String dbname, String pass) {
        String connectPath = "jdbc:mysql://localhost/" + dbname + "?serverTimezone=IST";
        connectToDB(connectPath, pass);
    }

    public Connection getConnection() {
        return conn;
    }

    public void connectToDB(String path, String pass) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            System.out.println("Driver definition succeed");
        } catch (Exception ex) {
            System.out.println("Driver definition failed");
        }

        try {
            conn = DriverManager.getConnection(path, "root", pass);
            System.out.println("SQL connection succeed");
            successFlag = 1;
        } catch (SQLException ex) {
            System.out.println("SQLException: " + ex.getMessage());
            System.out.println("SQLState: " + ex.getSQLState());
            System.out.println("VendorError: " + ex.getErrorCode());
            successFlag = 2;
        }
    }

    // ========== EXISTING METHODS (keep your current implementations) ==========
    
    public String checkLogin(String userName, String password) {
        // Your existing login method
        String qry = "SELECT UserTypeEnum FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("UserTypeEnum");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking login: " + e.getMessage());
        }
        return "None";
    }

    // ========== ENHANCED AVAILABILITY METHODS (ADD THESE) ==========

    /**
     * Enhanced parking availability checking that considers:
     * - Current occupancy
     * - Future reservations within specified time
     * - Reservations starting within 15 minutes
     * - Overnight parking sessions
     */
    public int getAvailableParkingSpotsAdvanced(LocalDateTime requestedStartTime, int estimatedDuration) {
        LocalDateTime requestedEndTime = requestedStartTime.plusHours(estimatedDuration);
        
        // Get all parking spots
        int totalSpots = getTotalParkingSpots();
        
        // Get occupied spots (currently in use)
        int currentlyOccupied = getCurrentlyOccupiedSpots();
        
        // Get spots that will be occupied by reservations during the requested time
        int reservedSpots = getSpotsReservedDuringTime(requestedStartTime, requestedEndTime);
        
        // Get spots with reservations starting within 15 minutes (considered occupied)
        int soonToBeOccupied = getSpotsWithReservationsStartingSoon(requestedStartTime);
        
        int unavailableSpots = currentlyOccupied + reservedSpots + soonToBeOccupied;
        
        return Math.max(0, totalSpots - unavailableSpots);
    }

    /**
     * Gets total number of parking spots in the system
     */
    private int getTotalParkingSpots() {
        String qry = "SELECT COUNT(*) FROM ParkingSpot";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting total parking spots: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Gets spots currently occupied (someone is physically parked)
     */
    private int getCurrentlyOccupiedSpots() {
        String qry = "SELECT COUNT(*) FROM ParkingSpot WHERE isOccupied = true";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting currently occupied spots: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Gets spots that have reservations overlapping with the requested time period
     * Supports overnight parking (spans multiple dates)
     */
    private int getSpotsReservedDuringTime(LocalDateTime requestedStart, LocalDateTime requestedEnd) {
        String qry = """
            SELECT COUNT(DISTINCT r.assigned_parking_spot_id) 
            FROM Reservations r 
            WHERE r.assigned_parking_spot_id IS NOT NULL 
            AND r.statusEnum = 'active'
            AND (
                -- Case 1: Same day reservation
                (DATE(r.reservation_Date) = DATE(?) AND DATE(r.reservation_Date) = DATE(?)
                 AND NOT (TIME(?) >= r.reservation_end_time OR TIME(?) <= r.reservation_start_time))
                OR
                -- Case 2: Overnight reservation starting today
                (DATE(r.reservation_Date) = DATE(?) AND TIME(r.reservation_end_time) < TIME(r.reservation_start_time)
                 AND NOT (TIME(?) >= r.reservation_end_time AND TIME(?) < r.reservation_start_time))
                OR  
                -- Case 3: Overnight reservation from previous day
                (DATE(r.reservation_Date) = DATE(? - INTERVAL 1 DAY) AND TIME(r.reservation_end_time) < TIME(r.reservation_start_time)
                 AND NOT (TIME(?) >= r.reservation_end_time AND TIME(?) < r.reservation_start_time))
            )
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            // Set all parameters for the complex query
            stmt.setTimestamp(1, Timestamp.valueOf(requestedStart));
            stmt.setTimestamp(2, Timestamp.valueOf(requestedEnd));
            stmt.setTime(3, Time.valueOf(requestedEnd.toLocalTime()));
            stmt.setTime(4, Time.valueOf(requestedStart.toLocalTime()));
            stmt.setTimestamp(5, Timestamp.valueOf(requestedStart));
            stmt.setTime(6, Time.valueOf(requestedEnd.toLocalTime()));
            stmt.setTime(7, Time.valueOf(requestedStart.toLocalTime()));
            stmt.setTimestamp(8, Timestamp.valueOf(requestedStart));
            stmt.setTime(9, Time.valueOf(requestedEnd.toLocalTime()));
            stmt.setTime(10, Time.valueOf(requestedStart.toLocalTime()));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting reserved spots during time: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Gets spots with reservations starting within 15 minutes (considered occupied)
     */
    private int getSpotsWithReservationsStartingSoon(LocalDateTime currentTime) {
        LocalDateTime fifteenMinutesFromNow = currentTime.plusMinutes(15);
        
        String qry = """
            SELECT COUNT(DISTINCT r.assigned_parking_spot_id) 
            FROM Reservations r 
            WHERE r.assigned_parking_spot_id IS NOT NULL 
            AND r.statusEnum = 'active'
            AND DATE(r.reservation_Date) = DATE(?)
            AND TIME(r.reservation_start_time) BETWEEN TIME(?) AND TIME(?)
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setTimestamp(1, Timestamp.valueOf(currentTime));
            stmt.setTime(2, Time.valueOf(currentTime.toLocalTime()));
            stmt.setTime(3, Time.valueOf(fifteenMinutesFromNow.toLocalTime()));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting spots with reservations starting soon: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Finds an available parking spot for a specific time period
     */
    public int findAvailableParkingSpot(LocalDateTime requestedStartTime, int estimatedDuration) {
        LocalDateTime requestedEndTime = requestedStartTime.plusHours(estimatedDuration);
        
        String qry = """
            SELECT ps.ParkingSpot_ID 
            FROM ParkingSpot ps
            WHERE ps.ParkingSpot_ID NOT IN (
                -- Exclude currently occupied spots
                SELECT ParkingSpot_ID FROM ParkingSpot WHERE isOccupied = true
                UNION
                -- Exclude spots with conflicting reservations
                SELECT DISTINCT r.assigned_parking_spot_id 
                FROM Reservations r 
                WHERE r.assigned_parking_spot_id IS NOT NULL 
                AND r.statusEnum = 'active'
                AND (
                    -- Same day conflicts
                    (DATE(r.reservation_Date) = DATE(?) AND DATE(r.reservation_Date) = DATE(?)
                     AND NOT (TIME(?) >= r.reservation_end_time OR TIME(?) <= r.reservation_start_time))
                    OR
                    -- Overnight conflicts
                    (DATE(r.reservation_Date) = DATE(?) AND TIME(r.reservation_end_time) < TIME(r.reservation_start_time)
                     AND NOT (TIME(?) >= r.reservation_end_time AND TIME(?) < r.reservation_start_time))
                    OR  
                    (DATE(r.reservation_Date) = DATE(? - INTERVAL 1 DAY) AND TIME(r.reservation_end_time) < TIME(r.reservation_start_time)
                     AND NOT (TIME(?) >= r.reservation_end_time AND TIME(?) < r.reservation_start_time))
                )
                UNION
                -- Exclude spots with reservations starting within 15 minutes
                SELECT DISTINCT r2.assigned_parking_spot_id 
                FROM Reservations r2 
                WHERE r2.assigned_parking_spot_id IS NOT NULL 
                AND r2.statusEnum = 'active'
                AND DATE(r2.reservation_Date) = DATE(?)
                AND TIME(r2.reservation_start_time) BETWEEN TIME(?) AND TIME(? + INTERVAL 15 MINUTE)
            )
            LIMIT 1
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            // Set parameters for the complex query
            stmt.setTimestamp(1, Timestamp.valueOf(requestedStartTime));
            stmt.setTimestamp(2, Timestamp.valueOf(requestedEndTime));
            stmt.setTime(3, Time.valueOf(requestedEndTime.toLocalTime()));
            stmt.setTime(4, Time.valueOf(requestedStartTime.toLocalTime()));
            stmt.setTimestamp(5, Timestamp.valueOf(requestedStartTime));
            stmt.setTime(6, Time.valueOf(requestedEndTime.toLocalTime()));
            stmt.setTime(7, Time.valueOf(requestedStartTime.toLocalTime()));
            stmt.setTimestamp(8, Timestamp.valueOf(requestedStartTime));
            stmt.setTime(9, Time.valueOf(requestedEndTime.toLocalTime()));
            stmt.setTime(10, Time.valueOf(requestedStartTime.toLocalTime()));
            stmt.setTimestamp(11, Timestamp.valueOf(requestedStartTime));
            stmt.setTime(12, Time.valueOf(requestedStartTime.toLocalTime()));
            stmt.setTimestamp(13, Timestamp.valueOf(requestedStartTime));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("ParkingSpot_ID");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error finding available parking spot: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Enhanced reservation method that assigns specific parking spot
     */
    public String makeAdvancedReservation(String userName, String reservationDateStr, String startTimeStr, String endTimeStr) {
        try {
            LocalDate reservationDate = LocalDate.parse(reservationDateStr);
            LocalTime startTime = LocalTime.parse(startTimeStr);
            LocalTime endTime = LocalTime.parse(endTimeStr);
            
            // Create full datetime for the start
            LocalDateTime reservationStart = LocalDateTime.of(reservationDate, startTime);
            
            // Calculate duration (handle overnight parking)
            int durationHours;
            if (endTime.isBefore(startTime)) {
                // Overnight parking
                durationHours = (int) java.time.Duration.between(startTime, endTime.plusHours(24)).toHours();
            } else {
                // Same day parking
                durationHours = (int) java.time.Duration.between(startTime, endTime).toHours();
            }
            
            // Check if reservation is possible (40% rule)
            if (!canMakeReservation()) {
                return "Not enough available spots for reservation (need 40% available)";
            }
            
            // Check time restrictions (24 hours to 7 days)
            LocalDate today = LocalDate.now();
            if (reservationDate.isBefore(today.plusDays(1)) || reservationDate.isAfter(today.plusDays(7))) {
                return "Reservation must be between 24 hours and 7 days in advance";
            }
            
            // Find available spot for this time period
            int assignedSpotID = findAvailableParkingSpot(reservationStart, durationHours);
            if (assignedSpotID == -1) {
                return "No parking spots available for the requested time period";
            }
            
            // Get user ID
            int userID = getUserID(userName);
            if (userID == -1) {
                return "User not found";
            }
            
            // Create reservation with assigned spot
            String insertQry = """
                INSERT INTO Reservations 
                (User_ID, parking_ID, reservation_Date, reservation_start_time, reservation_end_time, 
                 Date_Of_Placing_Order, statusEnum, assigned_parking_spot_id) 
                VALUES (?, ?, ?, ?, ?, ?, 'active', ?)
                """;
            
            try (PreparedStatement stmt = conn.prepareStatement(insertQry, PreparedStatement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, userID);
                stmt.setInt(2, assignedSpotID);
                stmt.setDate(3, Date.valueOf(reservationDate));
                stmt.setTime(4, Time.valueOf(startTime));
                stmt.setTime(5, Time.valueOf(endTime));
                stmt.setDate(6, Date.valueOf(LocalDate.now()));
                stmt.setInt(7, assignedSpotID);
                stmt.executeUpdate();
                
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int reservationCode = generatedKeys.getInt(1);
                        return String.format("Reservation confirmed! Code: %d, Assigned Spot: %d, Time: %s-%s", 
                                           reservationCode, assignedSpotID, startTimeStr, endTimeStr);
                    }
                }
            }
            
        } catch (Exception e) {
            System.out.println("Error making advanced reservation: " + e.getMessage());
            return "Reservation failed: " + e.getMessage();
        }
        
        return "Reservation failed";
    }

    /**
     * Updated immediate parking entry that considers reservations
     */
    public String enterParkingAdvanced(String userName) {
        LocalDateTime now = LocalDateTime.now();
        
        // Check immediate availability (next 4 hours)
        int availableSpots = getAvailableParkingSpotsAdvanced(now, 4);
        if (availableSpots <= 0) {
            return "No parking spots available for immediate parking";
        }
        
        // Find available spot for immediate parking
        int spotID = findAvailableParkingSpot(now, 4);
        if (spotID == -1) {
            return "No available parking spot found for immediate use";
        }
        
        // Get user ID
        int userID = getUserID(userName);
        if (userID == -1) {
            return "Invalid user code";
        }
        
        // Create parking session
        int parkingCode = generateParkingCode();
        LocalDateTime estimatedEnd = now.plusHours(4);
        
        String qry = """
            INSERT INTO ParkingInfo 
            (ParkingSpot_ID, User_ID, Date, Code, Actual_start_time, Estimated_start_time, 
             Estimated_end_time, IsOrderedEnum, IsLate, IsExtended) 
            VALUES (?, ?, ?, ?, ?, ?, ?, 'not ordered', false, false)
            """;
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, spotID);
            stmt.setInt(2, userID);
            stmt.setDate(3, Date.valueOf(now.toLocalDate()));
            stmt.setInt(4, parkingCode);
            stmt.setTime(5, Time.valueOf(now.toLocalTime()));
            stmt.setTime(6, Time.valueOf(now.toLocalTime()));
            stmt.setTime(7, Time.valueOf(estimatedEnd.toLocalTime()));
            stmt.executeUpdate();
            
            // Mark spot as physically occupied
            updateParkingSpotStatus(spotID, true);
            
            return "Entry successful. Parking code: " + parkingCode + ". Assigned spot: " + spotID;
            
        } catch (SQLException e) {
            System.out.println("Error in advanced parking entry: " + e.getMessage());
            return "Entry failed";
        }
    }

    // ========== KEEP ALL YOUR EXISTING HELPER METHODS ==========
    
    private int generateParkingCode() {
        Random random = new Random();
        return 100000 + random.nextInt(900000);
    }

    private int getUserID(String userName) {
        String qry = "SELECT User_ID FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("User_ID");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting user ID: " + e.getMessage());
        }
        return -1;
    }

    private boolean canMakeReservation() {
        int availableSpots = getAvailableParkingSpots();
        return availableSpots >= (TOTAL_PARKING_SPOTS * RESERVATION_THRESHOLD);
    }

    private void updateParkingSpotStatus(int spotID, boolean isOccupied) {
        String qry = "UPDATE ParkingSpot SET isOccupied = ? WHERE ParkingSpot_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setBoolean(1, isOccupied);
            stmt.setInt(2, spotID);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Error updating parking spot status: " + e.getMessage());
        }
    }

    // Keep all your other existing methods...
    public int getAvailableParkingSpots() {
        String qry = "SELECT COUNT(*) as available FROM ParkingSpot WHERE isOccupied = false";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("available");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting available spots: " + e.getMessage());
        }
        return 0;
    }
    

    /**
     * Gets user information by userName
     */
    public ParkingSubscriber getUserInfo(String userName) {
        String qry = "SELECT * FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ParkingSubscriber user = new ParkingSubscriber();
                    user.setSubscriberID(rs.getInt("User_ID"));
                    user.setFirstName(rs.getString("Name"));
                    user.setPhoneNumber(rs.getString("Phone"));
                    user.setEmail(rs.getString("Email"));
                    user.setCarNumber(rs.getString("CarNum"));
                    user.setSubscriberCode(userName);
                    user.setUserType(rs.getString("UserTypeEnum"));
                    return user;
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting user info: " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets the number of available parking spots
     */
//    public int getAvailableParkingSpots() {
//        String qry = "SELECT COUNT(*) as available FROM ParkingSpot WHERE isOccupied = false";
//        
//        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
//            try (ResultSet rs = stmt.executeQuery()) {
//                if (rs.next()) {
//                    return rs.getInt("available");
//                }
//            }
//        } catch (SQLException e) {
//            System.out.println("Error getting available spots: " + e.getMessage());
//        }
//        return 0;
//    }

    /**
     * Checks if reservation is possible (40% of spots must be available)
     */
//    public boolean canMakeReservation() {
//        int availableSpots = getAvailableParkingSpots();
//        return availableSpots >= (TOTAL_PARKING_SPOTS * RESERVATION_THRESHOLD);
//    }

    /**
     * Makes a parking reservation
     */
    public String makeReservation(String userName, String reservationDateStr) {
        // Check if reservation is possible
        if (!canMakeReservation()) {
            return "Not enough available spots for reservation (need 40% available)";
        }

        try {
            Date reservationDate = Date.valueOf(reservationDateStr);
            
            // Check if reservation is within valid time range (24 hours to 7 days)
            LocalDate today = LocalDate.now();
            LocalDate resDate = reservationDate.toLocalDate();
            
            if (resDate.isBefore(today.plusDays(1)) || resDate.isAfter(today.plusDays(7))) {
                return "Reservation must be between 24 hours and 7 days in advance";
            }

            // Get user ID
            int userID = getUserID(userName);
            if (userID == -1) {
                return "User not found";
            }

            // Find available parking spot
            int parkingSpotID = getAvailableParkingSpotID();
            if (parkingSpotID == -1) {
                return "No available parking spots";
            }

            String qry = "INSERT INTO Reservations (User_ID, parking_ID, reservation_Date, Date_Of_Placing_Order, statusEnum) VALUES (?, ?, ?, ?, 'active')";
            
            try (PreparedStatement stmt = conn.prepareStatement(qry, PreparedStatement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, userID);
                stmt.setInt(2, parkingSpotID);
                stmt.setDate(3, reservationDate);
                stmt.setDate(4, Date.valueOf(LocalDate.now()));
                stmt.executeUpdate();
                
                // Get the generated reservation code
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int reservationCode = generatedKeys.getInt(1);
                        return "Reservation confirmed. Confirmation code: " + reservationCode;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return "Invalid date format. Use YYYY-MM-DD";
        } catch (SQLException e) {
            System.out.println("Error making reservation: " + e.getMessage());
            return "Reservation failed";
        }
        return "Reservation failed";
    }

    /**
     * Handles parking entry with subscriber code (immediate parking)
     */
    public String enterParking(String userName) {
        // Get user ID
        int userID = getUserID(userName);
        if (userID == -1) {
            return "Invalid user code";
        }

        // Check if spots are available
        if (getAvailableParkingSpots() <= 0) {
            return "No parking spots available";
        }

        // Find available parking spot
        int spotID = getAvailableParkingSpotID();
        if (spotID == -1) {
            return "No available parking spot found";
        }

        // Generate unique parking code
        int parkingCode = generateParkingCode();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime estimatedEnd = now.plusHours(4); // Default 4 hours

        // Create parking info record
        String qry = "INSERT INTO ParkingInfo (ParkingSpot_ID, User_ID, Date, Code, Actual_start_time, Estimated_start_time, Estimated_end_time, IsOrderedEnum, IsLate, IsExtended) VALUES (?, ?, ?, ?, ?, ?, ?, 'not ordered', false, false)";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, spotID);
            stmt.setInt(2, userID);
            stmt.setDate(3, Date.valueOf(now.toLocalDate()));
            stmt.setInt(4, parkingCode);
            stmt.setTime(5, Time.valueOf(now.toLocalTime()));
            stmt.setTime(6, Time.valueOf(now.toLocalTime()));
            stmt.setTime(7, Time.valueOf(estimatedEnd.toLocalTime()));
            stmt.executeUpdate();

            // Mark parking spot as occupied
            updateParkingSpotStatus(spotID, true);
            
            return "Entry successful. Parking code: " + parkingCode + ". Spot: " + spotID;
        } catch (SQLException e) {
            System.out.println("Error handling entry: " + e.getMessage());
            return "Entry failed";
        }
    }

    /**
     * Handles parking entry with reservation code
     */
    public String enterParkingWithReservation(int reservationCode) {
        // Check if reservation exists and is active
        String checkQry = "SELECT r.*, u.User_ID FROM Reservations r JOIN users u ON r.User_ID = u.User_ID WHERE r.Reservation_code = ? AND r.statusEnum = 'active'";
        
        try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
            stmt.setInt(1, reservationCode);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Date reservationDate = rs.getDate("reservation_Date");
                    int userID = rs.getInt("User_ID");
                    int parkingSpotID = rs.getInt("parking_ID");
                    
                    // Check if reservation is for today
                    LocalDate today = LocalDate.now();
                    if (!reservationDate.toLocalDate().equals(today)) {
                        if (reservationDate.toLocalDate().isBefore(today)) {
                            // Cancel expired reservation
                            cancelReservation(reservationCode);
                            return "Reservation expired";
                        } else {
                            return "Reservation is for future date";
                        }
                    }

                    // Check if spot is still available
                    if (!isParkingSpotAvailable(parkingSpotID)) {
                        // Find another available spot
                        parkingSpotID = getAvailableParkingSpotID();
                        if (parkingSpotID == -1) {
                            return "No available parking spots found";
                        }
                    }

                    // Generate parking code
                    int parkingCode = generateParkingCode();
                    LocalDateTime now = LocalDateTime.now();
                    LocalDateTime estimatedEnd = now.plusHours(4);

                    // Create parking info record
                    String insertQry = "INSERT INTO ParkingInfo (ParkingSpot_ID, User_ID, Date, Code, Actual_start_time, Estimated_start_time, Estimated_end_time, IsOrderedEnum, IsLate, IsExtended) VALUES (?, ?, ?, ?, ?, ?, ?, 'ordered', false, false)";
                    
                    try (PreparedStatement insertStmt = conn.prepareStatement(insertQry)) {
                        insertStmt.setInt(1, parkingSpotID);
                        insertStmt.setInt(2, userID);
                        insertStmt.setDate(3, Date.valueOf(now.toLocalDate()));
                        insertStmt.setInt(4, parkingCode);
                        insertStmt.setTime(5, Time.valueOf(now.toLocalTime()));
                        insertStmt.setTime(6, Time.valueOf(now.toLocalTime()));
                        insertStmt.setTime(7, Time.valueOf(estimatedEnd.toLocalTime()));
                        insertStmt.executeUpdate();

                        // Mark parking spot as occupied and reservation as used
                        updateParkingSpotStatus(parkingSpotID, true);
                        updateReservationStatus(reservationCode, "expire");
                        
                        return "Entry successful with reservation. Parking code: " + parkingCode + ". Spot: " + parkingSpotID;
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error handling reservation entry: " + e.getMessage());
        }
        return "Invalid or expired reservation code";
    }
    
    /**
     * Registers a new subscriber in the system
     * @param name Subscriber's full name
     * @param phone Subscriber's phone number
     * @param email Subscriber's email address
     * @param carNumber Subscriber's car number
     * @param userName Unique username for the subscriber
     * @return Registration result message
     */
    public String registerNewSubscriber(String name, String phone, String email, String carNumber, String userName) {
        // Validate input
        if (name == null || name.trim().isEmpty()) {
            return "Name is required";
        }
        if (phone == null || phone.trim().isEmpty()) {
            return "Phone number is required";
        }
        if (email == null || email.trim().isEmpty()) {
            return "Email is required";
        }
        if (userName == null || userName.trim().isEmpty()) {
            return "Username is required";
        }
        
        // Check if username already exists
        String checkQry = "SELECT COUNT(*) FROM users WHERE UserName = ?";
        
        try (PreparedStatement checkStmt = conn.prepareStatement(checkQry)) {
            checkStmt.setString(1, userName);
            try (ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return "Username already exists. Please choose a different username.";
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking username: " + e.getMessage());
            return "Error checking username availability";
        }
        
        // Insert new subscriber
        String insertQry = "INSERT INTO users (UserName, Name, Phone, Email, CarNum, UserTypeEnum) VALUES (?, ?, ?, ?, ?, 'sub')";
        
        try (PreparedStatement stmt = conn.prepareStatement(insertQry)) {
            stmt.setString(1, userName);
            stmt.setString(2, name);
            stmt.setString(3, phone);
            stmt.setString(4, email);
            stmt.setString(5, carNumber);
            
            int rowsInserted = stmt.executeUpdate();
            if (rowsInserted > 0) {
                System.out.println("New subscriber registered: " + userName);
                return "SUCCESS:Subscriber registered successfully. Username: " + userName;
            }
        } catch (SQLException e) {
            System.out.println("Registration failed: " + e.getMessage());
            return "Registration failed: " + e.getMessage();
        }
        
        return "Registration failed: Unknown error";
    }

    /**
     * Generates a unique subscriber code/username
     * @param baseName Base name to generate username from
     * @return Unique username
     */
    public String generateUniqueUsername(String baseName) {
        // Remove spaces and special characters
        String cleanName = baseName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        
        // Try the clean name first
        if (isUsernameAvailable(cleanName)) {
            return cleanName;
        }
        
        // If taken, try with numbers
        for (int i = 1; i <= 999; i++) {
            String candidate = cleanName + i;
            if (isUsernameAvailable(candidate)) {
                return candidate;
            }
        }
        
        // Fallback to random number
        return cleanName + System.currentTimeMillis() % 10000;
    }
    
    /**
     * Checks if a username is available
     * @param userName Username to check
     * @return true if available, false if taken
     */
    private boolean isUsernameAvailable(String userName) {
        String checkQry = "SELECT COUNT(*) FROM users WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) == 0;
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking username availability: " + e.getMessage());
        }
        
        return false;
    }

    /**
     * Handles parking exit
     */
    public String exitParking(String parkingCodeStr) {
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            String qry = "SELECT pi.*, ps.ParkingSpot_ID FROM ParkingInfo pi JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID WHERE pi.Code = ? AND pi.Actual_end_time IS NULL";
            
            try (PreparedStatement stmt = conn.prepareStatement(qry)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int parkingInfoID = rs.getInt("ParkingInfo_ID");
                        int spotID = rs.getInt("ParkingSpot_ID");
                        Time estimatedEndTime = rs.getTime("Estimated_end_time");
                        int userID = rs.getInt("User_ID");
                        
                        LocalTime now = LocalTime.now();
                        LocalTime estimatedEnd = estimatedEndTime.toLocalTime();
                        
                        // Check if parking exceeded estimated time
                        boolean isLate = now.isAfter(estimatedEnd);
                        
                        // Update parking info with exit time
                        String updateQry = "UPDATE ParkingInfo SET Actual_end_time = ?, IsLate = ? WHERE ParkingInfo_ID = ?";
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                            updateStmt.setTime(1, Time.valueOf(now));
                            updateStmt.setBoolean(2, isLate);
                            updateStmt.setInt(3, parkingInfoID);
                            updateStmt.executeUpdate();
                            
                            // Free the parking spot
                            updateParkingSpotStatus(spotID, false);
                            
                            if (isLate) {
                                // Send notification about late exit
                                sendLateExitNotification(userID);
                                return "Exit successful. You were late - notification sent to your email/SMS";
                            }
                            
                            return "Exit successful. Thank you for using ParkB!";
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            return "Invalid parking code format";
        } catch (SQLException e) {
            System.out.println("Error handling exit: " + e.getMessage());
        }
        return "Invalid parking code or already exited";
    }

    /**
     * Extends parking time
     */
    public String extendParkingTime(String parkingCodeStr, int additionalHours) {
        if (additionalHours < 1 || additionalHours > 4) {
            return "Can only extend parking by 1-4 hours";
        }
        
        try {
            int parkingCode = Integer.parseInt(parkingCodeStr);
            String qry = "SELECT pi.* FROM ParkingInfo pi WHERE pi.Code = ? AND pi.Actual_end_time IS NULL";
            
            try (PreparedStatement stmt = conn.prepareStatement(qry)) {
                stmt.setInt(1, parkingCode);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Time currentEstimatedEnd = rs.getTime("Estimated_end_time");
                        LocalTime newEstimatedEnd = currentEstimatedEnd.toLocalTime().plusHours(additionalHours);
                        
                        String updateQry = "UPDATE ParkingInfo SET Estimated_end_time = ?, IsExtended = true WHERE Code = ?";
                        
                        try (PreparedStatement updateStmt = conn.prepareStatement(updateQry)) {
                            updateStmt.setTime(1, Time.valueOf(newEstimatedEnd));
                            updateStmt.setInt(2, parkingCode);
                            updateStmt.executeUpdate();
                            
                            return "Parking time extended by " + additionalHours + " hours until " + newEstimatedEnd;
                        }
                    }
                }
            }
        } catch (NumberFormatException e) {
            return "Invalid parking code format";
        } catch (SQLException e) {
            System.out.println("Error extending parking time: " + e.getMessage());
        }
        return "Invalid parking code or parking session not active";
    }

    /**
     * Sends lost parking code to user
     */
    public String sendLostParkingCode(String userName) {
        String qry = "SELECT pi.Code, u.Email, u.Phone FROM ParkingInfo pi JOIN users u ON pi.User_ID = u.User_ID WHERE u.UserName = ? AND pi.Actual_end_time IS NULL";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int parkingCode = rs.getInt("Code");
                    String email = rs.getString("Email");
                    String phone = rs.getString("Phone");
                    
                    // Simulate sending email/SMS
                    System.out.println("Sending parking code " + parkingCode + " to email: " + email + " and phone: " + phone);
                    
                    return String.valueOf(parkingCode);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error sending lost code: " + e.getMessage());
        }
        return "No active parking session found";
    }

    /**
     * Gets parking history for a user
     */
    public ArrayList<ParkingOrder> getParkingHistory(String userName) {
        ArrayList<ParkingOrder> history = new ArrayList<>();
        String qry = "SELECT pi.*, ps.ParkingSpot_ID FROM ParkingInfo pi JOIN users u ON pi.User_ID = u.User_ID JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID WHERE u.UserName = ? ORDER BY pi.Date DESC, pi.Actual_start_time DESC";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, userName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ParkingOrder order = new ParkingOrder();
                    order.setOrderID(rs.getInt("ParkingInfo_ID"));
                    order.setParkingCode(String.valueOf(rs.getInt("Code")));
                    order.setOrderType(rs.getString("IsOrderedEnum"));
                    order.setSpotNumber("Spot " + rs.getInt("ParkingSpot_ID")); // Use ParkingSpot_ID since SpotNumber doesn't exist
                    
                    // Convert SQL Date and Time to LocalDateTime
                    Date date = rs.getDate("Date");
                    Time startTime = rs.getTime("Actual_start_time");
                    Time endTime = rs.getTime("Actual_end_time");
                    Time estimatedEnd = rs.getTime("Estimated_end_time");
                    
                    if (date != null && startTime != null) {
                        order.setEntryTime(LocalDateTime.of(date.toLocalDate(), startTime.toLocalTime()));
                    }
                    if (date != null && endTime != null) {
                        order.setExitTime(LocalDateTime.of(date.toLocalDate(), endTime.toLocalTime()));
                    }
                    if (date != null && estimatedEnd != null) {
                        order.setExpectedExitTime(LocalDateTime.of(date.toLocalDate(), estimatedEnd.toLocalTime()));
                    }
                    
                    order.setLate(rs.getBoolean("IsLate"));
                    order.setExtended(rs.getBoolean("IsExtended"));
                    order.setStatus(endTime != null ? "Completed" : "Active");
                    
                    history.add(order);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting parking history: " + e.getMessage());
        }
        return history;
    }

    /**
     * Gets all active parking sessions (for attendant view)
     */
    public ArrayList<ParkingOrder> getActiveParkings() {
        ArrayList<ParkingOrder> activeParkings = new ArrayList<>();
        String qry = "SELECT pi.*, u.Name, ps.ParkingSpot_ID FROM ParkingInfo pi JOIN users u ON pi.User_ID = u.User_ID JOIN ParkingSpot ps ON pi.ParkingSpot_ID = ps.ParkingSpot_ID WHERE pi.Actual_end_time IS NULL ORDER BY pi.Actual_start_time";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ParkingOrder order = new ParkingOrder();
                    order.setOrderID(rs.getInt("ParkingInfo_ID"));
                    order.setParkingCode(String.valueOf(rs.getInt("Code")));
                    order.setOrderType(rs.getString("IsOrderedEnum"));
                    order.setSubscriberName(rs.getString("Name"));
                    order.setSpotNumber("Spot " + rs.getInt("ParkingSpot_ID")); // Use ParkingSpot_ID since SpotNumber doesn't exist
                    
                    // Convert SQL Date and Time to LocalDateTime
                    Date date = rs.getDate("Date");
                    Time startTime = rs.getTime("Actual_start_time");
                    Time estimatedEnd = rs.getTime("Estimated_end_time");
                    
                    if (date != null && startTime != null) {
                        order.setEntryTime(LocalDateTime.of(date.toLocalDate(), startTime.toLocalTime()));
                    }
                    if (date != null && estimatedEnd != null) {
                        order.setExpectedExitTime(LocalDateTime.of(date.toLocalDate(), estimatedEnd.toLocalTime()));
                    }
                    
                    order.setStatus("Active");
                    activeParkings.add(order);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting active parkings: " + e.getMessage());
        }
        return activeParkings;
    }

    /**
     * Updates subscriber information
     */
    public String updateSubscriberInfo(String updateData) {
        // Format: userName,phone,email
        String[] data = updateData.split(",");
        if (data.length != 3) {
            return "Invalid update data format";
        }
        
        String userName = data[0];
        String phone = data[1];
        String email = data[2];
        
        String qry = "UPDATE users SET Phone = ?, Email = ? WHERE UserName = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, phone);
            stmt.setString(2, email);
            stmt.setString(3, userName);
            
            int rowsUpdated = stmt.executeUpdate();
            if (rowsUpdated > 0) {
                return "Subscriber information updated successfully";
            }
        } catch (SQLException e) {
            System.out.println("Error updating subscriber info: " + e.getMessage());
        }
        return "Failed to update subscriber information";
    }

    /**
     * Cancels a reservation
     */
    public String cancelReservation(int reservationCode) {
        String qry = "UPDATE Reservations SET statusEnum = 'cancelled' WHERE Reservation_code = ? AND statusEnum = 'active'";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, reservationCode);
            int rowsUpdated = stmt.executeUpdate();
            
            if (rowsUpdated > 0) {
                return "Reservation cancelled successfully";
            }
        } catch (SQLException e) {
            System.out.println("Error cancelling reservation: " + e.getMessage());
        }
        return "Reservation not found or already cancelled";
    }

    /**
     * Logs out a user (for future use if needed)
     */
    public void logoutUser(String userName) {
        System.out.println("User logged out: " + userName);
        // Could implement logout tracking here if needed
    }

    /**
     * Initializes parking spots if they don't exist
     * Now works with AUTO_INCREMENT ParkingSpot_ID
     */
    public void initializeParkingSpots() {
        try {
            // Check if spots already exist
            String checkQry = "SELECT COUNT(*) FROM ParkingSpot";
            try (PreparedStatement stmt = conn.prepareStatement(checkQry)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        // Initialize parking spots - AUTO_INCREMENT will handle ParkingSpot_ID
                        String insertQry = "INSERT INTO ParkingSpot (isOccupied) VALUES (false)";
                        try (PreparedStatement insertStmt = conn.prepareStatement(insertQry)) {
                            for (int i = 1; i <= TOTAL_PARKING_SPOTS; i++) {
                                insertStmt.executeUpdate();
                            }
                        }
                        System.out.println("Successfully initialized " + TOTAL_PARKING_SPOTS + " parking spots with AUTO_INCREMENT");
                    } else {
                        System.out.println("Parking spots already exist: " + rs.getInt(1) + " spots found");
                    }
                }
            }
        } catch (SQLException e) {
            System.out.println("Error initializing parking spots: " + e.getMessage());
        }
    }
    // Helper methods

//    private int generateParkingCode() {
//        Random random = new Random();
//        return 100000 + random.nextInt(900000);
//    }

//    private int getUserID(String userName) {
//        String qry = "SELECT User_ID FROM users WHERE UserName = ?";
//        
//        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
//            stmt.setString(1, userName);
//            try (ResultSet rs = stmt.executeQuery()) {
//                if (rs.next()) {
//                    return rs.getInt("User_ID");
//                }
//            }
//        } catch (SQLException e) {
//            System.out.println("Error getting user ID: " + e.getMessage());
//        }
//        return -1;
//    }

    private int getAvailableParkingSpotID() {
        String qry = "SELECT ParkingSpot_ID FROM ParkingSpot WHERE isOccupied = false LIMIT 1";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("ParkingSpot_ID");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error getting available spot ID: " + e.getMessage());
        }
        return -1;
    }

    private boolean isParkingSpotAvailable(int spotID) {
        String qry = "SELECT isOccupied FROM ParkingSpot WHERE ParkingSpot_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, spotID);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return !rs.getBoolean("isOccupied");
                }
            }
        } catch (SQLException e) {
            System.out.println("Error checking spot availability: " + e.getMessage());
        }
        return false;
    }

//    private void updateParkingSpotStatus(int spotID, boolean isOccupied) {
//        String qry = "UPDATE ParkingSpot SET isOccupied = ? WHERE ParkingSpot_ID = ?";
//        
//        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
//            stmt.setBoolean(1, isOccupied);
//            stmt.setInt(2, spotID);
//            stmt.executeUpdate();
//        } catch (SQLException e) {
//            System.out.println("Error updating parking spot status: " + e.getMessage());
//        }
//    }

    private void updateReservationStatus(int reservationCode, String status) {
        String qry = "UPDATE Reservations SET statusEnum = ? WHERE Reservation_code = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setString(1, status);
            stmt.setInt(2, reservationCode);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.out.println("Error updating reservation status: " + e.getMessage());
        }
    }

    private void sendLateExitNotification(int userID) {
        // Simulate sending email/SMS notification
        String qry = "SELECT Email, Phone, Name FROM users WHERE User_ID = ?";
        
        try (PreparedStatement stmt = conn.prepareStatement(qry)) {
            stmt.setInt(1, userID);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String email = rs.getString("Email");
                    String phone = rs.getString("Phone");
                    String name = rs.getString("Name");
                    
                    System.out.println("Sending late exit notification to " + name + 
                                     " at email: " + email + " and phone: " + phone);
                }
            }
        } catch (SQLException e) {
            System.out.println("Error sending late notification: " + e.getMessage());
        }
    }
}