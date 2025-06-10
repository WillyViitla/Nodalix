// FULL UPDATED SimpleWebServer.java

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class SimpleWebServer {
    private static final File DATABASE_DIR = new File("databases");
    private static final String CONFIG_FILE = "config.properties";
    private static final Map<String, Long> sessions = new HashMap<>();
    private static final Properties config = new Properties();

    private static String USERNAME;
    private static String PASSWORD;
    private static String SECRET_KEY;
    private static int PORT;
    private static final int SESSION_TIMEOUT_MS = 5 * 60 * 1000;

    public static void start(int port) throws IOException {
        loadConfig();
        if (!DATABASE_DIR.exists()) DATABASE_DIR.mkdir();

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

            if (!isAuthenticated(authHeader)) {
                String response = "HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"SimpleWeb\"\r\n\r\n";
                out.write(response.getBytes());
                out.flush();
                return;
            }

            // Update session time
            sessions.put(authHeader, System.currentTimeMillis());

            // Expire old sessions
            sessions.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue() > SESSION_TIMEOUT_MS);

            if ("POST".equals(method)) {
                char[] buf = new char[contentLength];
                in.read(buf);
                body = new String(buf);
            }

            log(method + " " + path);

            if (path.equals("/") || path.equals("/index")) {
                sendHtml(out,
                    "<h1>Welcome</h1>" +
                    "<a href='/databases'>Databases</a> | " +
                    "<a href='/createdb'>Create Database</a> | " +
                    "<a href='/logs'>Logs</a> | " +
                    "<a href='/config'>Config</a>");
            }
            else if (path.equals("/createdb") && method.equals("GET")) {
                sendHtml(out,
                    "<h1>Create DB</h1>" +
                    "<form method='POST'>" +
                    "<input name='dbname' placeholder='mydb'>" +
                    "<button>Create</button>" +
                    "</form>");
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
                        sendHtml(out, "<p>Database already exists!</p><a href='/databases'>Back</a>");
                    }
                } else {
                    sendHtml(out, "<p>Invalid name</p><a href='/createdb'>Try Again</a>");
                }
            }
            else if (path.equals("/databases")) {
                StringBuilder html = new StringBuilder("<h1>Databases</h1><ul>");
                for (File db : DATABASE_DIR.listFiles((d, name) -> name.endsWith(".secdb"))) {
                    html.append("<li><a href='/viewdb?name=").append(db.getName()).append("'>")
                        .append(db.getName()).append("</a> ")
                        .append("<form method='POST' action='/deletedb' style='display:inline'>")
                        .append("<input type='hidden' name='dbname' value='").append(db.getName()).append("'>")
                        .append("<button>Delete</button></form></li>");
                }
                html.append("</ul><a href='/'>Back</a>");
                sendHtml(out, html.toString());
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
                    sendHtml(out, "<p>Database not found</p><a href='/databases'>Back</a>");
                    return;
                }
                UserDatabase db = new UserDatabase(dbFile);
                StringBuilder html = new StringBuilder("<h1>" + name + "</h1>");
                html.append(
                    "<h2>Create Table</h2>" +
                    "<form method='POST' action='/createtable'>" +
                    "<input type='hidden' name='dbname' value='" + name + "'>" +
                    "<input name='tablename' placeholder='tablename'>" +
                    "<input name='columns' placeholder='id,name,age'>" +
                    "<button>Create</button>" +
                    "</form>"
                );
                for (String table : db.getTables()) {
                    html.append("<h2>Table: ").append(table).append("</h2><table border='1'><tr>");
                    List<String> columns = db.getColumns(table);
                    for (String col : columns) html.append("<th>").append(col).append("</th>");
                    html.append("<th>Actions</th></tr>");
                    for (String[] row : db.getRows(table)) {
                        html.append("<tr>");
                        for (String cell : row) html.append("<td>").append(cell).append("</td>");
                        html.append("<td><form method='POST' action='/deleterow'><input type='hidden' name='dbname' value='").append(name).append("'><input type='hidden' name='table' value='").append(table).append("'><input type='hidden' name='id' value='").append(row[0]).append("'><button>Delete</button></form></td>");
                        html.append("</tr>");
                    }
                    html.append("</table>");
                }
                html.append("<a href='/databases'>Back</a>");
                sendHtml(out, html.toString());
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
                String logs = Files.readString(Path.of("server.log"));
                String html = """
                    <h1>Logs</h1>
                    <div style='margin-bottom: 10px;'>
                        <button onclick='refreshLogs()'>Refresh</button>
                        <button onclick='clearLogs()'>Clear Logs</button>
                    </div>
                    <pre id='logContainer' style='height: 400px; overflow-y: auto; border: 1px solid #ccc; padding: 10px; background: #f5f5f5; font-family: monospace;'>%s</pre>
                    <a href='/'>Back</a>
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
                    """.formatted(logs);
                sendHtml(out, html);
            }
            else if (path.equals("/clear-logs") && method.equals("POST")) {
                try (FileWriter fw = new FileWriter("server.log", false)) {
                    fw.write(""); // Clear the log file
                }
                log("Logs cleared by user");
                sendRedirect(out, "/logs");
            }
            else if (path.equals("/config") && method.equals("GET")) {
                String html = """
                    <h1>Server Configuration</h1>
                    <form method='POST'>
                        <label>Username: <input name='username' value='%s'></label><br>
                        <label>Password: <input type='password' name='password' value='%s'></label><br>
                        <label>Port: <input value='%s' readonly></label><br>
                        <label>Key: <input name='key' value='%s' readonly></label><br>
                        <button>Save</button>
                    </form>
                    <form method='POST' action='/regenerate-key' style='margin-top:20px'>
                        <button>Regenerate Key</button>
                    </form>
                    <a href='/'>Back</a>
                    """.formatted(
                    config.getProperty("server.username", "admin"),
                    config.getProperty("server.password", ""),
                    config.getProperty("server.port", "8080"),
                    SECRET_KEY
                );
                sendHtml(out, html);
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
            else {
                sendHtml(out, "<h1>404 Not Found</h1><a href='/'>Home</a>", 404);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
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
        String response = "HTTP/1.1 " + code + " OK\r\nContent-Type: text/html\r\n\r\n<html><body>" + html + "</body></html>";
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
}
