package ui;

import core.DatabaseManager;
import core.NetworkClient;
import core.NetworkServer;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;
import org.kordamp.ikonli.javafx.FontIcon;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Toàn bộ giao diện desktop của Lab 7 trong một file duy nhất.
 *
 * <p>Ba tab:
 * <ol>
 *   <li><b>Server</b> — bật/tắt, cổng, log, trạng thái MySQL, số request, bảng request.</li>
 *   <li><b>Browser</b> — thanh URL, WebView, thống kê Time/IP/Port/Status và bảng lịch sử
 *       của phiên duyệt hiện tại.</li>
 *   <li><b>Database</b> — lịch sử trong MySQL, xem JSON và xuất file.</li>
 * </ol>
 */
public final class MainView {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String DEFAULT_URL = "http://localhost:8080/gioi-thieu";

    private static final Color INK = Color.web("#343a40");
    private static final Color MUTED = Color.web("#6c757d");
    private static final Color BLUE = Color.web("#007bff");
    private static final Color DEEP_BLUE = Color.web("#0056b3");
    private static final Color GREEN = Color.web("#28a745");
    private static final Color AMBER = Color.web("#fd7e14");
    private static final Color RED = Color.web("#dc3545");
    private static final Color VIOLET = Color.web("#6f42c1");
    private static final Color WHITE = Color.web("#ffffff");

    private record BrowserHit(String time, String ip, int port, int status,
            String url, long millis) {
    }

    private record BrowserResult(NetworkClient.Response response, String preparedHtml,
            String title, NetworkClient.Analysis analysis, boolean historySaved) {
    }

    private record DbSnapshot(List<DatabaseManager.History> history, int pageCount,
            String pagesJson, String historyJson) {
    }

    private final DatabaseManager database = new DatabaseManager();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "lab7-ui-worker");
        thread.setDaemon(true);
        return thread;
    });

    private NetworkServer server;
    private Stage stage;
    private String cssUrl;

    private final Label clockLabel = new Label("--:--:--");
    private final Label serverPill = new Label("ĐANG TẮT");
    private final Label dbPill = new Label("DB: ĐANG KIỂM TRA");
    private final FontIcon dbPillIcon = icon(FontAwesomeSolid.DATABASE, 12, GREEN);
    private final FontIcon dbCardIcon = icon(FontAwesomeSolid.DATABASE, 26, VIOLET);
    private final FontIcon statusBadgeIcon = icon(FontAwesomeSolid.CHECK_CIRCLE, 15, MUTED);
    private final Label requestsValue = new Label("0");
    private final Label uptimeValue = new Label("00:00:00");
    private final Label activeValue = new Label("0");
    private final Label dbStatusValue = new Label("Đang kiểm tra");
    private final Label headerUptimeLabel = new Label("Uptime 00:00:00");
    private final Label dbInfoLabel = new Label("Chưa kiểm tra MySQL.");
    private final Label serverUrlLabel = new Label("Chưa bật server.");

    private final TextField portField = new TextField(Integer.toString(NetworkServer.DEFAULT_PORT));
    private final Button startButton = new Button("Bật server");
    private final Button stopButton = new Button("Tắt server");
    private final Button openWebButton = new Button("Mở Web Client");

    private final ObservableList<NetworkServer.RequestInfo> serverRows =
            FXCollections.observableArrayList();
    private final TextArea logArea = new TextArea();

    private final ComboBox<String> methodBox = new ComboBox<>();
    private final TextField urlField = new TextField(DEFAULT_URL);
    private final TextField postBodyField = new TextField();
    private final Button goButton = new Button("SEND");
    private final Button backButton = new Button("‹");
    private final Button forwardButton = new Button("›");
    private final Button reloadButton = new Button("↻");
    private final WebView webView = new WebView();
    private final WebEngine webEngine = webView.getEngine();
    private final TextArea plainArea = new TextArea();
    private final Label timeValue = new Label("--:--:--");
    private final Label ipValue = new Label("—");
    private final Label portValue = new Label("—");
    private final Label statusValue = new Label("—");
    private final Label analysisLabel = new Label("p 0 · div 0 · span 0 · img 0 · a 0");
    private final ObservableList<BrowserHit> browserRows = FXCollections.observableArrayList();

    private final ObservableList<DatabaseManager.History> historyRows =
            FXCollections.observableArrayList();
    private final TableView<DatabaseManager.History> historyTable = new TableView<>();
    private final TextArea pagesJsonArea = new TextArea();
    private final TextArea historyJsonArea = new TextArea();
    private final Label pagesCountLabel = new Label("0 trang");
    private final Label dbNoticeLabel = new Label("Chưa tải dữ liệu.");

    private final List<String> backStack = new ArrayList<>();
    private int backIndex = -1;
    private int loadToken;
    private boolean suppressLocation;
    private String currentLoadedUrl = "";
    private TabPane mainTabs;

    public MainView() {
        server = new NetworkServer(database, new NetworkServer.Listener() {
            @Override
            public void onLog(String message) {
                appendLog(message);
            }

            @Override
            public void onRequest(NetworkServer.RequestInfo info) {
                Platform.runLater(() -> addServerRequest(info));
            }
        });
        startClock();
    }

    // ------------------------------------------------------------------
    // Cửa sổ chính
    // ------------------------------------------------------------------

    public void show(Stage stage) {
        // Bắt buộc: đặt encoding trước khi JavaFX dựng giao diện.
        System.setProperty("file.encoding", "UTF-8");
        this.stage = stage;

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildHeader());
        mainTabs = buildTabs();
        root.setCenter(mainTabs);

        Scene scene = new Scene(root, 1320, 840);
        URL css = MainView.class.getResource("style.css");
        if (css == null) {
            css = MainView.class.getResource("/ui/style.css");
        }
        if (css != null) {
            cssUrl = css.toExternalForm();
            scene.getStylesheets().add(cssUrl);
        }

        stage.setTitle("HTTP Server & Client Dashboard · Lab 7");
        stage.setMinWidth(1120);
        stage.setMinHeight(740);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> shutdown());
        stage.setOnHidden(event -> shutdown());
        stage.show();

        showWelcome();
        appendLog("[HỆ THỐNG] Giao diện sẵn sàng. Bấm “Bật server” để mở cổng "
                + portField.getText() + ".");
        initializeDatabase();
    }

    private VBox buildHeader() {
        FontIcon logoIcon = icon(FontAwesomeSolid.SERVER, 24, WHITE);
        StackPane mark = new StackPane(logoIcon);
        mark.getStyleClass().add("brand-mark");

        Label title = new Label("HTTP SERVER & CLIENT DASHBOARD");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("Raw Socket · JavaFX · MySQL · Web Client");
        subtitle.getStyleClass().add("brand-subtitle");
        VBox titles = new VBox(2, title, subtitle);
        HBox brand = new HBox(12, mark, titles);
        brand.setAlignment(Pos.CENTER_LEFT);

        clockLabel.getStyleClass().add("clock");
        clockLabel.setGraphic(icon(FontAwesomeSolid.CLOCK, 12, DEEP_BLUE));
        headerUptimeLabel.getStyleClass().addAll("clock", "header-uptime");
        headerUptimeLabel.setGraphic(icon(FontAwesomeSolid.CLOCK, 12, DEEP_BLUE));
        dbPill.getStyleClass().addAll("pill", "pill-warn");
        dbPill.setGraphic(dbPillIcon);

        portField.getStyleClass().addAll("field", "header-port");
        portField.setPrefWidth(74);
        portField.setTooltip(new Tooltip("Cổng HTTP, mặc định 8080"));
        Label portCaption = new Label("Cổng");
        portCaption.getStyleClass().add("header-label");
        portCaption.setGraphic(icon(FontAwesomeSolid.PLUG, 11, BLUE));
        HBox portBox = new HBox(6, portCaption, portField);
        portBox.setAlignment(Pos.CENTER_LEFT);

        startButton.setText("Bật Server");
        stopButton.setText("Tắt Server");
        startButton.setGraphic(icon(FontAwesomeSolid.PLAY, 13, WHITE));
        stopButton.setGraphic(icon(FontAwesomeSolid.STOP, 13, WHITE));
        startButton.getStyleClass().add("button-success");
        stopButton.getStyleClass().add("button-danger");
        openWebButton.setText("Web Client");
        openWebButton.setGraphic(icon(FontAwesomeSolid.GLOBE, 13, DEEP_BLUE));
        openWebButton.getStyleClass().add("button-ghost");
        openWebButton.setDisable(true);
        stopButton.setDisable(true);

        startButton.setOnAction(event -> startServer());
        stopButton.setOnAction(event -> stopServer());
        openWebButton.setOnAction(event -> openWebClient());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(9, dbPill, headerUptimeLabel, clockLabel, portBox,
                startButton, stopButton, openWebButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(18, brand, spacer, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return new VBox(header);
    }

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("main-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab dashboardTab = new Tab("Dashboard", buildServerTab());
        dashboardTab.setGraphic(icon(FontAwesomeSolid.CHART_BAR, 14, BLUE));
        Tab browserTab = new Tab("Client Browser", buildBrowserTab());
        browserTab.setGraphic(icon(FontAwesomeSolid.GLOBE, 14, BLUE));
        Tab databaseTab = new Tab("Database", buildDatabaseTab());
        databaseTab.setGraphic(icon(FontAwesomeSolid.DATABASE, 14, GREEN));

        tabs.getTabs().addAll(dashboardTab, browserTab, databaseTab);
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, old, index) -> {
            if (index != null && index.intValue() == 2) {
                refreshDatabaseView(false);
            }
        });
        return tabs;
    }

    // ------------------------------------------------------------------
    // Tab 1 — Server
    // ------------------------------------------------------------------

    private VBox buildServerTab() {
        VBox tab = new VBox(14);
        tab.getStyleClass().add("tab-content");

        HBox metrics = new HBox(14,
                metricCard("Tổng Request", requestsValue, "metric-blue",
                        icon(FontAwesomeSolid.CHART_BAR, 26, BLUE), "metric-icon-blue"),
                metricCard("Uptime", uptimeValue, "metric-green",
                        icon(FontAwesomeSolid.CLOCK, 26, GREEN), "metric-icon-green"),
                metricCard("DB Status", dbStatusValue, "metric-violet",
                        dbCardIcon, "metric-icon-violet"),
                metricCard("Active Clients", activeValue, "metric-amber",
                        icon(FontAwesomeSolid.USERS, 26, AMBER), "metric-icon-amber"));
        for (var node : metrics.getChildren()) {
            HBox.setHgrow(node, Priority.ALWAYS);
        }
        setDbCard("Đang kiểm tra", "stat-neutral");

        serverPill.getStyleClass().addAll("pill", "pill-neutral");
        serverPill.setGraphic(icon(FontAwesomeSolid.SERVER, 11, DEEP_BLUE));
        serverUrlLabel.getStyleClass().add("muted");
        dbInfoLabel.getStyleClass().add("muted");
        dbInfoLabel.setWrapText(true);
        Label hint = new Label("Điều khiển server bằng nút Bật/Tắt ở góc phải header. "
                + "Cổng mặc định là 8080.");
        hint.getStyleClass().add("muted");
        hint.setGraphic(icon(FontAwesomeSolid.INFO_CIRCLE, 12, MUTED));
        HBox statusRow = new HBox(10, serverPill, spacer(), hint);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        VBox controlCard = new VBox(9, statusRow, serverUrlLabel, dbInfoLabel);
        controlCard.getStyleClass().add("card");

        TableView<NetworkServer.RequestInfo> requestTable = new TableView<>(serverRows);
        requestTable.getStyleClass().add("data-table");
        requestTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        requestTable.setPlaceholder(new Label("Chưa có request nào."));
        requestTable.getColumns().add(column("Time", FontAwesomeSolid.CLOCK,
                data -> data.getValue().timeText(), 84));
        requestTable.getColumns().add(column("IP", FontAwesomeSolid.GLOBE,
                data -> ipv4(data.getValue().clientIp()), 140));
        requestTable.getColumns().add(column("Port", FontAwesomeSolid.PLUG,
                data -> Integer.toString(data.getValue().clientPort()), 72));
        requestTable.getColumns().add(column("Method", FontAwesomeSolid.CODE,
                data -> data.getValue().method(), 78));
        requestTable.getColumns().add(column("Path", FontAwesomeSolid.LINK,
                data -> data.getValue().path(), 300));
        requestTable.getColumns().add(column("Status", FontAwesomeSolid.CHART_BAR,
                data -> statusLabel(data.getValue().status()), 90));
        VBox.setVgrow(requestTable, Priority.ALWAYS);
        VBox requestCard = card("Danh sách Request gần đây", FontAwesomeSolid.LIST, requestTable);
        VBox.setVgrow(requestCard, Priority.ALWAYS);

        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.getStyleClass().add("log-area");
        VBox logCard = card("Nhật ký hoạt động (Live Log)", FontAwesomeSolid.TERMINAL, logArea);
        VBox.setVgrow(logCard, Priority.ALWAYS);

        SplitPane split = new SplitPane(requestCard, logCard);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.58);
        VBox.setVgrow(split, Priority.ALWAYS);

        tab.getChildren().addAll(metrics, controlCard, split);
        return tab;
    }

    private void startServer() {
        if (server.isRunning()) {
            return;
        }
        int requestedPort;
        try {
            requestedPort = Integer.parseInt(portField.getText().trim());
            if (requestedPort < 1 || requestedPort > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            showError("Cổng không hợp lệ", "Hãy nhập số từ 1 đến 65535.");
            return;
        }

        startButton.setDisable(true);
        stopButton.setDisable(true);
        setPill(serverPill, "ĐANG KHỞI ĐỘNG", "pill-warn");
        setPill(dbPill, "DB: ĐANG KIỂM TRA", "pill-warn");
        setDbCard("Đang kiểm tra", "stat-neutral");

        async(() -> {
            String info;
            try {
                database.initialize();
                info = database.describe();
            } catch (DatabaseManager.DatabaseException e) {
                info = "MySQL: " + e.getMessage();
            }
            server.start(requestedPort);
            return info;
        }, info -> {
            dbInfoLabel.setText(info);
            if (info.startsWith("MySQL:")) {
                setPill(dbPill, "DB: LỖI", "pill-bad");
                setDbCard("Lỗi", "stat-bad");
            } else {
                setPill(dbPill, "DB: SẴN SÀNG", "pill-ok");
                setDbCard("Sẵn sàng", "stat-good");
            }
            setPill(serverPill, "ĐANG CHẠY", "pill-ok");
            serverUrlLabel.setText("Web client: " + server.baseUrl()
                    + "/  ·  API: /proxy, /history, /__health");
            portField.setDisable(true);
            startButton.setDisable(true);
            stopButton.setDisable(false);
            openWebButton.setDisable(false);
            appendLog("[SERVER] Sẵn sàng tại " + server.baseUrl() + "/");
            refreshDatabaseView(false);
        }, error -> {
            startButton.setDisable(false);
            stopButton.setDisable(true);
            openWebButton.setDisable(!server.isRunning());
            setPill(serverPill, "LỖI KHỞI ĐỘNG", "pill-bad");
            setDbCard("Lỗi", "stat-bad");
            dbInfoLabel.setText(safeMessage(error));
            appendLog("[LỖI] " + safeMessage(error));
            showError("Không bật được server", safeMessage(error));
        });
    }

    private void stopServer() {
        server.stop();
        setPill(serverPill, "ĐANG TẮT", "pill-neutral");
        startButton.setDisable(false);
        stopButton.setDisable(true);
        openWebButton.setDisable(true);
        portField.setDisable(false);
        serverUrlLabel.setText("Server đã tắt. Bấm “Bật server” để mở lại cổng.");
        appendLog("[SERVER] Đã tắt server.");
    }

    private void openWebClient() {
        if (!server.isRunning()) {
            showInfo("Server chưa chạy", "Hãy bấm “Bật server” trước.");
            return;
        }
        String url = server.baseUrl() + "/";
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } else {
                javafx.scene.input.Clipboard clipboard =
                        javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent content =
                        new javafx.scene.input.ClipboardContent();
                content.putString(url);
                clipboard.setContent(content);
                showInfo("Đã copy URL", "Không mở được trình duyệt tự động. URL đã được copy:\n" + url);
            }
        } catch (Exception e) {
            showError("Không mở được trình duyệt", safeMessage(e));
        }
    }

    // ------------------------------------------------------------------
    // Tab 2 — Client Browser
    // ------------------------------------------------------------------

    private VBox buildBrowserTab() {
        VBox tab = new VBox(12);
        tab.getStyleClass().add("tab-content");

        methodBox.getItems().addAll("GET", "POST", "HEAD");
        methodBox.setValue("GET");
        methodBox.getStyleClass().add("field");
        methodBox.setPrefWidth(96);

        urlField.getStyleClass().add("url-input");
        urlField.setPromptText("https://example.com hoặc http://localhost:8080/gioi-thieu");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        urlField.setOnAction(event -> navigateTo(urlField.getText(), true));

        HBox urlBox = new HBox(8, icon(FontAwesomeSolid.SEARCH, 15, MUTED), urlField);
        urlBox.getStyleClass().add("url-box");
        urlBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(urlBox, Priority.ALWAYS);
        HBox.setHgrow(urlField, Priority.ALWAYS);

        goButton.setText("SEND");
        goButton.setGraphic(icon(FontAwesomeSolid.PAPER_PLANE, 13, WHITE));
        goButton.getStyleClass().add("button-primary");
        goButton.setOnAction(event -> navigateTo(urlField.getText(), true));

        backButton.setText("");
        backButton.setGraphic(icon(FontAwesomeSolid.ARROW_LEFT, 13, DEEP_BLUE));
        backButton.getStyleClass().addAll("button-ghost", "icon-button");
        backButton.setTooltip(new Tooltip("Quay lại"));
        forwardButton.setText("");
        forwardButton.setGraphic(icon(FontAwesomeSolid.ARROW_RIGHT, 13, DEEP_BLUE));
        forwardButton.getStyleClass().addAll("button-ghost", "icon-button");
        forwardButton.setTooltip(new Tooltip("Tiến tới"));
        reloadButton.setText("");
        reloadButton.setGraphic(icon(FontAwesomeSolid.SYNC, 13, DEEP_BLUE));
        reloadButton.getStyleClass().addAll("button-ghost", "icon-button");
        reloadButton.setTooltip(new Tooltip("Tải lại"));
        backButton.setOnAction(event -> goBack());
        forwardButton.setOnAction(event -> goForward());
        reloadButton.setOnAction(event -> executeLoad(currentLoadedUrl.isBlank()
                ? urlField.getText() : currentLoadedUrl));
        backButton.setDisable(true);
        forwardButton.setDisable(true);

        HBox address = new HBox(8, methodBox, urlBox, goButton,
                backButton, forwardButton, reloadButton);
        address.setAlignment(Pos.CENTER_LEFT);

        postBodyField.getStyleClass().add("field");
        postBodyField.setPromptText("Body cho POST, ví dụ: ho_ten=Nguyễn Văn A&ma_sv=24IT277");
        postBodyField.setDisable(true);
        HBox.setHgrow(postBodyField, Priority.ALWAYS);
        methodBox.valueProperty().addListener((obs, old, value) ->
                postBodyField.setDisable(!"POST".equals(value)));
        Label postLabel = new Label("POST body");
        postLabel.setGraphic(icon(FontAwesomeSolid.CODE, 12, MUTED));
        HBox postRow = new HBox(8, postLabel, postBodyField);
        postRow.setAlignment(Pos.CENTER_LEFT);

        HBox badge = infoBadge();
        analysisLabel.getStyleClass().add("analysis");

        VBox toolbar = new VBox(9, address, postRow, badge, analysisLabel);
        toolbar.getStyleClass().add("card");

        webView.setContextMenuEnabled(false);
        webEngine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
            if (suppressLocation || newLocation == null || newLocation.isBlank()) {
                return;
            }
            if (newLocation.startsWith("about:") || newLocation.startsWith("data:")
                    || newLocation.startsWith("javascript:") || newLocation.startsWith("file:")) {
                return;
            }
            if (!newLocation.startsWith("http://") && !newLocation.startsWith("https://")) {
                return;
            }
            if (newLocation.equals(currentLoadedUrl)) {
                return;
            }
            Platform.runLater(() -> navigateTo(newLocation, true));
        });
        plainArea.setEditable(false);
        plainArea.setWrapText(true);
        plainArea.getStyleClass().add("plain-view");
        plainArea.setVisible(false);
        plainArea.setManaged(false);
        StackPane webStack = new StackPane(webView, plainArea);
        webStack.getStyleClass().add("web-frame");
        VBox.setVgrow(webStack, Priority.ALWAYS);
        VBox webCard = new VBox(webStack);
        webCard.getStyleClass().add("web-card");
        VBox.setVgrow(webCard, Priority.ALWAYS);

        TableView<BrowserHit> browserTable = new TableView<>(browserRows);
        browserTable.getStyleClass().add("data-table");
        browserTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        browserTable.setPlaceholder(new Label("Chưa có lượt duyệt nào trong phiên này."));
        browserTable.getColumns().add(column("Time", FontAwesomeSolid.CLOCK,
                data -> data.getValue().time(), 92));
        browserTable.getColumns().add(column("URL", FontAwesomeSolid.LINK,
                data -> data.getValue().url(), 420));
        browserTable.getColumns().add(column("IP", FontAwesomeSolid.GLOBE,
                data -> ipv4(data.getValue().ip()), 150));
        browserTable.getColumns().add(column("Port", FontAwesomeSolid.PLUG,
                data -> Integer.toString(data.getValue().port()), 74));
        browserTable.getColumns().add(column("Status", FontAwesomeSolid.CHART_BAR,
                data -> statusLabel(data.getValue().status()), 96));
        VBox tableCard = card("Lịch sử phiên duyệt · Time | IP | Port | Status",
                FontAwesomeSolid.HISTORY, browserTable);
        tableCard.setPrefHeight(230);

        SplitPane split = new SplitPane(webCard, tableCard);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.70);
        VBox.setVgrow(split, Priority.ALWAYS);

        tab.getChildren().addAll(toolbar, split);
        return tab;
    }

    private void navigateTo(String rawUrl, boolean pushHistory) {
        String url = normalizeTarget(rawUrl);
        if (url.isBlank()) {
            return;
        }
        if (pushHistory) {
            while (backStack.size() > backIndex + 1) {
                backStack.remove(backStack.size() - 1);
            }
            if (backIndex < 0 || !url.equals(backStack.get(backIndex))) {
                backStack.add(url);
                backIndex = backStack.size() - 1;
            }
        }
        updateNavigationButtons();
        executeLoad(url);
    }

    private void goBack() {
        if (backIndex > 0) {
            backIndex--;
            updateNavigationButtons();
            executeLoad(backStack.get(backIndex));
        }
    }

    private void goForward() {
        if (backIndex >= 0 && backIndex < backStack.size() - 1) {
            backIndex++;
            updateNavigationButtons();
            executeLoad(backStack.get(backIndex));
        }
    }

    private void executeLoad(String rawUrl) {
        String url = normalizeTarget(rawUrl);
        if (url.isBlank()) {
            return;
        }
        String method = methodBox.getValue() == null ? "GET" : methodBox.getValue();
        String body = "POST".equals(method) ? postBodyField.getText() : null;
        int token = ++loadToken;

        urlField.setText(url);
        goButton.setDisable(true);
        setStatusValue(0);
        statusValue.setText("…");
        analysisLabel.setText("Đang tải " + url + " …");

        async(() -> {
            NetworkClient.Response response = NetworkClient.send(url, method,
                    Map.of(), body, null);
            String title = NetworkClient.titleOf(response.body());
            String prepared = response.isHtml()
                    ? NetworkClient.prepareHtml(response.body(), response.finalUrl(), true)
                    : "";
            NetworkClient.Analysis analysis = NetworkClient.analyze(
                    response.isHtml() ? response.body() : "", response.bodyByteLength());
            boolean saved = false;
            try {
                database.recordHistory(url, response.finalUrl(), title,
                        response.statusCode(), response.contentType(),
                        response.bodyByteLength(), response.elapsedMillis(),
                        response.targetIp(), response.targetPort());
                saved = true;
            } catch (DatabaseManager.DatabaseException ignored) {
                // Browser vẫn hoạt động khi MySQL tắt.
            }
            return new BrowserResult(response, prepared, title, analysis, saved);
        }, result -> {
            if (token != loadToken) {
                return;
            }
            goButton.setDisable(false);
            showBrowserResult(result);
        }, error -> {
            if (token != loadToken) {
                return;
            }
            goButton.setDisable(false);
            showBrowserError(url, error);
        });
    }

    private void showBrowserResult(BrowserResult result) {
        NetworkClient.Response response = result.response();
        currentLoadedUrl = response.finalUrl();

        timeValue.setText(response.timeText());
        ipValue.setText(ipv4(response.targetIp()).isBlank() ? "-" : ipv4(response.targetIp()));

        if (result.historySaved()) {
            refreshDatabaseView(false);
        }
        portValue.setText(Integer.toString(response.targetPort()));
        statusValue.setText(statusLabel(response.statusCode()));
        setStatusValue(response.statusCode());
        urlField.setText(response.finalUrl());

        NetworkClient.Analysis analysis = result.analysis();
        analysisLabel.setText("p " + analysis.paragraphs()
                + " · div " + analysis.divs()
                + " · span " + analysis.spans()
                + " · img " + analysis.images()
                + " · a " + analysis.links()
                + " · " + formatBytes(response.bodyByteLength())
                + " · " + response.elapsedMillis() + " ms");

        if (response.isHtml() && !result.preparedHtml().isBlank()) {
            showWeb(result.preparedHtml());
        } else {
            showPlain(response.isHtml() || result.title().isBlank()
                    ? "HTTP " + response.statusCode() + " " + response.reason()
                            + "\n\n" + response.body()
                    : result.title() + "\n\n" + response.body());
        }

        if (backIndex >= 0 && backIndex < backStack.size()) {
            backStack.set(backIndex, response.finalUrl());
        }
        updateNavigationButtons();
        browserRows.add(0, new BrowserHit(response.timeText(), response.targetIp(),
                response.targetPort(), response.statusCode(), response.finalUrl(),
                response.elapsedMillis()));
        while (browserRows.size() > 200) {
            browserRows.remove(browserRows.size() - 1);
        }
        appendLog("[BROWSER] " + response.timeText() + "  " + response.targetIp()
                + ":" + response.targetPort() + "  HTTP " + response.statusCode()
                + "  " + response.finalUrl() + "  (" + response.elapsedMillis() + " ms)");
    }

    private void showBrowserError(String url, Throwable error) {
        timeValue.setText(LocalTime.now().format(TIME_FORMAT));
        ipValue.setText("—");
        portValue.setText("—");
        statusValue.setText("ERR");
        setStatusValue(0);
        analysisLabel.setText("Không tải được URL.");
        showWeb(errorPage("Không tải được URL", sanitize(url) + "\n\n" + safeMessage(error)));
        browserRows.add(0, new BrowserHit(LocalTime.now().format(TIME_FORMAT), "—", 0, 0,
                url, 0));
        while (browserRows.size() > 200) {
            browserRows.remove(browserRows.size() - 1);
        }
        appendLog("[LỖI] " + url + " · " + safeMessage(error));
    }

    private void showWeb(String html) {
        plainArea.setVisible(false);
        plainArea.setManaged(false);
        webView.setVisible(true);
        webView.setManaged(true);
        suppressLocation = true;
        webEngine.loadContent(html, "text/html");
        Platform.runLater(() -> suppressLocation = false);
    }

    private void showPlain(String text) {
        plainArea.setText(text == null ? "" : text);
        plainArea.setVisible(true);
        plainArea.setManaged(true);
        webView.setVisible(false);
        webView.setManaged(false);
    }

    private void showWelcome() {
        String html = """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>
                  :root{color-scheme:light}
                  body{margin:0;background:#f8fafc;color:#0f172a;
                       font-family:'Segoe UI',Arial,sans-serif}
                  .wrap{max-width:760px;margin:48px auto;padding:0 24px}
                  .card{background:#fff;border:1px solid #e2e8f0;border-radius:18px;
                        padding:34px;box-shadow:0 18px 50px rgba(15,23,42,.08)}
                  .badge{display:inline-block;background:#dbeafe;color:#1d4ed8;
                         padding:6px 12px;border-radius:999px;font-size:12px;font-weight:800}
                  h1{font-size:28px;margin:16px 0 10px}p{color:#475569;line-height:1.7}
                  code{background:#f1f5f9;padding:2px 6px;border-radius:6px}
                  .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:22px}
                  .item{border:1px solid #e2e8f0;border-radius:12px;padding:14px 16px}
                  .item b{display:block;margin-bottom:5px;color:#0f172a}
                  .item span{color:#64748b;font-size:13px}
                </style></head><body><div class="wrap"><div class="card">
                <span class="badge">RAW SOCKET · JAVA 21 · JAVAFX</span>
                <h1>Client Browser</h1>
                <p>Nhập URL ở thanh phía trên rồi bấm <b>SEND</b>. Request được gửi bằng
                <code>Socket</code> thuần, phản hồi được parse thủ công và HTML được
                rewrite link thành <code>javascript:loadUrl(...)</code>.</p>
                <div class="grid">
                  <div class="item"><b>GET / POST / HEAD</b><span>Đọc status line, header, chunked, gzip.</span></div>
                  <div class="item"><b>IP &amp; Port thật</b><span>Phân giải bằng InetAddress, hiển thị ngay dưới URL.</span></div>
                  <div class="item"><b>MySQL 3307</b><span>Lịch sử được lưu vào web_history.</span></div>
                  <div class="item"><b>Web client</b><span>Bật server rồi mở http://localhost:8080/.</span></div>
                </div>
                <p style="margin-top:22px">Gợi ý: <code>http://localhost:8080/gioi-thieu</code>
                hoặc <code>https://example.com</code>.</p>
                </div></div></body></html>
                """;
        showWeb(html);
    }

    // ------------------------------------------------------------------
    // Tab 3 — Database
    // ------------------------------------------------------------------

    private VBox buildDatabaseTab() {
        VBox tab = new VBox(14);
        tab.getStyleClass().add("tab-content");

        Button refreshButton = new Button("Làm mới");
        refreshButton.setGraphic(icon(FontAwesomeSolid.SYNC, 13, WHITE));
        refreshButton.getStyleClass().add("button-primary");
        refreshButton.setOnAction(event -> refreshDatabaseView(true));

        Button exportPages = new Button("Export web_data.json");
        exportPages.setGraphic(icon(FontAwesomeSolid.FILE_EXPORT, 13, DEEP_BLUE));
        exportPages.getStyleClass().add("button-ghost");
        exportPages.setOnAction(event -> exportJson(false));

        Button exportHistory = new Button("Export web_history.json");
        exportHistory.setGraphic(icon(FontAwesomeSolid.FILE_DOWNLOAD, 13, DEEP_BLUE));
        exportHistory.getStyleClass().add("button-ghost");
        exportHistory.setOnAction(event -> exportJson(true));

        pagesCountLabel.getStyleClass().add("muted");
        dbNoticeLabel.getStyleClass().add("muted");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(9, refreshButton, exportPages, exportHistory, spacer,
                pagesCountLabel, dbNoticeLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("toolbar");
        VBox toolbarCard = new VBox(toolbar);
        toolbarCard.getStyleClass().add("card");

        historyTable.setItems(historyRows);
        historyTable.getStyleClass().add("data-table");
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setPlaceholder(new Label("Chưa có lịch sử trong web_history."));
        historyTable.getColumns().add(column("Time", FontAwesomeSolid.CLOCK,
                data -> data.getValue().visitedAt() == null ? ""
                        : data.getValue().visitedAt().format(TIME_FORMAT), 88));
        historyTable.getColumns().add(column("URL", FontAwesomeSolid.LINK,
                data -> data.getValue().finalUrl(), 380));
        historyTable.getColumns().add(column("IP", FontAwesomeSolid.GLOBE,
                data -> ipv4(nullToEmpty(data.getValue().targetIp())), 140));
        historyTable.getColumns().add(column("Port", FontAwesomeSolid.PLUG,
                data -> Integer.toString(data.getValue().targetPort()), 72));
        historyTable.getColumns().add(column("Status", FontAwesomeSolid.CHART_BAR,
                data -> statusLabel(data.getValue().statusCode()), 96));
        VBox historyCard = card("Lịch sử trong MySQL · Time | URL | IP | Port | Status",
                FontAwesomeSolid.DATABASE, historyTable);
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        pagesJsonArea.setEditable(false);
        pagesJsonArea.setWrapText(false);
        pagesJsonArea.getStyleClass().add("json-area");
        historyJsonArea.setEditable(false);
        historyJsonArea.setWrapText(false);
        historyJsonArea.getStyleClass().add("json-area");

        Tab pagesTab = new Tab("web_data.json", pagesJsonArea);
        pagesTab.setGraphic(icon(FontAwesomeSolid.FILE_CODE, 12, BLUE));
        Tab historyTab = new Tab("web_history.json", historyJsonArea);
        historyTab.setGraphic(icon(FontAwesomeSolid.HISTORY, 12, BLUE));
        TabPane previews = new TabPane(pagesTab, historyTab);
        previews.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        previews.getStyleClass().add("inner-tabs");
        VBox previewCard = card("File JSON tự sinh", FontAwesomeSolid.FILE_CODE, previews);
        VBox.setVgrow(previewCard, Priority.ALWAYS);

        SplitPane split = new SplitPane(historyCard, previewCard);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.52);
        VBox.setVgrow(split, Priority.ALWAYS);

        tab.getChildren().addAll(toolbarCard, split);
        return tab;
    }

    private void initializeDatabase() {
        async(() -> {
            database.initialize();
            return database.describe();
        }, description -> {
            dbInfoLabel.setText(description);
            setPill(dbPill, "DB: SẴN SÀNG", "pill-ok");
            setDbCard("Sẵn sàng", "stat-good");
            refreshDatabaseView(false);
        }, error -> {
            dbInfoLabel.setText(safeMessage(error));
            setPill(dbPill, "DB: LỖI", "pill-bad");
            setDbCard("Lỗi", "stat-bad");
            dbNoticeLabel.setText("Không kết nối MySQL tại " + database.endpoint());
            appendLog("[DB] " + safeMessage(error));
        });
    }

    private void refreshDatabaseView(boolean notify) {
        async(() -> {
            DatabaseManager.DataSnapshot snapshot = database.refreshData();
            String historyJson = database.exportJson();
            return new DbSnapshot(snapshot.history(), snapshot.pages().size(),
                    snapshot.pagesJson(), historyJson);
        }, snapshot -> {
            historyRows.setAll(snapshot.history());
            pagesCountLabel.setText(snapshot.pageCount() + " trang");
            pagesJsonArea.setText(snapshot.pagesJson());
            historyJsonArea.setText(snapshot.historyJson());
            dbNoticeLabel.setText(snapshot.history().size() + " lịch sử · "
                    + snapshot.pageCount() + " trang");
            if (notify) {
                appendLog("[DB] Đã làm mới dữ liệu MySQL.");
            }
        }, error -> {
            dbNoticeLabel.setText("Lỗi DB: " + safeMessage(error));
            if (notify) {
                showError("Không đọc được database", safeMessage(error));
            }
        });
    }

    private void exportJson(boolean history) {
        async(() -> history ? database.exportHistory() : database.exportPages(),
                path -> {
                    dbNoticeLabel.setText("Đã ghi " + path);
                    appendLog("[DB] Export → " + path);
                    refreshDatabaseView(false);
                },
                error -> showError("Export JSON thất bại", safeMessage(error)));
    }

    // ------------------------------------------------------------------
    // Timer & sự kiện server
    // ------------------------------------------------------------------

    private void startClock() {
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            clockLabel.setText(LocalTime.now().format(TIME_FORMAT));
            requestsValue.setText(Long.toString(server.requestCount()));
            activeValue.setText(Integer.toString(server.activeConnections()));
            String uptime = server.isRunning() ? server.uptimeText() : "00:00:00";
            uptimeValue.setText(uptime);
            headerUptimeLabel.setText("Uptime " + uptime);
        }));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void addServerRequest(NetworkServer.RequestInfo info) {
        serverRows.add(0, info);
        while (serverRows.size() > 300) {
            serverRows.remove(serverRows.size() - 1);
        }
        requestsValue.setText(Long.toString(server.requestCount()));
        activeValue.setText(Integer.toString(server.activeConnections()));
        if ("/proxy".equals(info.path())) {
            refreshDatabaseView(false);
        }
    }

    private void appendLog(String message) {
        Runnable task = () -> {
            logArea.appendText(message + System.lineSeparator());
            if (logArea.getLength() > 220_000) {
                logArea.deleteText(0, 60_000);
            }
        };
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private void shutdown() {
        server.stop();
        workers.shutdownNow();
    }

    // ------------------------------------------------------------------
    // Tiện ích UI
    // ------------------------------------------------------------------

    private static FontIcon icon(Ikon code, int size, Color color) {
        FontIcon icon = new FontIcon(code);
        icon.setIconSize(size);
        icon.setIconColor(color);
        icon.setMouseTransparent(true);
        icon.getStyleClass().add("fa-icon");
        return icon;
    }

    private HBox metricCard(String title, Label value, String styleClass,
            FontIcon icon, String iconStyle) {
        value.getStyleClass().add("metric-value");
        Label caption = new Label(title);
        caption.getStyleClass().add("metric-caption");
        VBox text = new VBox(3, value, caption);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);

        StackPane iconBox = new StackPane(icon);
        iconBox.getStyleClass().addAll("metric-icon", iconStyle);

        HBox box = new HBox(14, iconBox, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("metric-card", styleClass);
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        HBox wrapper = new HBox(box);
        wrapper.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(wrapper, Priority.ALWAYS);
        return wrapper;
    }

    private HBox infoBadge() {
        HBox badge = new HBox();
        badge.getStyleClass().add("info-badge");
        badge.getChildren().addAll(
                badgeBlock("TIME", icon(FontAwesomeSolid.CLOCK, 14, DEEP_BLUE), timeValue),
                badgeSeparator(),
                badgeBlock("IP", icon(FontAwesomeSolid.GLOBE, 14, DEEP_BLUE), ipValue),
                badgeSeparator(),
                badgeBlock("PORT", icon(FontAwesomeSolid.PLUG, 14, DEEP_BLUE), portValue),
                badgeSeparator(),
                badgeBlock("STATUS", statusBadgeIcon, statusValue));
        return badge;
    }

    private VBox badgeBlock(String caption, FontIcon icon, Label value) {
        value.getStyleClass().add("badge-value");
        Label label = new Label(caption);
        label.setGraphic(icon);
        label.getStyleClass().add("badge-label");
        VBox box = new VBox(3, label, value);
        box.getStyleClass().add("badge-block");
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private static Region badgeSeparator() {
        Region region = new Region();
        region.getStyleClass().add("badge-sep");
        return region;
    }

    private void setDbCard(String text, String styleClass) {
        dbStatusValue.setText(text);
        dbStatusValue.getStyleClass().removeAll("stat-good", "stat-bad", "stat-neutral");
        dbStatusValue.getStyleClass().add(styleClass);

        if ("stat-good".equals(styleClass)) {
            dbCardIcon.setIconCode(FontAwesomeSolid.CHECK_CIRCLE);
            dbCardIcon.setIconColor(GREEN);
            dbPillIcon.setIconColor(GREEN);
        } else if ("stat-bad".equals(styleClass)) {
            dbCardIcon.setIconCode(FontAwesomeSolid.EXCLAMATION_TRIANGLE);
            dbCardIcon.setIconColor(RED);
            dbPillIcon.setIconColor(RED);
        } else {
            dbCardIcon.setIconCode(FontAwesomeSolid.DATABASE);
            dbCardIcon.setIconColor(VIOLET);
            dbPillIcon.setIconColor(AMBER);
        }
    }

    private static String statusLabel(int status) {
        if (status == 0) {
            return "ERR";
        }
        return switch (status) {
            case 200 -> "200 OK";
            case 201 -> "201 Created";
            case 204 -> "204 No Content";
            case 301 -> "301 Moved";
            case 302 -> "302 Found";
            case 304 -> "304 Not Modified";
            case 400 -> "400 Bad Request";
            case 403 -> "403 Forbidden";
            case 404 -> "404 Not Found";
            case 405 -> "405 Method Not Allowed";
            case 408 -> "408 Timeout";
            case 500 -> "500 Server Error";
            case 502 -> "502 Bad Gateway";
            case 503 -> "503 Unavailable";
            default -> Integer.toString(status);
        };
    }

    private static VBox card(String title, Ikon iconCode, Node content) {
        Label caption = new Label(title);
        caption.setGraphic(icon(iconCode, 13, BLUE));
        caption.getStyleClass().add("card-title");
        VBox box = new VBox(10, caption, content);
        box.getStyleClass().add("card");
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static <T> TableColumn<T, String> column(String title, Ikon iconCode,
            java.util.function.Function<TableColumn.CellDataFeatures<T, String>, String> value,
            double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setGraphic(icon(iconCode, 11, MUTED));
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.apply(data)));
        column.setPrefWidth(width);
        column.setSortable(false);
        return column;
    }

    private void setPill(Label pill, String text, String styleClass) {
        pill.setText(text);
        pill.getStyleClass().removeAll("pill-ok", "pill-bad", "pill-warn", "pill-neutral");
        pill.getStyleClass().add(styleClass);
    }

    private void setStatusValue(int status) {
        statusValue.getStyleClass().removeAll("stat-good", "stat-bad", "stat-neutral");
        if (status >= 200 && status < 400) {
            statusValue.getStyleClass().add("stat-good");
            statusBadgeIcon.setIconCode(FontAwesomeSolid.CHECK_CIRCLE);
            statusBadgeIcon.setIconColor(GREEN);
        } else if (status == 0) {
            statusValue.getStyleClass().add("stat-neutral");
            statusBadgeIcon.setIconCode(FontAwesomeSolid.CHECK_CIRCLE);
            statusBadgeIcon.setIconColor(MUTED);
        } else {
            statusValue.getStyleClass().add("stat-bad");
            statusBadgeIcon.setIconCode(FontAwesomeSolid.EXCLAMATION_TRIANGLE);
            statusBadgeIcon.setIconColor(RED);
        }
    }

    private void updateNavigationButtons() {
        backButton.setDisable(backIndex <= 0);
        forwardButton.setDisable(backIndex < 0 || backIndex >= backStack.size() - 1);
    }

    private String normalizeTarget(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("/")) {
            int port = server.isRunning() ? server.port() : NetworkServer.DEFAULT_PORT;
            return "http://localhost:" + port + value;
        }
        return NetworkClient.normalizeUrl(value);
    }

    private <T> void async(Callable<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        workers.submit(() -> {
            try {
                T value = task.call();
                Platform.runLater(() -> success.accept(value));
            } catch (Throwable error) {
                Platform.runLater(() -> failure.accept(error));
            }
        });
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        if (stage != null) {
            alert.initOwner(stage);
        }
        if (cssUrl != null) {
            alert.getDialogPane().getStylesheets().add(cssUrl);
        }
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        if (stage != null) {
            alert.initOwner(stage);
        }
        if (cssUrl != null) {
            alert.getDialogPane().getStylesheets().add(cssUrl);
        }
        alert.showAndWait();
    }

    private static String errorPage(String title, String message) {
        return """
                <!DOCTYPE html><html><head><meta charset="UTF-8">
                <style>
                  :root{color-scheme:light}
                  body{margin:0;background:#fef2f2;color:#7f1d1d;
                       font-family:'Segoe UI',Arial,sans-serif}
                  .wrap{max-width:720px;margin:48px auto;padding:0 24px}
                  .card{background:#fff;border:1px solid #fecaca;border-radius:16px;
                        padding:30px;box-shadow:0 16px 40px rgba(127,29,29,.08)}
                  h1{margin:0 0 12px;font-size:24px}
                  pre{white-space:pre-wrap;word-break:break-word;background:#fff7f7;
                      border:1px solid #fee2e2;border-radius:10px;padding:16px;
                      color:#991b1b;font-family:Consolas,monospace;font-size:13px}
                  code{background:#fee2e2;padding:2px 6px;border-radius:6px}
                </style></head><body><div class="wrap"><div class="card">
                <h1>%s</h1><pre>%s</pre>
                <p style="color:#7f1d1d">Kiểm tra URL, kết nối mạng hoặc bật server tại tab
                <code>Server</code>.</p>
                </div></div></body></html>
                """.formatted(sanitize(title), sanitize(message));
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "không rõ lỗi";
        }
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String ipv4(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String ip = value.trim();
        if (ip.startsWith("/")) {
            ip = ip.substring(1);
        }
        if (ip.contains(".") && !ip.contains(":")) {
            return ip;
        }
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(ip)) {
                if (address instanceof Inet4Address) {
                    return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return ip;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

