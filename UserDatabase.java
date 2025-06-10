import java.io.*;
import java.util.*;

public class UserDatabase {
    private final File file;
    private Map<String, List<String[]>> tables;
    private Map<String, List<String>> tableHeaders;

    public UserDatabase(File file) throws IOException {
        this.file = file;
        this.tables = new HashMap<>();
        this.tableHeaders = new HashMap<>();

        if (file.exists() && file.length() > 0) {
            load(); // Load existing data
        }
        // Note: No need for else block since we already initialized empty structures above
    }

    public void createTable(String tableName, String[] columns) throws IOException {
        if (tables.containsKey(tableName)) return;
        tableHeaders.put(tableName, Arrays.asList(columns));
        tables.put(tableName, new ArrayList<>());
        save();
    }

    public void insert(String tableName, String[] row) throws IOException {
        if (!tables.containsKey(tableName)) throw new IllegalArgumentException("No such table: " + tableName);
        tables.get(tableName).add(row);
        save();
    }

    // Get all table names
    public Set<String> getTables() {
        return tables.keySet();
    }

    // getRows() for compatibility with SimpleWebServer
    public List<String[]> getRows(String tableName) {
        return tables.getOrDefault(tableName, new ArrayList<>());
    }

    public List<String> getColumns(String tableName) {
        return tableHeaders.getOrDefault(tableName, new ArrayList<>());
    }

    public void deleteRow(String tableName, int index) throws IOException {
        List<String[]> rows = tables.get(tableName);
        if (rows != null && index >= 0 && index < rows.size()) {
            rows.remove(index);
            save();
        }
    }

    // Delete by ID (first column match)
    public void deleteRow(String tableName, String id) throws IOException {
        List<String[]> rows = tables.get(tableName);
        if (rows != null) {
            Iterator<String[]> it = rows.iterator();
            while (it.hasNext()) {
                String[] row = it.next();
                if (row.length > 0 && row[0].equals(id)) {
                    it.remove();
                    save();
                    return;
                }
            }
        }
    }

    public void resetTable(String tableName) throws IOException {
        if (tables.containsKey(tableName)) {
            tables.put(tableName, new ArrayList<>());
            save();
        }
    }

    public void deleteTable(String tableName) throws IOException {
        tables.remove(tableName);
        tableHeaders.remove(tableName);
        save();
    }

    private void save() throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
            out.writeObject(tableHeaders);
            out.writeObject(tables);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
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
