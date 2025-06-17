
---

# [![FKZ1AMP.md.png](https://iili.io/FKZ1AMP.md.png)](https://freeimage.host/i/FKZ1AMP)

# Nodalix

**A Lightweight Java-Based Web Server with Custom Database and Encryption**

---

## 📛 Badges

![Java](https://img.shields.io/badge/language-Java-blue?style=flat-square)
![License](https://img.shields.io/github/license/WillyViitla/Nodalix?style=flat-square)
![Build](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)
![Platform](https://img.shields.io/badge/platform-cross--platform-lightgrey?style=flat-square)
![Status](https://img.shields.io/badge/status-active-success?style=flat-square)

---

## 🔐 Preview Credentials

* **Username:** `admin`
* **Password:** `password`

---

## 📦 About Nodalix

Nodalix is a lightweight web server written entirely in Java. It features a custom-built encrypted database system, allowing you to securely manage your data directly through a user-friendly web interface. Designed with simplicity, flexibility, and security in mind, Nodalix serves as a strong foundation for Java-based server applications.

---

## ✨ Features

* **Fully Java-based Web Server** — No external dependencies required
* **Custom Encrypted Database** — Secure storage with built-in encryption
* **Web-Based Database Editing** — Manage your data easily via web interface
* **Configurable Server Settings** — Adjust server parameters to suit your needs
* **Comprehensive Logging** — Detailed logs for monitoring and troubleshooting
* **Improved Styling & Security** — Modern UI enhancements and stronger encryption

---

## ⚙️ Requirements

* **Java Development Kit (JDK) 11 or higher**
* **Git** (optional, for cloning the repository)

Make sure the `java` and `javac` commands are available in your terminal or command prompt.

---

## 🚀 Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/WillyViitla/Nodalix.git
cd Nodalix
```

### 2. Compile the Source Files

```bash
javac *.java
```

### 3. Run the Server

```bash
java Main
```

By default, the server will start at:
📍 `http://localhost:8080`

---

## ⚙️ Configuration

Edit the `config.properties` file to customize your setup:

* Server port number
* Admin username/password
* Database encryption keys
* Logging options
* Secret key for authentication
* Default startup database

---

## 📜 Logging

All server activity is logged and saved in the `logs/` directory to help with monitoring and debugging.

---

## 🖥️ How to Use

### ✅ Create Your First Page

1. **Create a New HTML File**
   Place your HTML file in the `/pages/` directory.
   Example: `pages/about.html`

2. **Register the Route**
   Open `SimpleWebServer.java` and find the `handleClient` method.
   Add a new route:

   ```java
   else if (path.equals("/about")) {
       serveStaticFile(out, new File(PAGES_DIR, "about.html"));
   }
   ```

3. **Access Your Page**
   Start the server and open:
   `http://localhost:8080/about`

✅ You’ve created your first Nodalix page!
(*This process will be automated in future releases.*)

---

## 🗃️ How to Use the Database

You can interact with encrypted databases via HTTP POST requests to the API.

### 🔑 Authorization Header

Use the secret key from `config.properties`:
`Authorization: Basic base64(secret:YOUR_SECRET_KEY)`

Example base64 of `secret:MYKEY` = `c2VjcmV0Ok1ZS0VZ`

---

### 📥 Insert Data

```http
POST /api/insert
Authorization: Basic c2VjcmV0OllPVVJfU0VDUkVUX0tFWQ==

Body:
file:mydb.secdb
table:users
row:1,john,john@email.com
```

---

### 📤 Get Data

```http
POST /api/get
Authorization: Basic c2VjcmV0OllPVVJfU0VDUkVUX0tFWQ==

Body:
file:mydb.secdb
table:users
```

---

### 🗑️ Delete Data

```http
POST /api/delete
Authorization: Basic c2VjcmV0OllPVVJfU0VDUkVUX0tFWQ==

Body:
file:mydb.secdb
table:users
row:1
```

---

### 📘 Parameters Reference

* `file:` your database filename (`.secdb`)
* `table:` the table inside the file to interact with
* `row:` the row data (format: `id,user,email`)
* `secret:` used in `Authorization` header for access

✅ That's how to securely interact with Nodalix database APIs!

---

## 🤝 Contributing

Contributions, ideas, and bug reports are always welcome.
Feel free to open issues or submit pull requests on GitHub.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

## 📬 Contact

Created by **Willy Viitla**
GitHub: [https://github.com/WillyViitla](https://github.com/WillyViitla)

---
