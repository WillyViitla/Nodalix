// Nodalix - Modern Styled Web Server with Database Support

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class SimpleWebServer {
    private static final File DATABASE_DIR = new File("databases");
    private static final String CONFIG_FILE = "config.properties";
    private static final Map<String, Long> sessions = new HashMap<>();
    private static final Properties config = new Properties();
    private static final File PAGES_DIR = new File("pages");
    private static final Map<String, String> customEndpoints = new HashMap<>();


    private static String USERNAME;
    private static String PASSWORD;
    private static String SECRET_KEY;
    private static int PORT;
    private static final int SESSION_TIMEOUT_MS = 5 * 60 * 1000;

    public static void start(int port) throws IOException {
        loadConfig();
        scanEndpoints();
        if (!DATABASE_DIR.exists()) DATABASE_DIR.mkdir();
        if (!PAGES_DIR.exists()) {
            PAGES_DIR.mkdir();
            createDefaultIndexPage();
        }

        // Bind to all network interfaces, not just localhost
        ServerSocket serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        
        // Get and display the actual IP address
        String localIP = getLocalIPAddress();
        log("Server running on:");
        log("  Local: http://localhost:" + port);
        log("  Network: http://" + localIP + ":" + port);

        while (true) {
            Socket socket = serverSocket.accept();
            new Thread(() -> handleClient(socket)).start();
        }
    }

    private static boolean requiresAuthentication(String path) {
    // Define admin paths that require authentication
    String[] adminPaths = {
        "/admin",       // Admin panel
        "/createdb",    // Database creation
        "/databases",   // Database management
        "/deletedb",    // Database deletion
        "/viewdb",      // View database
        "/createtable", // Table creation
        "/deleterow",   // Row deletion
        "/logs",        // Server logs
        "/clear-logs",  // Clear logs
        "/config",      // Configuration
        "/regenerate-key" // Key regeneration
    };
    
    // Check if path starts with any admin path
    for (String adminPath : adminPaths) {
        if (path.startsWith(adminPath)) {
            return true;
        }
    }
    
    // All other paths are public
    return false;
}

    private static void handleClient(Socket socket) {
        try (
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream()
        ) {
            String authHeader = null;
            String line;
            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String method = requestLine.split(" ")[0];
            String path = requestLine.split(" ")[1];
            String body = "";

            int contentLength = 0;
            while (!(line = in.readLine()).isEmpty()) {
                if (line.toLowerCase().startsWith("authorization: basic")) {
                    authHeader = line.substring("Authorization: Basic ".length()).trim();
                } else if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":" )[1].trim());
                }
            }

            if (requiresAuthentication(path) && !isAuthenticated(authHeader)) {
                String response = "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Nodalix Admin\"\r\n\r\n";
                out.write(response.getBytes());
                out.flush();
                return;
            }

            // Update session time only for authenticated requests
            if (authHeader != null && isAuthenticated(authHeader)) {
                sessions.put(authHeader, System.currentTimeMillis());
                // Expire old sessions
                sessions.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > SESSION_TIMEOUT_MS);
            }

            if ("POST".equals(method)) {
                char[] buf = new char[contentLength];
                in.read(buf);
                body = new String(buf);
            }

            log(method + " " + path);

            if (path.equals("/")) {
                // Serve public index.html from /pages directory
                serveStaticFile(out, new File(PAGES_DIR, "index.html"));
            }
            else if (path.equals("/admin") || path.equals("/admin/")) {
                sendHtml(out, getHomePage());
            }
            else if (path.equals("/createdb") && method.equals("GET")) {
                sendHtml(out, getCreateDbPage());
            }
            else if (path.equals("/createdb") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String dbName = form.get("dbname");
                if (dbName != null && dbName.matches("\\w+")) {
                    File newDb = new File(DATABASE_DIR, dbName + ".secdb");
                    if (newDb.createNewFile()) {
                        log("Created database: " + dbName);
                        sendRedirect(out, "/databases");
                    } else {
                        sendHtml(out, getErrorPage("Database already exists!", "/databases", "Back to Databases"));
                    }
                } else {
                    sendHtml(out, getErrorPage("Invalid database name! Use only letters, numbers, and underscores.", "/createdb", "Try Again"));
                }
            }
            else if (path.equals("/databases")) {
                sendHtml(out, getDatabasesPage());
            }
            else if (path.equals("/deletedb") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String name = form.get("dbname");
                File dbFile = new File(DATABASE_DIR, name);
                if (dbFile.exists()) {
                    dbFile.delete();
                    log("Deleted database: " + name);
                }
                sendRedirect(out, "/databases");
            }
            else if (path.startsWith("/viewdb")) {
                String name = URLDecoder.decode(getQueryParam(path, "name"), "UTF-8");
                File dbFile = new File(DATABASE_DIR, name);
                if (!dbFile.exists()) {
                    sendHtml(out, getErrorPage("Database not found!", "/databases", "Back to Databases"));
                    return;
                }
                sendHtml(out, getViewDbPage(name, dbFile));
            }
            else if (path.equals("/createtable") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String dbName = form.get("dbname");
                String tableName = form.get("tablename");
                String columns = form.get("columns");
                if (dbName != null && tableName != null && columns != null) {
                    File dbFile = new File(DATABASE_DIR, dbName);
                    UserDatabase db = new UserDatabase(dbFile);
                    db.createTable(tableName, columns.split(","));
                    log("Created table '" + tableName + "' in DB " + dbName);
                }
                sendRedirect(out, "/viewdb?name=" + URLEncoder.encode(dbName, "UTF-8"));
            }
            else if (path.equals("/deleterow") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String dbName = form.get("dbname");
                String table = form.get("table");
                String id = form.get("id");
                File dbFile = new File(DATABASE_DIR, dbName);
                UserDatabase db = new UserDatabase(dbFile);
                db.deleteRow(table, id);
                log("Deleted row " + id + " in table '" + table + "'");
                sendRedirect(out, "/viewdb?name=" + URLEncoder.encode(dbName, "UTF-8"));
            }
            else if (path.equals("/logs")) {
                sendHtml(out, getLogsPage());
            }
            else if (path.equals("/clear-logs") && method.equals("POST")) {
                try (FileWriter fw = new FileWriter("server.log", false)) {
                    fw.write(""); // Clear the log file
                }
                log("Logs cleared by user");
                sendRedirect(out, "/logs");
            }
            else if (path.equals("/config") && method.equals("GET")) {
                sendHtml(out, getConfigPage());
            }
            else if (path.equals("/config") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String username = form.get("username");
                String password = form.get("password");

                if (username != null && password != null) {
                    config.setProperty("server.username", username);
                    config.setProperty("server.password", password);
                    try (FileWriter fw = new FileWriter(CONFIG_FILE)) {
                        config.store(fw, "Updated config");
                    }
                    log("Updated server config");
                }
                sendRedirect(out, "/config");
            }
            else if (path.equals("/regenerate-key") && method.equals("POST")) {
                SECRET_KEY = generateKey();
                config.setProperty("server.key", SECRET_KEY);
                try (FileWriter fw = new FileWriter(CONFIG_FILE)) {
                    config.store(fw, "Key regenerated");
                }
                sendRedirect(out, "/config");
            }
            else if (path.startsWith("/api/")) {
                handleApiRequest(out, method, path, body, authHeader);
            }
            else if (path.equals("/api/insert") && method.equals("POST")) {
                handleApiInsert(out, body);
            }
            // API Endpoint: Query data from database
            else if (path.equals("/api/query") && method.equals("POST")) {
                handleApiQuery(out, body);
            }
            // API Endpoint: Check if record exists
            else if (path.equals("/api/exists") && method.equals("POST")) {
                handleApiExists(out, body);
            }
            // API Endpoint: Get all rows from table
            else if (path.equals("/api/getrows") && method.equals("POST")) {
                handleApiGetRows(out, body);
            }
            else if (path.equals("/pages")) {
                sendHtml(out, getPagesPage());
            }
            else if (path.equals("/createpage") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String endpoint = form.get("endpoint");
                String content = form.get("content");
                
                if (endpoint != null && content != null && endpoint.matches("[a-zA-Z0-9-_]+")) {
                    if (!PAGES_DIR.exists()) {
                        PAGES_DIR.mkdirs();
                    }
                    
                    File pageFile = new File(PAGES_DIR, endpoint + ".html");
                    try (FileWriter fw = new FileWriter(pageFile)) {
                        fw.write(content);
                    }
                    
                    // Update endpoints map
                    customEndpoints.put("/" + endpoint, pageFile.getAbsolutePath());
                    log("Created page: " + endpoint);
                    sendRedirect(out, "/pages");
                } else {
                    sendHtml(out, getErrorPage("Invalid endpoint name! Use only letters, numbers, hyphens, and underscores.", "/pages", "Back to Pages"));
                }
            }
            else if (path.equals("/updatepage") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String filename = form.get("filename");
                String content = form.get("content");
                
                if (filename != null && content != null) {
                    File pageFile = new File(PAGES_DIR, filename);
                    if (pageFile.exists()) {
                        try (FileWriter fw = new FileWriter(pageFile)) {
                            fw.write(content);
                        }
                        log("Updated page: " + filename);
                    }
                }
                sendRedirect(out, "/pages");
            }
            else if (path.equals("/deletepage") && method.equals("POST")) {
                Map<String, String> form = parseFormData(body);
                String filename = form.get("filename");
                
                if (filename != null) {
                    File pageFile = new File(PAGES_DIR, filename);
                    if (pageFile.exists()) {
                        pageFile.delete();
                        // Remove from endpoints map
                        String endpoint = "/" + filename.replace(".html", "");
                        customEndpoints.remove(endpoint);
                        log("Deleted page: " + filename);
                    }
                }
                sendRedirect(out, "/pages");
            }
            else if (path.equals("/getpage") && method.equals("GET")) {
                String filename = getQueryParam(path, "filename");
                if (filename != null) {
                    File pageFile = new File(PAGES_DIR, filename);
                    if (pageFile.exists()) {
                        try {
                            String content = Files.readString(pageFile.toPath());
                            String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n" + content;
                            out.write(response.getBytes());
                            out.flush();
                            return;
                        } catch (IOException e) {
                            sendHtml(out, getErrorPage("Error reading file", "/pages", "Back to Pages"));
                            return;
                        }
                    }
                }
                sendHtml(out, getErrorPage("File not found", "/pages", "Back to Pages"));
            }
            else if (customEndpoints.containsKey(path)) {
                try {
                    String content = Files.readString(Path.of(customEndpoints.get(path)));
                    String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + content;
                    out.write(response.getBytes());
                    out.flush();
                } catch (IOException e) {
                    sendHtml(out, getErrorPage("Error loading page", "/", "Go Home"));
                }
            }
            else {
                sendHtml(out, get404Page(), 404);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handleStaticFile(OutputStream out, String path) throws IOException {
        // Remove /pages/ prefix and get actual file path
        String filePath = path.substring("/pages/".length());
        File file = new File("pages", filePath);
        
        if (!file.exists() || !file.isFile()) {
            sendHtml(out, get404Page(), 404);
            return;
        }
        
        // Determine content type
        String contentType = getContentType(filePath);
        
        // Read file content
        byte[] content = Files.readAllBytes(file.toPath());
        
        // Send response
        String response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "Content-Length: " + content.length + "\r\n\r\n";
        
        out.write(response.getBytes());
        out.write(content);
        out.flush();
    }

    private static String getContentType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        switch (extension) {
            case "html": return "text/html";
            case "css": return "text/css";
            case "js": return "application/javascript";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "svg": return "image/svg+xml";
            case "ico": return "image/x-icon";
            default: return "text/plain";
        }
    }

    private static void handleApiRequest(OutputStream out, String method, String path, String body, String authHeader) throws IOException {
        // Check API authentication using secret key
        if (!isApiAuthenticated(authHeader)) {
            sendJsonResponse(out, "{\"error\":\"Unauthorized - provide secret key\"}", 401);
            return;
        }

        String[] pathParts = path.split("/");
        if (pathParts.length < 3) {
            sendJsonResponse(out, "{\"error\":\"Invalid API path\"}", 400);
            return;
        }

        String action = pathParts[2]; // /api/action
        
        try {
            switch (action) {
                case "insert":
                    handleInsertApi(out, body);
                    break;
                case "get":
                    handleGetApi(out, body);
                    break;
                case "delete":
                    handleDeleteApi(out, body);
                    break;
                default:
                    sendJsonResponse(out, "{\"error\":\"Unknown API action\"}", 400);
            }
        } catch (Exception e) {
            sendJsonResponse(out, "{\"error\":\"" + e.getMessage() + "\"}", 500);
            log("API Error: " + e.getMessage());
        }
    }

    private static String getBaseTemplate(String title, String content) {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s - Nodalix</title>
                <script src="https://cdn.tailwindcss.com"></script>
                <link href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.0.0/css/all.min.css" rel="stylesheet">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap');
                    body { font-family: 'Inter', sans-serif; }
                    .gradient-bg { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); }
                    .card-shadow { box-shadow: 0 10px 25px rgba(0,0,0,0.1); }
                    .hover-lift:hover { transform: translateY(-2px); transition: all 0.3s ease; }
                    .glass-effect { backdrop-filter: blur(10px); background: rgba(255,255,255,0.1); }
                </style>
            </head>
            <body class="bg-gray-50 min-h-screen">
                <!-- Navigation -->
                <nav class="gradient-bg shadow-lg">
                    <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
                        <div class="flex justify-between items-center h-16">
                            <div class="flex items-center space-x-3">
                                <img src="logo.png" alt="Nodalix" class="h-8 w-8 rounded-lg" onerror="this.style.display='none'">
                                <h1 class="text-white text-xl font-bold">Nodalix</h1>
                                <span class="text-blue-200 text-sm">Database Server</span>
                            </div>
                            <div class="flex items-center space-x-4">
                                <a href="/" class="text-white hover:text-blue-200 transition-colors">
                                    <i class="fas fa-home mr-1"></i>Home
                                </a>
                                <a href="/databases" class="text-white hover:text-blue-200 transition-colors">
                                    <i class="fas fa-database mr-1"></i>Databases
                                </a>
                                <a href="/pages" class="text-white hover:text-blue-200 transition-colors">
                                    <i class="fas fa-file-code mr-1"></i>Pages
                                </a>
                                <a href="/logs" class="text-white hover:text-blue-200 transition-colors">
                                    <i class="fas fa-file-alt mr-1"></i>Logs
                                </a>
                                <a href="/config" class="text-white hover:text-blue-200 transition-colors">
                                    <i class="fas fa-cog mr-1"></i>Config
                                </a>
                            </div>
                        </div>
                    </div>
                </nav>

                <!-- Main Content -->
                <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
                    %s
                </div>
            </body>
            </html>
            """.formatted(title, content);
    }

    private static String getHomePage() {
        String content = """
            <div class="text-center mb-12">
                <h1 class="text-4xl font-bold text-gray-900 mb-4">Welcome to Nodalix</h1>
                <p class="text-xl text-gray-600">Professional Database Management Server</p>
            </div>

            <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                <a href="/databases" class="block bg-white rounded-xl p-6 card-shadow hover-lift">
                    <div class="text-center">
                        <div class="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <i class="fas fa-database text-2xl text-blue-600"></i>
                        </div>
                        <h3 class="text-lg font-semibold text-gray-900 mb-2">View Databases</h3>
                        <p class="text-gray-600">Manage your existing databases</p>
                    </div>
                </a>

                <a href="/createdb" class="block bg-white rounded-xl p-6 card-shadow hover-lift">
                    <div class="text-center">
                        <div class="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <i class="fas fa-plus-circle text-2xl text-green-600"></i>
                        </div>
                        <h3 class="text-lg font-semibold text-gray-900 mb-2">Create Database</h3>
                        <p class="text-gray-600">Set up a new database</p>
                    </div>
                </a>

                <a href="/logs" class="block bg-white rounded-xl p-6 card-shadow hover-lift">
                    <div class="text-center">
                        <div class="w-16 h-16 bg-purple-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <i class="fas fa-file-alt text-2xl text-purple-600"></i>
                        </div>
                        <h3 class="text-lg font-semibold text-gray-900 mb-2">Server Logs</h3>
                        <p class="text-gray-600">Monitor server activity</p>
                    </div>
                </a>

                <a href="/config" class="block bg-white rounded-xl p-6 card-shadow hover-lift">
                    <div class="text-center">
                        <div class="w-16 h-16 bg-orange-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <i class="fas fa-cog text-2xl text-orange-600"></i>
                        </div>
                        <h3 class="text-lg font-semibold text-gray-900 mb-2">Configuration</h3>
                        <p class="text-gray-600">Server settings</p>
                    </div>
                </a>

                <a href="/pages" class="block bg-white rounded-xl p-6 card-shadow hover-lift">
                    <div class="text-center">
                        <div class="w-16 h-16 bg-indigo-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <i class="fas fa-file-code text-2xl text-indigo-600"></i>
                        </div>
                        <h3 class="text-lg font-semibold text-gray-900 mb-2">Pages</h3>
                        <p class="text-gray-600">Manage custom endpoints</p>
                    </div>
                </a>
            </div>

            <div class="mt-12 bg-white rounded-xl p-8 card-shadow">
                <h2 class="text-2xl font-bold text-gray-900 mb-4">Server Status</h2>
                <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <div class="text-center p-4 bg-green-50 rounded-lg">
                        <div class="text-2xl font-bold text-green-600">Online</div>
                        <div class="text-gray-600">Server Status</div>
                    </div>
                    <div class="text-center p-4 bg-blue-50 rounded-lg">
                        <div class="text-2xl font-bold text-blue-600">%d</div>
                        <div class="text-gray-600">Active Sessions</div>
                    </div>
                    <div class="text-center p-4 bg-purple-50 rounded-lg">
                        <div class="text-2xl font-bold text-purple-600">%d</div>
                        <div class="text-gray-600">Databases</div>
                    </div>
                </div>
            </div>
            """.formatted(sessions.size(), DATABASE_DIR.listFiles((d, name) -> name.endsWith(".secdb")).length);

        return getBaseTemplate("Dashboard", content);
    }

    private static String getCreateDbPage() {
        String content = """
            <div class="max-w-2xl mx-auto">
                <div class="bg-white rounded-xl p-8 card-shadow">
                    <div class="text-center mb-8">
                        <div class="w-16 h-16 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-4">
                            <i class="fas fa-plus-circle text-2xl text-green-600"></i>
                        </div>
                        <h1 class="text-3xl font-bold text-gray-900 mb-2">Create New Database</h1>
                        <p class="text-gray-600">Set up a new database for your project</p>
                    </div>

                    <form method="POST" class="space-y-6">
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">Database Name</label>
                            <input type="text" name="dbname" 
                                   class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 transition-colors"
                                   placeholder="Enter database name (e.g., myproject_db)"
                                   pattern="\\w+" 
                                   title="Only letters, numbers, and underscores allowed"
                                   required>
                            <p class="text-sm text-gray-500 mt-2">Only letters, numbers, and underscores are allowed</p>
                        </div>

                        <div class="flex space-x-4">
                            <button type="submit" 
                                    class="flex-1 bg-blue-600 text-white py-3 px-6 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                                <i class="fas fa-plus mr-2"></i>Create Database
                            </button>
                            <a href="/databases" 
                               class="flex-1 bg-gray-200 text-gray-700 py-3 px-6 rounded-lg hover:bg-gray-300 transition-colors font-medium text-center">
                                <i class="fas fa-arrow-left mr-2"></i>Cancel
                            </a>
                        </div>
                    </form>
                </div>
            </div>
            """;

        return getBaseTemplate("Create Database", content);
    }

    private static String getDatabasesPage() {
        StringBuilder content = new StringBuilder();
        content.append("""
            <div class="flex justify-between items-center mb-8">
                <div>
                    <h1 class="text-3xl font-bold text-gray-900">Databases</h1>
                    <p class="text-gray-600">Manage your database collections</p>
                </div>
                <a href="/createdb" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                    <i class="fas fa-plus mr-2"></i>New Database
                </a>
            </div>
            """);

        File[] databases = DATABASE_DIR.listFiles((d, name) -> name.endsWith(".secdb"));
        
        if (databases == null || databases.length == 0) {
            content.append("""
                <div class="bg-white rounded-xl p-12 card-shadow text-center">
                    <div class="w-24 h-24 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-6">
                        <i class="fas fa-database text-3xl text-gray-400"></i>
                    </div>
                    <h3 class="text-xl font-semibold text-gray-900 mb-2">No Databases Found</h3>
                    <p class="text-gray-600 mb-6">Get started by creating your first database</p>
                    <a href="/createdb" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                        <i class="fas fa-plus mr-2"></i>Create Database
                    </a>
                </div>
                """);
        } else {
            content.append("<div class=\"grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6\">");
            
            for (File db : databases) {
                String dbName = db.getName();
                long size = db.length();
                String sizeStr = size > 1024 ? (size / 1024) + " KB" : size + " B";
                
                content.append("""
                    <div class="bg-white rounded-xl p-6 card-shadow hover-lift">
                        <div class="flex items-center justify-between mb-4">
                            <div class="w-12 h-12 bg-blue-100 rounded-lg flex items-center justify-center">
                                <i class="fas fa-database text-blue-600"></i>
                            </div>
                            <form method="POST" action="/deletedb" class="inline" 
                                  onsubmit="return confirm('Are you sure you want to delete this database?')">
                                <input type="hidden" name="dbname" value="%s">
                                <button type="submit" class="text-red-500 hover:text-red-700 transition-colors">
                                    <i class="fas fa-trash"></i>
                                </button>
                            </form>
                        </div>
                        
                        <h3 class="text-lg font-semibold text-gray-900 mb-2">%s</h3>
                        <p class="text-sm text-gray-500 mb-4">Size: %s</p>
                        
                        <a href="/viewdb?name=%s" 
                           class="block w-full bg-blue-600 text-white text-center py-2 rounded-lg hover:bg-blue-700 transition-colors">
                            <i class="fas fa-eye mr-2"></i>View Database
                        </a>
                    </div>
                    """.formatted(dbName, dbName.replace(".secdb", ""), sizeStr, dbName));
            }
            
            content.append("</div>");
        }

        return getBaseTemplate("Databases", content.toString());
    }

    private static String getViewDbPage(String dbName, File dbFile) {
        UserDatabase db = new UserDatabase(dbFile);
        StringBuilder content = new StringBuilder();
        
        content.append("""
            <div class="flex justify-between items-center mb-8">
                <div>
                    <h1 class="text-3xl font-bold text-gray-900">%s</h1>
                    <p class="text-gray-600">Database management and table operations</p>
                </div>
                <a href="/databases" class="bg-gray-200 text-gray-700 px-6 py-3 rounded-lg hover:bg-gray-300 transition-colors font-medium">
                    <i class="fas fa-arrow-left mr-2"></i>Back to Databases
                </a>
            </div>

            <!-- Create Table Form -->
            <div class="bg-white rounded-xl p-6 card-shadow mb-8">
                <h2 class="text-xl font-semibold text-gray-900 mb-4">
                    <i class="fas fa-table mr-2"></i>Create New Table
                </h2>
                <form method="POST" action="/createtable" class="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <input type="hidden" name="dbname" value="%s">
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">Table Name</label>
                        <input type="text" name="tablename" 
                               class="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                               placeholder="users" required>
                    </div>
                    <div>
                        <label class="block text-sm font-medium text-gray-700 mb-2">Columns</label>
                        <input type="text" name="columns" 
                               class="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                               placeholder="id,name,email" required>
                    </div>
                    <div class="flex items-end">
                        <button type="submit" 
                                class="w-full bg-green-600 text-white py-2 px-4 rounded-lg hover:bg-green-700 transition-colors">
                            <i class="fas fa-plus mr-2"></i>Create Table
                        </button>
                    </div>
                </form>
            </div>
            """.formatted(dbName.replace(".secdb", ""), dbName));

        // Display tables
        List<String> tables = db.getTables();
        if (tables.isEmpty()) {
            content.append("""
                <div class="bg-white rounded-xl p-12 card-shadow text-center">
                    <div class="w-24 h-24 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-6">
                        <i class="fas fa-table text-3xl text-gray-400"></i>
                    </div>
                    <h3 class="text-xl font-semibold text-gray-900 mb-2">No Tables Found</h3>
                    <p class="text-gray-600">Create your first table to get started</p>
                </div>
                """);
        } else {
            for (String table : tables) {
                content.append("""
                    <div class="bg-white rounded-xl p-6 card-shadow mb-6">
                        <h2 class="text-xl font-semibold text-gray-900 mb-4">
                            <i class="fas fa-table mr-2"></i>Table: %s
                        </h2>
                        <div class="overflow-x-auto">
                            <table class="min-w-full divide-y divide-gray-200">
                                <thead class="bg-gray-50">
                                    <tr>
                    """.formatted(table));

                List<String> columns = db.getColumns(table);
                for (String col : columns) {
                    content.append("""
                        <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">%s</th>
                        """.formatted(col));
                }
                content.append("""
                    <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
                    </tr>
                    </thead>
                    <tbody class="bg-white divide-y divide-gray-200">
                    """);

                List<String[]> rows = db.getRows(table);
                if (rows.isEmpty()) {
                    content.append("""
                        <tr>
                            <td colspan="%d" class="px-6 py-4 text-center text-gray-500">No data available</td>
                        </tr>
                        """.formatted(columns.size() + 1));
                } else {
                    for (String[] row : rows) {
                        content.append("<tr class=\"hover:bg-gray-50\">");
                        for (String cell : row) {
                            content.append("""
                                <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900">%s</td>
                                """.formatted(cell != null ? cell : ""));
                        }
                        content.append("""
                            <td class="px-6 py-4 whitespace-nowrap text-sm">
                                <form method="POST" action="/deleterow" class="inline"
                                      onsubmit="return confirm('Are you sure you want to delete this row?')">
                                    <input type="hidden" name="dbname" value="%s">
                                    <input type="hidden" name="table" value="%s">
                                    <input type="hidden" name="id" value="%s">
                                    <button type="submit" class="text-red-600 hover:text-red-900 transition-colors">
                                        <i class="fas fa-trash"></i>
                                    </button>
                                </form>
                            </td>
                            </tr>
                            """.formatted(dbName, table, row[0]));
                    }
                }
                
                content.append("""
                    </tbody>
                    </table>
                    </div>
                    </div>
                    """);
            }
        }

        return getBaseTemplate("Database: " + dbName.replace(".secdb", ""), content.toString());
    }

    private static boolean isApiAuthenticated(String authHeader) {
        if (authHeader == null) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader));
            return decoded.equals("secret:" + SECRET_KEY);
        } catch (Exception e) {
            return false;
        }
    }

    private static void handleInsertApi(OutputStream out, String requestBody) throws IOException {
        // Parse: file:filename.secdb table:tablename row:col1,col2,col3
        String[] parts = requestBody.split(" ");
        String filename = null, tablename = null, rowData = null;
        
        for (String part : parts) {
            if (part.startsWith("file:")) filename = part.substring(5);
            else if (part.startsWith("table:")) tablename = part.substring(6);
            else if (part.startsWith("row:")) rowData = part.substring(4);
        }
        
        if (filename == null || tablename == null || rowData == null) {
            sendJsonResponse(out, "{\"error\":\"Missing file, table, or row data\"}", 400);
            return;
        }
        
        File dbFile = new File(DATABASE_DIR, filename);
        if (!dbFile.exists()) {
            sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
            return;
        }
        
        UserDatabase db = new UserDatabase(dbFile);
        String[] rowValues = rowData.split(",");
        db.insert(tablename, rowValues);
        
        sendJsonResponse(out, "{\"success\":true,\"message\":\"Row inserted\"}", 200);
    }

    private static void handleGetApi(OutputStream out, String requestBody) throws IOException {
        // Parse: file:filename.secdb table:tablename
        String[] parts = requestBody.split(" ");
        String filename = null, tablename = null;
        
        for (String part : parts) {
            if (part.startsWith("file:")) filename = part.substring(5);
            else if (part.startsWith("table:")) tablename = part.substring(6);
        }
        
        if (filename == null || tablename == null) {
            sendJsonResponse(out, "{\"error\":\"Missing file or table name\"}", 400);
            return;
        }
        
        File dbFile = new File(DATABASE_DIR, filename);
        if (!dbFile.exists()) {
            sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
            return;
        }
        
        UserDatabase db = new UserDatabase(dbFile);
        List<String[]> rows = db.getRows(tablename);
        List<String> columns = db.getColumns(tablename);
        
        StringBuilder json = new StringBuilder();
        json.append("{\"columns\":[");
        for (int i = 0; i < columns.size(); i++) {
            json.append("\"").append(columns.get(i)).append("\"");
            if (i < columns.size() - 1) json.append(",");
        }
        json.append("],\"rows\":[");
        
        for (int i = 0; i < rows.size(); i++) {
            json.append("[");
            String[] row = rows.get(i);
            for (int j = 0; j < row.length; j++) {
                json.append("\"").append(row[j] != null ? row[j] : "").append("\"");
                if (j < row.length - 1) json.append(",");
            }
            json.append("]");
            if (i < rows.size() - 1) json.append(",");
        }
        json.append("]}");
        
        sendJsonResponse(out, json.toString(), 200);
    }

    private static void handleDeleteApi(OutputStream out, String requestBody) throws IOException {
        // Parse: file:filename.secdb table:tablename row:id
        String[] parts = requestBody.split(" ");
        String filename = null, tablename = null, rowId = null;
        
        for (String part : parts) {
            if (part.startsWith("file:")) filename = part.substring(5);
            else if (part.startsWith("table:")) tablename = part.substring(6);
            else if (part.startsWith("row:")) rowId = part.substring(4);
        }
        
        if (filename == null || tablename == null || rowId == null) {
            sendJsonResponse(out, "{\"error\":\"Missing file, table, or row ID\"}", 400);
            return;
        }
        
        File dbFile = new File(DATABASE_DIR, filename);
        if (!dbFile.exists()) {
            sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
            return;
        }
        
        UserDatabase db = new UserDatabase(dbFile);
        db.deleteRow(tablename, rowId);
        
        sendJsonResponse(out, "{\"success\":true,\"message\":\"Row deleted\"}", 200);
    }

    private static void sendJsonResponse(OutputStream out, String json, int statusCode) throws IOException {
        String status = statusCode == 200 ? "OK" : 
                    statusCode == 400 ? "Bad Request" :
                    statusCode == 401 ? "Unauthorized" :
                    statusCode == 404 ? "Not Found" : "Internal Server Error";
        
        String response = "HTTP/1.1 " + statusCode + " " + status + "\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE\r\n" +
                        "Access-Control-Allow-Headers: Content-Type, Authorization\r\n\r\n" + 
                        json;
        out.write(response.getBytes());
        out.flush();
    }

    private static String getLogsPage() {
        String logs = "";
        try {
            logs = Files.readString(Path.of("server.log"));
        } catch (IOException e) {
            logs = "No logs available";
        }

        String content = """
            <div class="flex justify-between items-center mb-8">
                <div>
                    <h1 class="text-3xl font-bold text-gray-900">Server Logs</h1>
                    <p class="text-gray-600">Monitor server activity and events</p>
                </div>
                <div class="flex space-x-3">
                    <button onclick="refreshLogs()" class="bg-blue-600 text-white px-4 py-2 rounded-lg hover:bg-blue-700 transition-colors">
                        <i class="fas fa-sync-alt mr-2"></i>Refresh
                    </button>
                    <button onclick="clearLogs()" class="bg-red-600 text-white px-4 py-2 rounded-lg hover:bg-red-700 transition-colors">
                        <i class="fas fa-trash mr-2"></i>Clear Logs
                    </button>
                </div>
            </div>

            <div class="bg-white rounded-xl p-6 card-shadow">
                <div class="bg-gray-900 rounded-lg p-4">
                    <pre id="logContainer" class="text-green-400 text-sm overflow-x-auto max-h-96 overflow-y-auto font-mono">%s</pre>
                </div>
            </div>

            <script>
                function scrollToBottom() {
                    const container = document.getElementById('logContainer');
                    container.scrollTop = container.scrollHeight;
                }
                
                function refreshLogs() {
                    window.location.reload();
                }
                
                function clearLogs() {
                    if (confirm('Are you sure you want to clear all logs?')) {
                        fetch('/clear-logs', { method: 'POST' })
                            .then(() => window.location.reload());
                    }
                }
                
                // Auto-scroll to bottom when page loads
                window.onload = scrollToBottom;
            </script>
            """.formatted(logs.isEmpty() ? "No logs available" : logs);

        return getBaseTemplate("Server Logs", content);
    }

    private static String getConfigPage() {
        String content = """
            <div class="max-w-4xl mx-auto">
                <div class="flex justify-between items-center mb-8">
                    <div>
                        <h1 class="text-3xl font-bold text-gray-900">Server Configuration</h1>
                        <p class="text-gray-600">Manage server settings and security</p>
                    </div>
                </div>

                <div class="grid grid-cols-1 lg:grid-cols-2 gap-8">
                    <!-- Authentication Settings -->
                    <div class="bg-white rounded-xl p-6 card-shadow">
                        <h2 class="text-xl font-semibold text-gray-900 mb-6">
                            <i class="fas fa-shield-alt mr-2"></i>Authentication
                        </h2>
                        <form method="POST" class="space-y-4">
                            <div>
                                <label class="block text-sm font-medium text-gray-700 mb-2">Username</label>
                                <input type="text" name="username" value="%s"
                                       class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                            </div>
                            <div>
                                <label class="block text-sm font-medium text-gray-700 mb-2">Password</label>
                                <input type="password" name="password" value="%s"
                                       class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500">
                            </div>
                            <button type="submit" class="w-full bg-blue-600 text-white py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                                <i class="fas fa-save mr-2"></i>Save Changes
                            </button>
                        </form>
                    </div>

                    <!-- Server Information -->
                    <div class="bg-white rounded-xl p-6 card-shadow">
                        <h2 class="text-xl font-semibold text-gray-900 mb-6">
                            <i class="fas fa-server mr-2"></i>Server Information
                        </h2>
                        <div class="space-y-4">
                            <div>
                                <label class="block text-sm font-medium text-gray-700 mb-2">Port</label>
                                <input type="text" value="%s" readonly
                                       class="w-full px-4 py-3 bg-gray-50 border border-gray-300 rounded-lg text-gray-600">
                            </div>
                            <div>
                                <label class="block text-sm font-medium text-gray-700 mb-2">Security Key</label>
                                <div class="flex space-x-2">
                                    <input type="text" value="%s" readonly
                                           class="flex-1 px-4 py-3 bg-gray-50 border border-gray-300 rounded-lg font-mono text-gray-600">
                                    <form method="POST" action="/regenerate-key" class="inline">
                                        <button type="submit" class="bg-orange-600 text-white px-4 py-3 rounded-lg hover:bg-orange-700 transition-colors"
                                                onclick="return confirm('Are you sure you want to regenerate the security key?')">
                                            <i class="fas fa-sync-alt"></i>
                                        </button>
                                    </form>
                                </div>
                                <p class="text-sm text-gray-500 mt-2">Used for internal security operations</p>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- System Status -->
                <div class="bg-white rounded-xl p-6 card-shadow mt-8">
                    <h2 class="text-xl font-semibold text-gray-900 mb-6">
                        <i class="fas fa-chart-line mr-2"></i>System Status
                    </h2>
                    <div class="grid grid-cols-1 md:grid-cols-4 gap-6">
                        <div class="text-center p-4 bg-green-50 rounded-lg">
                            <div class="text-2xl font-bold text-green-600">Online</div>
                            <div class="text-sm text-gray-600">Server Status</div>
                        </div>
                        <div class="text-center p-4 bg-blue-50 rounded-lg">
                            <div class="text-2xl font-bold text-blue-600">%d</div>
                            <div class="text-sm text-gray-600">Active Sessions</div>
                        </div>
                        <div class="text-center p-4 bg-purple-50 rounded-lg">
                            <div class="text-2xl font-bold text-purple-600">%d</div>
                            <div class="text-sm text-gray-600">Total Databases</div>
                        </div>
                        <div class="text-center p-4 bg-orange-50 rounded-lg">
                            <div class="text-2xl font-bold text-orange-600">%s</div>
                            <div class="text-sm text-gray-600">Uptime</div>
                        </div>
                    </div>
                </div>
            </div>
            """.formatted(
                config.getProperty("server.username", "admin"),
                config.getProperty("server.password", ""),
                config.getProperty("server.port", "8080"),
                SECRET_KEY,
                sessions.size(),
                DATABASE_DIR.listFiles((d, name) -> name.endsWith(".secdb")).length,
                "Active"
            );

        return getBaseTemplate("Configuration", content);
    }

    private static String getErrorPage(String message, String backUrl, String backText) {
        String content = """
            <div class="max-w-2xl mx-auto text-center">
                <div class="bg-white rounded-xl p-8 card-shadow">
                    <div class="w-16 h-16 bg-red-100 rounded-full flex items-center justify-center mx-auto mb-6">
                        <i class="fas fa-exclamation-triangle text-2xl text-red-600"></i>
                    </div>
                    <h1 class="text-2xl font-bold text-gray-900 mb-4">Oops! Something went wrong</h1>
                    <p class="text-gray-600 mb-8">%s</p>
                    <a href="%s" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                        <i class="fas fa-arrow-left mr-2"></i>%s
                    </a>
                </div>
            </div>
            """.formatted(message, backUrl, backText);

        return getBaseTemplate("Error", content);
    }

    private static String get404Page() {
        String content = """
            <div class="max-w-2xl mx-auto text-center">
                <div class="bg-white rounded-xl p-8 card-shadow">
                    <div class="w-24 h-24 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-6">
                        <i class="fas fa-search text-3xl text-gray-400"></i>
                    </div>
                    <h1 class="text-3xl font-bold text-gray-900 mb-4">404 - Page Not Found</h1>
                    <p class="text-gray-600 mb-8">The page you're looking for doesn't exist or has been moved.</p>
                    <a href="/" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                        <i class="fas fa-home mr-2"></i>Go Home
                    </a>
                </div>
            </div>
            """;

        return getBaseTemplate("Page Not Found", content);
    }

    private static boolean isAuthenticated(String authHeader) {
        if (authHeader == null) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader));
            return decoded.equals(USERNAME + ":" + PASSWORD);
        } catch (Exception e) {
            return false;
        }
    }

    private static void sendHtml(OutputStream out, String html) throws IOException {
        sendHtml(out, html, 200);
    }

    private static void sendHtml(OutputStream out, String html, int code) throws IOException {
        String response = "HTTP/1.1 " + code + " OK\r\nContent-Type: text/html\r\n\r\n" + html;
        out.write(response.getBytes());
        out.flush();
    }

    private static void sendRedirect(OutputStream out, String location) throws IOException {
        String response = "HTTP/1.1 302 Found\r\nLocation: " + location + "\r\n\r\n";
        out.write(response.getBytes());
        out.flush();
    }

    private static Map<String, String> parseFormData(String data) {
        Map<String, String> map = new HashMap<>();
        for (String pair : data.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2) {
                try {
                    map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    map.put(kv[0], kv[1]);
                }
            }
        }
        return map;
    }

    private static String getQueryParam(String path, String key) {
        int q = path.indexOf("?");
        if (q == -1) return null;
        String[] params = path.substring(q + 1).split("&");
        for (String p : params) {
            String[] kv = p.split("=");
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private static void loadConfig() throws IOException {
        File f = new File(CONFIG_FILE);
        if (f.exists()) {
            try (FileReader fr = new FileReader(f)) {
                config.load(fr);
            }
        }

        USERNAME = config.getProperty("server.username", "admin");
        PASSWORD = config.getProperty("server.password", "admin");
        PORT = Integer.parseInt(config.getProperty("server.port", "8080"));
        SECRET_KEY = config.getProperty("server.key");

        if (SECRET_KEY == null || SECRET_KEY.length() != 8) {
            SECRET_KEY = generateKey();
            config.setProperty("server.key", SECRET_KEY);
            try (FileWriter fw = new FileWriter(CONFIG_FILE)) {
                config.store(fw, "Updated config");
            }
        }

        log("Loaded config: port=" + PORT + ", user=" + USERNAME);
    }

    private static String generateKey() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(8);
        Random r = new Random();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(r.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "localhost";
    }

    private static void log(String text) {
        try (FileWriter fw = new FileWriter("server.log", true)) {
            fw.write("[" + new Date() + "] " + text + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleApiInsert(OutputStream out, String body) throws IOException {
        try {
            Map<String, String> params = parseFormData(body);
            String dbFile = params.get("dbfile");
            String dbTable = params.get("dbtable");
            String serverSecret = params.get("server_secret");
            
            // Verify server secret
            if (!SECRET_KEY.equals(serverSecret)) {
                sendJsonResponse(out, "{\"error\":\"Invalid server secret\"}", 401);
                return;
            }
            
            // Validate required parameters
            if (dbFile == null || dbTable == null) {
                sendJsonResponse(out, "{\"error\":\"Missing dbfile or dbtable parameter\"}", 400);
                return;
            }
            
            File db = new File(DATABASE_DIR, dbFile);
            if (!db.exists()) {
                sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
                return;
            }
            
            UserDatabase database = new UserDatabase(db);
            
            // Check if table exists
            if (!database.getTables().contains(dbTable)) {
                sendJsonResponse(out, "{\"error\":\"Table not found\"}", 404);
                return;
            }
            
            // Get table columns
            List<String> columns = database.getColumns(dbTable);
            String[] rowData = new String[columns.size()];
            
            // Fill row data from parameters
            for (int i = 0; i < columns.size(); i++) {
                String value = params.get(columns.get(i));
                rowData[i] = value != null ? value : "";
            }
            
            // Insert the row
            database.insert(dbTable, rowData);
            
            log("API: Inserted row into " + dbFile + "." + dbTable);
            sendJsonResponse(out, "{\"success\":true,\"message\":\"Row inserted successfully\"}", 200);
            
        } catch (Exception e) {
            log("API Insert Error: " + e.getMessage());
            sendJsonResponse(out, "{\"error\":\"Internal server error\"}", 500);
        }
    }

    private static void handleApiQuery(OutputStream out, String body) throws IOException {
        try {
            Map<String, String> params = parseFormData(body);
            String dbFile = params.get("dbfile");
            String dbTable = params.get("dbtable");
            String serverSecret = params.get("server_secret");
            String queryColumn = params.get("query_column");
            String queryValue = params.get("query_value");
            
            // Verify server secret
            if (!SECRET_KEY.equals(serverSecret)) {
                sendJsonResponse(out, "{\"error\":\"Invalid server secret\"}", 401);
                return;
            }
            
            // Validate required parameters
            if (dbFile == null || dbTable == null) {
                sendJsonResponse(out, "{\"error\":\"Missing dbfile or dbtable parameter\"}", 400);
                return;
            }
            
            File db = new File(DATABASE_DIR, dbFile);
            if (!db.exists()) {
                sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
                return;
            }
            
            UserDatabase database = new UserDatabase(db);
            
            if (!database.getTables().contains(dbTable)) {
                sendJsonResponse(out, "{\"error\":\"Table not found\"}", 404);
                return;
            }
            
            List<String> columns = database.getColumns(dbTable);
            List<String[]> rows = database.getRows(dbTable);
            
            StringBuilder jsonResult = new StringBuilder();
            jsonResult.append("{\"success\":true,\"columns\":[");
            
            // Add column names
            for (int i = 0; i < columns.size(); i++) {
                jsonResult.append("\"").append(columns.get(i)).append("\"");
                if (i < columns.size() - 1) jsonResult.append(",");
            }
            jsonResult.append("],\"rows\":[");
            
            // Filter rows if query parameters provided
            boolean first = true;
            for (String[] row : rows) {
                boolean includeRow = true;
                
                if (queryColumn != null && queryValue != null) {
                    int columnIndex = columns.indexOf(queryColumn);
                    if (columnIndex >= 0 && columnIndex < row.length) {
                        includeRow = queryValue.equals(row[columnIndex]);
                    } else {
                        includeRow = false;
                    }
                }
                
                if (includeRow) {
                    if (!first) jsonResult.append(",");
                    jsonResult.append("[");
                    for (int i = 0; i < row.length; i++) {
                        jsonResult.append("\"").append(row[i] != null ? row[i] : "").append("\"");
                        if (i < row.length - 1) jsonResult.append(",");
                    }
                    jsonResult.append("]");
                    first = false;
                }
            }
            
            jsonResult.append("]}");
            
            log("API: Queried " + dbFile + "." + dbTable);
            sendJsonResponse(out, jsonResult.toString(), 200);
            
        } catch (Exception e) {
            log("API Query Error: " + e.getMessage());
            sendJsonResponse(out, "{\"error\":\"Internal server error\"}", 500);
        }
    }
    
    private static void handleApiExists(OutputStream out, String body) throws IOException {
        try {
            Map<String, String> params = parseFormData(body);
            String dbFile = params.get("dbfile");
            String dbTable = params.get("dbtable");
            String serverSecret = params.get("server_secret");
            String checkColumn = params.get("check_column");
            String checkValue = params.get("check_value");
            
            // Verify server secret
            if (!SECRET_KEY.equals(serverSecret)) {
                sendJsonResponse(out, "{\"error\":\"Invalid server secret\"}", 401);
                return;
            }
            
            // Validate required parameters
            if (dbFile == null || dbTable == null || checkColumn == null || checkValue == null) {
                sendJsonResponse(out, "{\"error\":\"Missing required parameters\"}", 400);
                return;
            }
            
            File db = new File(DATABASE_DIR, dbFile);
            if (!db.exists()) {
                sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
                return;
            }
            
            UserDatabase database = new UserDatabase(db);
            
            if (!database.getTables().contains(dbTable)) {
                sendJsonResponse(out, "{\"error\":\"Table not found\"}", 404);
                return;
            }
            
            List<String> columns = database.getColumns(dbTable);
            List<String[]> rows = database.getRows(dbTable);
            
            int columnIndex = columns.indexOf(checkColumn);
            if (columnIndex < 0) {
                sendJsonResponse(out, "{\"error\":\"Column not found\"}", 404);
                return;
            }
            
            boolean exists = false;
            for (String[] row : rows) {
                if (columnIndex < row.length && checkValue.equals(row[columnIndex])) {
                    exists = true;
                    break;
                }
            }
            
            log("API: Checked existence in " + dbFile + "." + dbTable);
            sendJsonResponse(out, "{\"success\":true,\"exists\":" + exists + "}", 200);
            
        } catch (Exception e) {
            log("API Exists Error: " + e.getMessage());
            sendJsonResponse(out, "{\"error\":\"Internal server error\"}", 500);
        }
    }
    
    private static void handleApiGetRows(OutputStream out, String body) throws IOException {
        try {
            Map<String, String> params = parseFormData(body);
            String dbFile = params.get("dbfile");
            String dbTable = params.get("dbtable");
            String serverSecret = params.get("server_secret");
            
            // Verify server secret
            if (!SECRET_KEY.equals(serverSecret)) {
                sendJsonResponse(out, "{\"error\":\"Invalid server secret\"}", 401);
                return;
            }
            
            // Validate required parameters
            if (dbFile == null || dbTable == null) {
                sendJsonResponse(out, "{\"error\":\"Missing dbfile or dbtable parameter\"}", 400);
                return;
            }
            
            File db = new File(DATABASE_DIR, dbFile);
            if (!db.exists()) {
                sendJsonResponse(out, "{\"error\":\"Database file not found\"}", 404);
                return;
            }
            
            UserDatabase database = new UserDatabase(db);
            
            if (!database.getTables().contains(dbTable)) {
                sendJsonResponse(out, "{\"error\":\"Table not found\"}", 404);
                return;
            }
            
            List<String> columns = database.getColumns(dbTable);
            List<String[]> rows = database.getRows(dbTable);
            
            StringBuilder jsonResult = new StringBuilder();
            jsonResult.append("{\"success\":true,\"count\":").append(rows.size()).append(",\"columns\":[");
            
            // Add column names
            for (int i = 0; i < columns.size(); i++) {
                jsonResult.append("\"").append(columns.get(i)).append("\"");
                if (i < columns.size() - 1) jsonResult.append(",");
            }
            jsonResult.append("],\"rows\":[");
            
            // Add all rows
            for (int i = 0; i < rows.size(); i++) {
                String[] row = rows.get(i);
                jsonResult.append("[");
                for (int j = 0; j < row.length; j++) {
                    jsonResult.append("\"").append(row[j] != null ? row[j] : "").append("\"");
                    if (j < row.length - 1) jsonResult.append(",");
                }
                jsonResult.append("]");
                if (i < rows.size() - 1) jsonResult.append(",");
            }
            
            jsonResult.append("]}");
            
            log("API: Retrieved all rows from " + dbFile + "." + dbTable);
            sendJsonResponse(out, jsonResult.toString(), 200);
            
        } catch (Exception e) {
            log("API GetRows Error: " + e.getMessage());
            sendJsonResponse(out, "{\"error\":\"Internal server error\"}", 500);
        }
    }
    
    static class UserDatabase {
        private File dbFile;
        private Map<String, List<String[]>> tables;
        private Map<String, List<String>> tableHeaders;
        
        public UserDatabase(File dbFile) {
            this.dbFile = dbFile;
            this.tables = new HashMap<>();
            this.tableHeaders = new HashMap<>();
            
            if (dbFile.exists() && dbFile.length() > 0) {
                try {
                    load(); // Load existing data
                } catch (IOException e) {
                    System.err.println("Error loading database: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        public List<String> getTables() {
            return new ArrayList<>(tables.keySet());
        }
        
        public List<String> getColumns(String table) {
            return tableHeaders.getOrDefault(table, new ArrayList<>());
        }
        
        public List<String[]> getRows(String table) {
            return tables.getOrDefault(table, new ArrayList<>());
        }
        
        public void createTable(String tableName, String[] columns) {
            if (tables.containsKey(tableName)) return;
            tableHeaders.put(tableName, Arrays.asList(columns));
            tables.put(tableName, new ArrayList<>());
            try {
                save();
            } catch (IOException e) {
                System.err.println("Error saving database: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        public void insert(String tableName, String[] row) {
            if (!tables.containsKey(tableName)) {
                throw new IllegalArgumentException("No such table: " + tableName);
            }
            tables.get(tableName).add(row);
            try {
                save();
            } catch (IOException e) {
                System.err.println("Error saving database: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        public void deleteRow(String tableName, String id) {
            List<String[]> rows = tables.get(tableName);
            if (rows != null) {
                Iterator<String[]> it = rows.iterator();
                while (it.hasNext()) {
                    String[] row = it.next();
                    if (row.length > 0 && row[0].equals(id)) {
                        it.remove();
                        try {
                            save();
                        } catch (IOException e) {
                            System.err.println("Error saving database: " + e.getMessage());
                            e.printStackTrace();
                        }
                        return;
                    }
                }
            }
        }
        
        public void deleteRow(String tableName, int index) {
            List<String[]> rows = tables.get(tableName);
            if (rows != null && index >= 0 && index < rows.size()) {
                rows.remove(index);
                try {
                    save();
                } catch (IOException e) {
                    System.err.println("Error saving database: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        public void resetTable(String tableName) {
            if (tables.containsKey(tableName)) {
                tables.put(tableName, new ArrayList<>());
                try {
                    save();
                } catch (IOException e) {
                    System.err.println("Error saving database: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        
        public void deleteTable(String tableName) {
            tables.remove(tableName);
            tableHeaders.remove(tableName);
            try {
                save();
            } catch (IOException e) {
                System.err.println("Error saving database: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        private void save() throws IOException {
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(dbFile))) {
                out.writeObject(tableHeaders);
                out.writeObject(tables);
            }
        }
        
        @SuppressWarnings("unchecked")
        private void load() throws IOException {
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(dbFile))) {
                // Read tableHeaders first (as saved in save() method)
                Object headersObj = in.readObject();
                if (headersObj instanceof Map) {
                    this.tableHeaders = (Map<String, List<String>>) headersObj;
                }
                
                // Read tables second
                Object tablesObj = in.readObject();
                if (tablesObj instanceof Map) {
                    this.tables = (Map<String, List<String[]>>) tablesObj;
                }
            } catch (IOException | ClassNotFoundException e) {
                System.err.println("Error loading database file: " + e.getMessage());
                e.printStackTrace();
                // Reset to empty state on error
                this.tables = new HashMap<>();
                this.tableHeaders = new HashMap<>();
            }
        }
    }

    private static void createDefaultIndexPage() {
        try {
            String defaultIndex = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Welcome to Nodalix</title>
                    <script src="https://cdn.tailwindcss.com"></script>
                </head>
                <body class="bg-gray-100 min-h-screen flex items-center justify-center">
                    <div class="max-w-md mx-auto bg-white rounded-xl shadow-md overflow-hidden">
                        <div class="p-8 text-center">
                            <h1 class="text-3xl font-bold text-gray-900 mb-4">Welcome to Nodalix</h1>
                            <p class="text-gray-600 mb-6">Your lightweight Java web server is running!</p>
                            <a href="/admin" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors">
                                Admin Dashboard
                            </a>
                        </div>
                    </div>
                </body>
                </html>
                """;
            
            Files.write(new File(PAGES_DIR, "index.html").toPath(), defaultIndex.getBytes());
            log("Created default index.html in pages directory");
        } catch (IOException e) {
            log("Error creating default index page: " + e.getMessage());
        }
    }

    private static void serveStaticFile(OutputStream out, File file) throws IOException {
        if (!file.exists() || !file.isFile()) {
            sendHtml(out, get404Page(), 404);
            return;
        }
        
        String contentType = "text/html";
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".css")) contentType = "text/css";
        else if (fileName.endsWith(".js")) contentType = "application/javascript";
        else if (fileName.endsWith(".png")) contentType = "image/png";
        else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) contentType = "image/jpeg";
        else if (fileName.endsWith(".gif")) contentType = "image/gif";
        
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        String response = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + fileBytes.length + "\r\n\r\n";
        out.write(response.getBytes());
        out.write(fileBytes);
        out.flush();
    }

    private static void scanEndpoints() {
        if (!PAGES_DIR.exists()) {
            PAGES_DIR.mkdirs();
        }
        
        File[] htmlFiles = PAGES_DIR.listFiles((dir, name) -> name.endsWith(".html"));
        if (htmlFiles != null) {
            for (File file : htmlFiles) {
                String fileName = file.getName().replace(".html", "");
                String endpoint = "/" + fileName;
                customEndpoints.put(endpoint, file.getAbsolutePath());
            }
        }
    }

    private static String getPagesPage() {
        // Scan for endpoints on each page load
        scanEndpoints();
        
        StringBuilder content = new StringBuilder();
        content.append("""
            <div class="flex justify-between items-center mb-8">
                <div>
                    <h1 class="text-3xl font-bold text-gray-900">Pages Management</h1>
                    <p class="text-gray-600">Create and manage custom endpoints</p>
                </div>
                <button onclick="showCreateModal()" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                    <i class="fas fa-plus mr-2"></i>New Page
                </button>
            </div>

            <!-- Create Page Modal -->
            <div id="createModal" class="fixed inset-0 bg-gray-600 bg-opacity-50 hidden flex items-center justify-center z-50">
                <div class="bg-white rounded-xl p-8 max-w-2xl w-full mx-4 card-shadow">
                    <div class="flex justify-between items-center mb-6">
                        <h2 class="text-2xl font-bold text-gray-900">Create New Page</h2>
                        <button onclick="hideCreateModal()" class="text-gray-400 hover:text-gray-600">
                            <i class="fas fa-times text-xl"></i>
                        </button>
                    </div>
                    
                    <form method="POST" action="/createpage" class="space-y-6">
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">Endpoint Name</label>
                            <div class="flex items-center">
                                <span class="text-gray-500 mr-2">/</span>
                                <input type="text" name="endpoint" 
                                    class="flex-1 px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
                                    placeholder="my-page" 
                                    pattern="[a-zA-Z0-9-_]+" 
                                    title="Only letters, numbers, hyphens, and underscores allowed"
                                    required>
                            </div>
                            <p class="text-sm text-gray-500 mt-1">This will create the endpoint and corresponding HTML file</p>
                        </div>

                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">HTML Content</label>
                            <textarea name="content" rows="12" 
                                    class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 font-mono text-sm"
                                    placeholder="Enter your HTML content here..."
                                    required></textarea>
                        </div>

                        <div class="flex space-x-4">
                            <button type="submit" 
                                    class="flex-1 bg-blue-600 text-white py-3 px-6 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                                <i class="fas fa-save mr-2"></i>Create Page
                            </button>
                            <button type="button" onclick="hideCreateModal()" 
                                class="flex-1 bg-gray-200 text-gray-700 py-3 px-6 rounded-lg hover:bg-gray-300 transition-colors font-medium">
                                <i class="fas fa-times mr-2"></i>Cancel
                            </button>
                        </div>
                    </form>
                </div>
            </div>
            """);

        // Display existing pages
        File[] htmlFiles = PAGES_DIR.listFiles((dir, name) -> name.endsWith(".html"));
        
        if (htmlFiles == null || htmlFiles.length == 0) {
            content.append("""
                <div class="bg-white rounded-xl p-12 card-shadow text-center">
                    <div class="w-24 h-24 bg-gray-100 rounded-full flex items-center justify-center mx-auto mb-6">
                        <i class="fas fa-file-code text-3xl text-gray-400"></i>
                    </div>
                    <h3 class="text-xl font-semibold text-gray-900 mb-2">No Pages Found</h3>
                    <p class="text-gray-600 mb-6">Create your first custom page to get started</p>
                    <button onclick="showCreateModal()" class="bg-blue-600 text-white px-6 py-3 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                        <i class="fas fa-plus mr-2"></i>Create Page
                    </button>
                </div>
                """);
        } else {
            content.append("""
                <div class="bg-white rounded-xl p-6 card-shadow">
                    <h2 class="text-xl font-semibold text-gray-900 mb-6">
                        <i class="fas fa-list mr-2"></i>Active Pages
                    </h2>
                    <div class="overflow-x-auto">
                        <table class="min-w-full divide-y divide-gray-200">
                            <thead class="bg-gray-50">
                                <tr>
                                    <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Endpoint</th>
                                    <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File</th>
                                    <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Size</th>
                                    <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Last Modified</th>
                                    <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
                                </tr>
                            </thead>
                            <tbody class="bg-white divide-y divide-gray-200">
                """);

            for (File file : htmlFiles) {
                String fileName = file.getName();
                String endpointName = fileName.replace(".html", "");
                String endpoint = "/" + endpointName;
                long size = file.length();
                String sizeStr = size > 1024 ? (size / 1024) + " KB" : size + " B";
                String lastModified = new Date(file.lastModified()).toString();
                
                content.append("""
                    <tr class="hover:bg-gray-50">
                        <td class="px-6 py-4 whitespace-nowrap">
                            <div class="flex items-center">
                                <i class="fas fa-link text-blue-500 mr-2"></i>
                                <a href="%s" target="_blank" class="text-blue-600 hover:text-blue-800 font-medium">%s</a>
                            </div>
                        </td>
                        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-900 font-mono">%s</td>
                        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">%s</td>
                        <td class="px-6 py-4 whitespace-nowrap text-sm text-gray-500">%s</td>
                        <td class="px-6 py-4 whitespace-nowrap text-sm space-x-2">
                            <button onclick="editPage('%s')" class="text-blue-600 hover:text-blue-800 transition-colors">
                                <i class="fas fa-edit"></i>
                            </button>
                            <form method="POST" action="/deletepage" class="inline"
                                onsubmit="return confirm('Are you sure you want to delete this page?')">
                                <input type="hidden" name="filename" value="%s">
                                <button type="submit" class="text-red-600 hover:text-red-800 transition-colors">
                                    <i class="fas fa-trash"></i>
                                </button>
                            </form>
                        </td>
                    </tr>
                    """.formatted(endpoint, endpoint, fileName, sizeStr, lastModified, fileName, fileName));
            }
            
            content.append("""
                            </tbody>
                        </table>
                    </div>
                </div>
                """);
        }

        content.append("""
            <!-- Edit Page Modal -->
            <div id="editModal" class="fixed inset-0 bg-gray-600 bg-opacity-50 hidden flex items-center justify-center z-50">
                <div class="bg-white rounded-xl p-8 max-w-4xl w-full mx-4 card-shadow max-h-screen overflow-y-auto">
                    <div class="flex justify-between items-center mb-6">
                        <h2 class="text-2xl font-bold text-gray-900">Edit Page</h2>
                        <button onclick="hideEditModal()" class="text-gray-400 hover:text-gray-600">
                            <i class="fas fa-times text-xl"></i>
                        </button>
                    </div>
                    
                    <form method="POST" action="/updatepage" class="space-y-6">
                        <input type="hidden" name="filename" id="editFilename">
                        
                        <div>
                            <label class="block text-sm font-medium text-gray-700 mb-2">File: <span id="editFileDisplay" class="font-mono text-blue-600"></span></label>
                            <textarea name="content" id="editContent" rows="20" 
                                    class="w-full px-4 py-3 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 font-mono text-sm"
                                    required></textarea>
                        </div>

                        <div class="flex space-x-4">
                            <button type="submit" 
                                    class="flex-1 bg-blue-600 text-white py-3 px-6 rounded-lg hover:bg-blue-700 transition-colors font-medium">
                                <i class="fas fa-save mr-2"></i>Update Page
                            </button>
                            <button type="button" onclick="hideEditModal()" 
                                class="flex-1 bg-gray-200 text-gray-700 py-3 px-6 rounded-lg hover:bg-gray-300 transition-colors font-medium">
                                <i class="fas fa-times mr-2"></i>Cancel
                            </button>
                        </div>
                    </form>
                </div>
            </div>

            <script>
                function showCreateModal() {
                    document.getElementById('createModal').classList.remove('hidden');
                }
                
                function hideCreateModal() {
                    document.getElementById('createModal').classList.add('hidden');
                }
                
                function hideEditModal() {
                    document.getElementById('editModal').classList.add('hidden');
                }
                
                async function editPage(filename) {
                    try {
                        const response = await fetch('/getpage?filename=' + encodeURIComponent(filename));
                        const content = await response.text();
                        
                        document.getElementById('editFilename').value = filename;
                        document.getElementById('editFileDisplay').textContent = filename;
                        document.getElementById('editContent').value = content;
                        document.getElementById('editModal').classList.remove('hidden');
                    } catch (error) {
                        alert('Error loading page content: ' + error.message);
                    }
                }
            </script>
            """);

        return getBaseTemplate("Pages Management", content.toString());
    }
}
