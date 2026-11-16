# Lab 7 — Web Server & Client (JavaFX + MySQL + Raw Socket)

Ứng dụng desktop JavaFX kết hợp web client, web server HTTP/1.1 viết tay trên raw
`Socket` và cơ sở dữ liệu MySQL (XAMPP, cổng **3307**). Giao diện desktop và web
đều ở **Light Mode**.

## Cấu trúc bắt buộc

```text
F:\Lab7_WebServer
├── src
│   ├── App.java
│   ├── core
│   │   ├── NetworkServer.java
│   │   ├── NetworkClient.java
│   │   └── DatabaseManager.java
│   └── ui
│       ├── MainView.java
│       └── style.css
├── web
│   └── index.html
├── libs/
├── web_data.json
├── web_history.json
├── run.bat
└── .gitignore
```

## Tính năng chính

- **Raw socket**: `GET`, `POST`, `HEAD`, HTTP/1.1, chunked, gzip/deflate, redirect.
- **MySQL 3307**: tự tạo database/bảng, seed 4 trang, lưu lịch sử và export JSON.
- **Phân giải thật IP/Port** bằng `InetAddress`, hiển thị `Time | IP | Port | Status`.
- **Rewrite link** trong HTML thành `javascript:loadUrl(...)` và chèn bridge script.
- **Desktop UI**: Enterprise Light Mode, header trắng, icon Ikonli FontAwesome 5,
  4 thẻ thống kê, bảng Request có icon, thanh URL Search + nút SEND, badge
  `Time | IP | Port | Status`, cùng các tab Dashboard / Client Browser / Database.
- **Web UI**: light glassmorphism, URL bar, GO, Back/Forward, iframe, History.
- **Server endpoints**: `/`, `/proxy`, `/history`, `/__health`, `/web_data.json`, `/web_history.json`.

## Yêu cầu

- JDK 21+
- JavaFX SDK 21.0.5 tại `libs/javafx-sdk-21.0.5/lib`
- MySQL Connector/J, Gson, SLF4J trong `libs`
- Ikonli 12.3.1: `ikonli-core`, `ikonli-javafx`, `ikonli-fontawesome5-pack`
- MySQL/MariaDB XAMPP đang chạy tại `127.0.0.1:3307`, user `root`, mật khẩu rỗng

## Chạy chương trình

```bat
run.bat
```

`run.bat` sẽ compile đúng 5 file Java, copy `style.css` vào `out/ui`, rồi chạy `App`.

Sau khi ứng dụng mở:

1. Tab **Dashboard** → bấm **Bật Server**.
2. Mở web client tại `http://localhost:8080/`.
3. Tab **Client Browser** để duyệt URL bằng raw socket; nhập URL rồi bấm **SEND**.
4. Tab **Database** để xem lịch sử và export `web_data.json`, `web_history.json`.

## Kiểm tra nhanh

```bat
curl -i http://localhost:8080/
curl -I http://localhost:8080/sinh-vien
curl -i -X POST -d "ho_ten=Nguyen%20Van%20A&ma_sv=24IT277" http://localhost:8080/lien-he
curl -i http://localhost:8080/__health
curl -i http://localhost:8080/history
```

## Ghi chú

- Các file JSON là file tự sinh, đã được `.gitignore` bỏ qua.
- Thư mục `out/`, `*.class`, `.vscode` đã được `.gitignore` bỏ qua.
- Nếu MySQL chưa chạy, server vẫn bật; các request proxy sẽ báo `historySaved = false`.
