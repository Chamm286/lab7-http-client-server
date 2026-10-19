package ui;

import core.HttpClientCore;
import core.HttpServerCore;
import core.UrlInspector;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Alert;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.web.WebView;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.BindException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

public class MainView {
    private enum LaunchMode {
        COMBINED, SERVER, CLIENT
    }

    private static final String APP_STYLE =
            "-fx-font-family: 'Segoe UI'; -fx-font-size: 14px; "
                    + "-fx-background-color: #eef2f7; -fx-text-fill: #172033;";
    private static final String CARD_STYLE =
            "-fx-background-color: #ffffff; -fx-background-radius: 14; "
                    + "-fx-border-color: #e2e8f0; -fx-border-radius: 14; "
                    + "-fx-padding: 16; "
                    + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.055), 14, 0, 0, 4);";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final HttpServerCore server = new HttpServerCore();
    private final ObservableList<HttpServerCore.RequestInfo> requests =
            FXCollections.observableArrayList();
    private final ObservableList<ClientActivity> clientActivities =
            FXCollections.observableArrayList();
    private final ObservableList<EndpointInfo> endpoints = FXCollections.observableArrayList();
    private final ObservableList<Long> requestTimes = FXCollections.observableArrayList();

    private Stage stage;
    private TextArea serverLog;
    private TextField portField;
    private TextField rootField;
    private TextField urlField;
    private PasswordField writeTokenField;
    private TextField visibleWriteTokenField;
    private TextField serverTokenDisplay;
    private String selectedHttpMethod = "GET";
    private ComboBox<String> transferMethodBox;
    private ComboBox<String> roleBox;
    private Label roleDescription;
    private String activeRole = "Server + Client (cùng máy)";
    private LaunchMode launchMode = LaunchMode.COMBINED;
    private TabPane mainTabs;
    private VBox serverSettingsCard;
    private VBox clientGuideCard;
    private Button startButton;
    private Button stopButton;
    private Button sendButton;
    private Button inspectUrlButton;
    private Button chooseUploadButton;
    private Button uploadButton;
    private Button saveResponseButton;
    private Label uploadSummaryLabel;
    private VBox attachmentList;
    private ProgressBar uploadProgressBar;
    private final List<File> selectedUploadFiles = new java.util.ArrayList<>();
    private HttpClientCore.Response latestResponse;
    private Label serverState;
    private Label addressBadge;
    private Label connectAddressLabel;
    private Label connectionHelpLabel;
    private Label dashboardServerStatus;
    private Label dashboardServerAddress;
    private Label dashboardHealthStatus;
    private Label serverUptimeLabel;
    private Label requestCountLabel;
    private Label rateLabel;
    private Label transferLabel;
    private Label latencyLabel;
    private Label successLabel;
    private Label statusBadge;
    private Label responseMeta;
    private TextArea headerArea;
    private TextArea bodyArea;
    private TextArea requestBodyArea;
    private Label htmlAnalysisLabel;
    private Label urlInspectorStatus;
    private Label urlProtocolValue;
    private Label urlHostValue;
    private Label urlPortValue;
    private Label urlDefaultPortValue;
    private Label urlEffectivePortValue;
    private Label urlFileValue;
    private Label urlIpValue;
    private WebView webView;
    private String lastClientLoadedUrl;
    private boolean interceptingBrowserNavigation;
    private TableView<HttpServerCore.RequestInfo> requestTable;
    private TableView<ClientActivity> clientActivityTable;
    private TableView<EndpointInfo> endpointTable;
    private FilteredList<HttpServerCore.RequestInfo> filteredRequests;
    private TextField requestSearchField;
    private Label requestFilterSummary;
    private Timeline serverClock;
    private long serverStartedAtMillis;
    private long totalBytes;
    private long totalLatency;
    private long totalRequests;
    private long successfulRequests;

    public void show(Stage stage) {
        show(stage, LaunchMode.COMBINED);
    }

    public void showServer(Stage stage) {
        show(stage, LaunchMode.SERVER);
    }

    public void showClient(Stage stage) {
        show(stage, LaunchMode.CLIENT);
    }

    private void show(Stage stage, LaunchMode mode) {
        this.stage = stage;
        this.launchMode = mode;
        BorderPane root = new BorderPane();
        root.setTop(createHeader());
        if (mode != LaunchMode.CLIENT) {
            root.setLeft(createSidebar());
        }
        root.setCenter(createWorkspace());
        root.setStyle(APP_STYLE);

        Scene scene = new Scene(root, mode == LaunchMode.CLIENT ? 1360 : 1480, 880);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.L && urlField != null) {
                selectTab(clientTabIndex());
                urlField.requestFocus();
                urlField.selectAll();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.R
                    && mainTabs.getSelectionModel().getSelectedIndex() == clientTabIndex()) {
                sendRequest();
                event.consume();
            }
        });
        stage.setTitle(mode == LaunchMode.SERVER ? "Lab 7 | HTTP Server"
                : mode == LaunchMode.CLIENT ? "Lab 7 | HTTP Client"
                        : "Lab 7 | HTTP Server & Client");
        stage.setMinWidth(mode == LaunchMode.CLIENT ? 1100 : 1040);
        stage.setMinHeight(680);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> server.stop());
        if (mode == LaunchMode.CLIENT) {
            serverState.setText("●  CLIENT");
            serverState.setStyle(stateStyle(true));
            addressBadge.setText("HTTP REQUEST WORKBENCH");
        }
        stage.show();
        Platform.runLater(this::applyProfessionalTheme);
        if (mode != LaunchMode.CLIENT) {
            refreshEndpoints();
            updateRoleControls();
        }
    }

    private int clientTabIndex() {
        return launchMode == LaunchMode.CLIENT ? 0
                : launchMode == LaunchMode.SERVER ? -1 : 4;
    }

    private String serverUrl(String path) {
        String address = HttpServerCore.getLanAddress();
        String host = address == null ? "localhost" : address;
        return "http://" + host + ":" + server.getPort() + path;
    }

    private Node createHeader() {
        StackPane mark = new StackPane(svgIcon(
                "M3 4h18v3H3z M3 10h8v10H3z M13 10h8v4h-8z M13 16h8v4h-8z",
                19, "#ffffff"));
        mark.setMinSize(44, 44);
        mark.setMaxSize(44, 44);
        mark.setStyle("-fx-background-color: linear-gradient(to bottom right, #0ea5e9, #1d4ed8); "
                + "-fx-background-radius: 14; "
                + "-fx-effect: dropshadow(gaussian, rgba(37,99,235,0.28), 12, 0, 0, 3);");

        Label title = new Label(launchMode == LaunchMode.SERVER ? "HTTP SERVER"
                : launchMode == LaunchMode.CLIENT ? "HTTP CLIENT"
                        : "HTTP SERVER & CLIENT");
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        Label subtitle = new Label(launchMode == LaunchMode.SERVER
                ? "LAB 7  ·  SERVER CONTROL PANEL"
                : launchMode == LaunchMode.CLIENT
                        ? "LAB 7  ·  HTTP REQUEST WORKBENCH"
                        : "LAB 7  ·  HTTP SERVICE CONTROL CENTER");
        subtitle.setStyle("-fx-text-fill: #748198; -fx-font-size: 10px; -fx-font-weight: bold;");
        VBox brand = new VBox(2, title, subtitle);

        addressBadge = new Label("SERVER CHƯA CHẠY");
        addressBadge.setStyle("-fx-background-color: #eaf3f6; -fx-text-fill: #155e75; "
                + "-fx-padding: 8 12; -fx-background-radius: 8; -fx-font-weight: bold; "
                + "-fx-font-family: 'Consolas'; -fx-font-size: 11px;");
        serverState = new Label("●  STOPPED");
        serverState.setStyle(stateStyle(false));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(13, mark, brand, spacer, addressBadge, serverState);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(12, 22, 12, 22));
        header.setStyle("-fx-background-color: white; "
                + "-fx-border-color: transparent transparent #dce4ef transparent; "
                + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.05), 8, 0, 0, 2);");
        return header;
    }

    private Node createSidebar() {
        Label controlTitle = sectionTitle("CHẾ ĐỘ ỨNG DỤNG");
        roleBox = new ComboBox<>(FXCollections.observableArrayList(
                "Server + Client (cùng máy)", "Server (máy chủ)", "Client (máy khách)"));
        roleBox.getSelectionModel().select(launchMode == LaunchMode.SERVER
                ? "Server (máy chủ)" : "Server + Client (cùng máy)");
        roleBox.setMaxWidth(Double.MAX_VALUE);
        roleBox.setOnAction(event -> updateRoleControls());
        roleDescription = new Label("Dùng client tích hợp để kiểm tra server trên cùng máy.");
        roleDescription.setWrapText(true);
        roleDescription.setStyle("-fx-text-fill: #748197; -fx-font-size: 10px;");
        VBox roleCard = new VBox(9, controlTitle, roleBox, roleDescription);
        roleCard.setPadding(new Insets(16));
        roleCard.setStyle(CARD_STYLE);

        Label serverTitle = new Label("CẤU HÌNH MÁY CHỦ");
        serverTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 17));
        serverTitle.setStyle("-fx-text-fill: #1e293b;");
        portField = new TextField("8080");
        portField.setPromptText("Port");
        portField.setTooltip(new javafx.scene.control.Tooltip(
                "Cổng TCP máy chủ HTTP lắng nghe (1–65535)."));
        rootField = new TextField("www");
        rootField.setPromptText("Chọn thư mục chia sẻ");
        rootField.setTooltip(new javafx.scene.control.Tooltip(
                "Thư mục chứa trang web và các tệp được chia sẻ."));
        Button browseButton = secondaryButton("Duyệt");
        browseButton.setTooltip(new javafx.scene.control.Tooltip(
                "Chọn thư mục tài liệu mà HTTP Server sẽ chia sẻ."));
        browseButton.setOnAction(event -> chooseDocumentRoot());

        startButton = primaryButton("Khởi động");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(event -> startServer());
        startButton.setTooltip(new javafx.scene.control.Tooltip(
                "Mở TCP listener và bắt đầu nhận request HTTP."));
        stopButton = dangerButton("Dừng");
        stopButton.setOnAction(event -> stopServer());
        stopButton.setTooltip(new javafx.scene.control.Tooltip(
                "Đóng TCP listener và ngắt các kết nối đang hoạt động."));
        HBox serverButtons = new HBox(8, startButton, stopButton);
        HBox.setHgrow(startButton, Priority.ALWAYS);
        HBox.setHgrow(stopButton, Priority.ALWAYS);

        connectAddressLabel = new Label("Chưa chạy");
        connectAddressLabel.setWrapText(true);
        connectAddressLabel.setStyle("-fx-text-fill: #155e75; -fx-font-size: 12px; "
                + "-fx-font-family: 'Consolas'; -fx-font-weight: bold;");
        connectionHelpLabel = new Label("Client cần cùng mạng LAN/Wi-Fi.");
        connectionHelpLabel.setWrapText(true);
        connectionHelpLabel.setStyle("-fx-text-fill: #748197; -fx-font-size: 10px; "
                + "-fx-line-spacing: 2px;");
        Button copyAddressButton = secondaryButton("Sao chép URL");
        copyAddressButton.setOnAction(event -> copyServerAddress());
        serverTokenDisplay = new TextField();
        serverTokenDisplay.setEditable(false);
        serverTokenDisplay.setPromptText("Sinh tự động khi khởi động");
        serverTokenDisplay.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 10px;");
        Button copyTokenButton = secondaryButton("Sao chép token");
        copyTokenButton.setOnAction(event -> copyWriteToken());
        Label tokenHelp = new Label(
                "Cần cho POST, PUT, DELETE và audit. Chỉ chia sẻ với client tin cậy.");
        tokenHelp.setWrapText(true);
        tokenHelp.setStyle("-fx-text-fill: #748197; -fx-font-size: 10px;");

        HBox portRow = new HBox(portField);
        portField.setPrefColumnCount(7);
        portRow.setAlignment(Pos.CENTER_LEFT);
        HBox rootRow = new HBox(7, rootField, browseButton);
        HBox.setHgrow(rootField, Priority.ALWAYS);
        Label rootHelp = new Label("Tệp nhận từ client được lưu trong /uploads.");
        rootHelp.setStyle("-fx-text-fill: #748197; -fx-font-size: 10px;");
        serverSettingsCard = new VBox(10, serverTitle,
                fieldCaption("↔  CỔNG TCP"), portRow,
                fieldCaption("▰  DOCUMENT ROOT"), rootRow, rootHelp,
                serverButtons,
                new javafx.scene.control.Separator(),
                fieldCaption("⌖  ĐỊA CHỈ MÁY CHỦ (LAN)"),
                connectAddressLabel, connectionHelpLabel, copyAddressButton,
                new javafx.scene.control.Separator(),
                fieldCaption("◆  TOKEN QUYỀN GHI"),
                serverTokenDisplay, tokenHelp, copyTokenButton);
        serverSettingsCard.setPadding(new Insets(16));
        serverSettingsCard.setStyle(CARD_STYLE);

        Label clientGuideTitle = sectionTitle("KẾT NỐI MÁY CHỦ");
        Label clientGuideText = new Label(
                "CHỌN CHỈ CLIENT KHI MÁY NÀY KHÔNG CHẠY SERVER.\n\n"
                + "1. Hai máy cùng Wi-Fi/LAN.\n"
                + "2. Nhập URL/IP của máy Server ở thanh địa chỉ.\n"
                + "3. Dán Bearer token do quản trị máy Server cung cấp để ghi/xem audit.\n"
                + "4. GET tải/xem HTML và đếm byte, p/div/span/img; HEAD chỉ nhận metadata.\n"
                + "5. POST gửi body biểu mẫu hoặc tệp; PUT thay thế/tạo tệp; "
                + "DELETE xóa tệp upload; OPTIONS xem Allow.\n\n"
                + "Ví dụ: http://192.168.1.25:8080/\n"
                + "Nếu không kết nối, kiểm tra IP, cổng và firewall trên máy Server.");
        clientGuideText.setText("Nhập URL LAN của máy chủ trong tab HTTP Client. "
                + "Hai máy cần cùng mạng. Token chỉ cần cho POST, PUT, DELETE và audit.");
        clientGuideText.setWrapText(true);
        clientGuideText.setStyle("-fx-text-fill: #68738a; -fx-font-size: 11px; "
                + "-fx-line-spacing: 2px;");
        clientGuideCard = new VBox(12, clientGuideTitle, clientGuideText);
        clientGuideCard.setPadding(new Insets(16));
        clientGuideCard.setStyle(CARD_STYLE);
        clientGuideCard.setVisible(false);
        clientGuideCard.setManaged(false);

        requestCountLabel = statValue("0");
        rateLabel = statValue("0 req/s");
        transferLabel = statValue("0 B");
        latencyLabel = statValue("0 ms");
        successLabel = statValue("0%");

        VBox sidebar = launchMode == LaunchMode.SERVER
                ? new VBox(12, serverSettingsCard)
                : new VBox(12, roleCard, serverSettingsCard, clientGuideCard);
        sidebar.setPadding(new Insets(15, 15, 15, 18));
        sidebar.setPrefWidth(320);
        sidebar.setMinWidth(290);
        sidebar.setStyle("-fx-background-color: #f5f7fa;");
        javafx.scene.control.ScrollPane sidebarScroll = new javafx.scene.control.ScrollPane(sidebar);
        sidebarScroll.setFitToWidth(true);
        sidebarScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        sidebarScroll.setPrefViewportWidth(320);
        sidebarScroll.setMinWidth(290);
        sidebarScroll.setStyle("-fx-background-color: #f5f7fa; -fx-background: #f5f7fa;");
        return sidebarScroll;
    }

    private Node createWorkspace() {
        mainTabs = new TabPane();
        if (launchMode == LaunchMode.CLIENT) {
            mainTabs.getTabs().add(createIconTab("HTTP Client",
                    "M3 4h18v13H3z M8 21h8v-2H8z M7 8h10v2H7z M7 12h7v2H7z",
                    createHttpTestPage()));
        } else {
            mainTabs.getTabs().addAll(
                    createIconTab("Tổng quan", "M3 3h8v8H3z M13 3h8v5h-8z M13 10h8v11h-8z M3 13h8v8H3z", createDashboard()),
                    createIconTab("Yêu cầu HTTP", "M4 5h15l-3-3 2-2 6 6-6 6-2-2 3-3H4z M20 19H5l3 3-2 2-6-6 6-6 2 2-3 3h15z", createRequestsPage()),
                    createIconTab("Nhật ký", "M4 4h2v2H4z M9 4h12v2H9z M4 11h2v2H4z M9 11h12v2H9z M4 18h2v2H4z M9 18h12v2H9z", createLogPage()),
                    createIconTab("Tệp & tài nguyên", "M3 5a2 2 0 0 1 2-2h6l2 2h6a2 2 0 0 1 2 2v1H8a3 3 0 0 0-2.7 1.7L3 16z M8 10h13l-3 9a2 2 0 0 1-2 1H5a2 2 0 0 1-1.9-2.6l2.8-6.2A2 2 0 0 1 8 10z", createEndpointsPage()));
            if (launchMode == LaunchMode.COMBINED) {
                mainTabs.getTabs().add(createIconTab("HTTP Client",
                        "M3 4h18v13H3z M8 21h8v-2H8z M7 8h10v2H7z M7 12h7v2H7z",
                        createHttpTestPage()));
            }
        }
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.setStyle("-fx-background-color: transparent;");

        VBox workspace = new VBox(mainTabs);
        workspace.setPadding(new Insets(15, 18, 15, 0));
        VBox.setVgrow(mainTabs, Priority.ALWAYS);
        return workspace;
    }

    private Node createDashboard() {
        VBox heading = pageTitle("Tổng quan máy chủ",
                "Theo dõi kết nối TCP, request HTTP và thao tác tệp từ các máy khách.");

        dashboardServerStatus = new Label("●  STOPPED");
        dashboardServerStatus.setStyle(stateStyle(false));
        dashboardServerAddress = new Label("Khởi động server để nhận địa chỉ LAN.");
        dashboardServerAddress.setStyle("-fx-text-fill: #155e75; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 12px; -fx-font-weight: bold;");
        dashboardServerAddress.setWrapText(true);
        Label serverInfo = new Label(
                "Lắng nghe HTTP qua TCP · GET tệp công khai · ghi cần Bearer token");
        serverInfo.setStyle("-fx-text-fill: #748197; -fx-font-size: 11px;");
        serverInfo.setText("HTTP/1.0 · HTTP/1.1 qua TCP · GET công khai · thao tác ghi cần token.");
        serverUptimeLabel = new Label("◷  Thời gian chạy: —");
        serverUptimeLabel.setStyle("-fx-text-fill: #748197; -fx-font-size: 11px;");
        dashboardHealthStatus = new Label("Chưa kiểm tra health endpoint");
        dashboardHealthStatus.setStyle("-fx-text-fill: #748197; -fx-font-size: 11px;");
        Button checkHealth = secondaryButton("Kiểm tra trạng thái");
        checkHealth.setTooltip(new javafx.scene.control.Tooltip(
                "Gửi GET /__health đến server đang chạy."));
        checkHealth.setOnAction(event -> {
            if (!server.isRunning()) {
                dashboardHealthStatus.setText("Server chưa chạy");
                dashboardHealthStatus.setStyle("-fx-text-fill: #bf3f32; -fx-font-size: 11px;");
                showInfo("Server chưa chạy", "Khởi động máy chủ trước khi kiểm tra /__health.");
                return;
            }
            runServerHealthCheck();
        });
        Button copyAddress = secondaryButton("Sao chép URL");
        copyAddress.setTooltip(new javafx.scene.control.Tooltip(
                "Sao chép địa chỉ LAN để gửi cho Client hoặc mở trên thiết bị khác."));
        copyAddress.setOnAction(event -> copyServerAddress());
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        VBox serverIdentity = new VBox(7, dashboardServerStatus,
                dashboardServerAddress, serverInfo, serverUptimeLabel, dashboardHealthStatus);
        HBox statusActions = new HBox(8, checkHealth, copyAddress);
        statusActions.setAlignment(Pos.CENTER_RIGHT);
        HBox statusCard = new HBox(14, serverIdentity, statusSpacer, statusActions);
        statusCard.setAlignment(Pos.CENTER_LEFT);
        statusCard.setPadding(new Insets(15));
        statusCard.setStyle(CARD_STYLE);

        HBox metrics = new HBox(10,
                metricCard("TỔNG REQUEST", requestCountLabel),
                metricCard("REQUEST / GIÂY", rateLabel),
                metricCard("TỶ LỆ THÀNH CÔNG", successLabel),
                metricCard("ĐỘ TRỄ TRUNG BÌNH", latencyLabel),
                metricCard("DỮ LIỆU PHẢN HỒI", transferLabel));
        for (Node metric : metrics.getChildren()) {
            HBox.setHgrow(metric, Priority.ALWAYS);
        }

        Label recentTitle = sectionTitle("REQUEST GẦN ĐÂY");
        Button viewAll = secondaryButton("Xem toàn bộ request");
        viewAll.setOnAction(event -> selectTab(1));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox recentHeader = new HBox(recentTitle, spacer, viewAll);
        recentHeader.setAlignment(Pos.CENTER_LEFT);

        TableView<HttpServerCore.RequestInfo> recent = createRequestTable();
        recent.setItems(requests);
        recent.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(recent, Priority.ALWAYS);

        VBox tableCard = new VBox(10, recentHeader, recent);
        tableCard.setPadding(new Insets(14));
        tableCard.setStyle(CARD_STYLE);
        VBox.setVgrow(recent, Priority.ALWAYS);
        VBox content = new VBox(13, heading, statusCard, metrics, tableCard);
        content.setPadding(new Insets(16));
        VBox.setVgrow(tableCard, Priority.ALWAYS);
        return content;
    }

    private Node createRequestsPage() {
        VBox heading = pageTitle("Yêu cầu HTTP",
                "Theo dõi từng request từ TCP client, status response, độ trễ và dữ liệu trả về.");
        requestTable = createRequestTable();
        filteredRequests = new FilteredList<>(requests, request -> true);
        requestTable.setItems(filteredRequests);
        requestSearchField = new TextField();
        requestSearchField.setPromptText("Lọc theo method, đường dẫn, trạng thái hoặc IP client");
        requestSearchField.setPrefWidth(390);
        requestSearchField.textProperty().addListener((observable, previous, value) ->
                filterRequests(value));
        requestFilterSummary = new Label();
        requestFilterSummary.setStyle("-fx-text-fill: #748197; -fx-font-size: 11px;");
        filterRequests("");
        Button clearHistory = secondaryButton("Xóa lịch sử phiên");
        clearHistory.setTooltip(new javafx.scene.control.Tooltip(
                "Xóa các request đang hiển thị; không xóa audit log đã lưu trên đĩa."));
        clearHistory.setOnAction(event -> {
            requests.clear();
            updateRequestFilterSummary();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, requestSearchField, requestFilterSummary, spacer, clearHistory);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(12, heading, toolbar, requestTable);
        content.setPadding(new Insets(19));
        VBox.setVgrow(requestTable, Priority.ALWAYS);
        return content;
    }

    private Node createLogPage() {
        VBox heading = pageTitle("Nhật ký máy chủ",
                "Luồng TCP accept, HTTP request line, headers an toàn và kết quả xử lý theo thời gian thực.");
        serverLog = new TextArea();
        serverLog.setEditable(false);
        serverLog.setWrapText(false);
        serverLog.setStyle("-fx-control-inner-background: #101827; -fx-text-fill: #c7d2e5; "
                + "-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        VBox.setVgrow(serverLog, Priority.ALWAYS);
        Button exportLog = secondaryButton("Xuất nhật ký…");
        exportLog.setOnAction(event -> exportServerLog());
        Button clearLog = secondaryButton("Xóa log màn hình");
        clearLog.setTooltip(new javafx.scene.control.Tooltip(
                "Xóa nội dung log đang hiển thị; không xóa audit đã lưu trên đĩa."));
        clearLog.setOnAction(event -> serverLog.clear());
        Label logHint = new Label("Log runtime của ứng dụng; xóa ở đây không xóa audit lưu trên đĩa.");
        logHint.setStyle("-fx-text-fill: #748197; -fx-font-size: 11px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(logHint, spacer, exportLog, clearLog);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(serverLog);
        card.setPadding(new Insets(2));
        card.setStyle(CARD_STYLE);
        VBox content = new VBox(12, heading, toolbar, card);
        content.setPadding(new Insets(19));
        VBox.setVgrow(card, Priority.ALWAYS);
        return content;
    }

    private Node createEndpointsPage() {
        VBox heading = pageTitle("Tài nguyên máy chủ",
                "Quản lý tài nguyên trong document root và uploads. Chọn file để xem MIME, dung lượng và URL HTTP.");
        Button refresh = secondaryButton("Làm mới danh sách");
        refresh.setOnAction(event -> refreshEndpoints());
        endpointTable = new TableView<>();
        endpointTable.setItems(endpoints);
        endpointTable.getColumns().setAll(java.util.Arrays.asList(
                endpointColumn("TÊN TỆP", 220, EndpointInfo::getFileName),
                endpointColumn("ĐƯỜNG DẪN HTTP", 220, EndpointInfo::getHttpPath),
                endpointColumn("ĐỊNH DẠNG / MIME", 220, EndpointInfo::getType),
                endpointColumn("DUNG LƯỢNG", 110, EndpointInfo::getSize),
                endpointColumn("SỬA ĐỔI", 150, EndpointInfo::getModified)));
        endpointTable.setFixedCellSize(40);
        endpointTable.setStyle("-fx-background-color: white; -fx-background-radius: 9; "
                + "-fx-border-color: #e5eaf1; -fx-border-radius: 9; "
                + "-fx-table-cell-border-color: #edf1f5; -fx-selection-bar: #dbeafe; "
                + "-fx-selection-bar-non-focused: #e8eef7;");
        Label fileDetails = new Label(
                "Chọn một tệp để xem thông tin nhận dạng và đường dẫn HTTP.");
        fileDetails.setWrapText(true);
        fileDetails.setStyle("-fx-text-fill: #596277; -fx-font-size: 12px;");
        endpointTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> {
                    if (selected == null) {
                        fileDetails.setText("Chọn tệp để xem thông tin.");
                        return;
                    }
                    String origin = selected.httpPath.startsWith("/uploads/")
                            ? "ĐÃ NHẬN TỪ CLIENT · POST/PUT"
                            : "TỆP ĐƯỢC CHIA SẺ BỞI SERVER";
                    fileDetails.setText(origin + "\n"
                            + "Tên: " + selected.getFileName() + "   ·   "
                            + "Định dạng: " + selected.getType() + "   ·   "
                            + "Dung lượng: " + selected.getSize() + "   ·   "
                            + "Sửa đổi: " + selected.getModified() + "\n"
                            + "HTTP URL: " + serverUrl(selected.getHttpPath()));
                });
        endpointTable.setRowFactory(table -> {
            javafx.scene.control.TableRow<EndpointInfo> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && urlField != null) {
                    urlField.setText(serverUrl(row.getItem().getHttpPath()));
                    selectTab(clientTabIndex());
                }
            });
            return row;
        });
        endpointTable.setPlaceholder(new Label("Không tìm thấy file trong document root."));
        Label inboxCaption = sectionTitle("TỆP NHẬN TỪ CLIENT VÀ TỆP CHIA SẺ");
        Button copyFileUrl = secondaryButton("Sao chép URL");
        copyFileUrl.setDisable(true);
        copyFileUrl.setOnAction(event -> {
            EndpointInfo selected = endpointTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(serverUrl(selected.getHttpPath()));
            Clipboard.getSystemClipboard().setContent(content);
            showInfo("Đã sao chép URL tài nguyên", serverUrl(selected.getHttpPath()));
        });
        endpointTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> copyFileUrl.setDisable(selected == null));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, inboxCaption, spacer, copyFileUrl, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(12, toolbar, endpointTable, fileDetails);
        card.setPadding(new Insets(15));
        card.setStyle(CARD_STYLE);
        VBox.setVgrow(endpointTable, Priority.ALWAYS);
        VBox content = new VBox(16, heading, card);
        content.setPadding(new Insets(19));
        VBox.setVgrow(card, Priority.ALWAYS);
        return content;
    }

    private Node createHttpTestPage() {
        VBox heading = pageTitle("Trình duyệt HTTP",
                "Kiểm tra phản hồi HTTP và phân tích trang HTML.");
        ToggleGroup methodGroup = new ToggleGroup();
        ToggleButton getMethodButton = methodToggle("GET", methodGroup);
        ToggleButton postMethodButton = methodToggle("POST", methodGroup);
        ToggleButton headMethodButton = methodToggle("HEAD", methodGroup);
        getMethodButton.setSelected(true);
        Label methodHelp = new Label("GET tải trang và phân tích HTML.");
        methodHelp.setWrapText(true);
        methodHelp.setMinWidth(205);
        methodHelp.setStyle("-fx-background-color: #f1f5f9; -fx-text-fill: #526176; "
                + "-fx-background-radius: 8; -fx-padding: 8 11; -fx-font-size: 11px;");
        Region methodSpacer = new Region();
        HBox.setHgrow(methodSpacer, Priority.ALWAYS);
        HBox methodSelector = new HBox(8, getMethodButton, postMethodButton,
                headMethodButton, methodSpacer, methodHelp);
        methodSelector.setAlignment(Pos.CENTER_LEFT);
        methodSelector.setPadding(new Insets(3, 0, 3, 0));
        urlField = new TextField();
        urlField.setPromptText("Nhập URL, ví dụ https://example.com/");
        urlField.setPrefHeight(48);
        urlField.setTooltip(new javafx.scene.control.Tooltip(
                "Nhập URL đầy đủ của tài nguyên cần gửi request HTTP."));
        urlField.setOnAction(event -> sendRequest());
        inspectUrlButton = secondaryButton("Phân tích URL");
        inspectUrlButton.setGraphic(svgIcon(
                "M10 3H3v7h7z M21 14h-7v7h7z M14 3h7v7h-7z M3 14h7v7H3z",
                14, "#2563eb"));
        inspectUrlButton.setPrefHeight(48);
        inspectUrlButton.setMinWidth(145);
        inspectUrlButton.setOnAction(event -> inspectUrl());
        sendButton = primaryButton("Gửi request");
        sendButton.setGraphic(svgIcon(
                "M2 3 22 12 2 21l3-8 11-1-11-1z",
                15, "#ffffff"));
        sendButton.setPrefHeight(48);
        sendButton.setMinWidth(150);
        sendButton.setOnAction(event -> sendRequest());
        HBox.setHgrow(urlField, Priority.ALWAYS);
        HBox urlRow = new HBox(10, urlField, inspectUrlButton, sendButton);
        urlRow.setAlignment(Pos.CENTER_LEFT);
        urlProtocolValue = urlInfoValue();
        urlHostValue = urlInfoValue();
        urlPortValue = urlInfoValue();
        urlDefaultPortValue = urlInfoValue();
        urlEffectivePortValue = urlInfoValue();
        urlFileValue = urlInfoValue();
        urlIpValue = urlInfoValue();
        urlInspectorStatus = new Label("Chưa phân tích URL");
        urlInspectorStatus.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px;");
        HBox urlInfoHeading = new HBox(8,
                svgIcon("M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 5v5l4 2",
                        16, "#0284c7"),
                sectionTitle("THÔNG TIN URL & ĐỊA CHỈ IP"), urlInspectorStatus);
        urlInfoHeading.setAlignment(Pos.CENTER_LEFT);
        HBox urlFieldsRow = new HBox(10,
                urlInfoItem("PROTOCOL", urlProtocolValue),
                urlInfoItem("HOSTNAME", urlHostValue),
                urlInfoItem("PORT TRONG URL", urlPortValue),
                urlInfoItem("PORT MẶC ĐỊNH", urlDefaultPortValue),
                urlInfoItem("PORT KẾT NỐI", urlEffectivePortValue));
        HBox urlResourceRow = new HBox(10,
                urlInfoItem("FILE / PATH", urlFileValue),
                urlInfoItem("HOSTNAME → IP (InetAddress)", urlIpValue));
        VBox urlAnalysisCard = new VBox(9, urlInfoHeading, urlFieldsRow, urlResourceRow);
        urlAnalysisCard.setPadding(new Insets(12));
        urlAnalysisCard.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 10; "
                + "-fx-border-color: #dbe5f0; -fx-border-radius: 10;");
        HBox requestTitle = new HBox(9,
                svgIcon("M3 4h18v13H3z M8 21h8v-2H8z M7 8h10v2H7z M7 12h7v2H7z",
                        16, "#2563eb"),
                sectionTitle("TẠO YÊU CẦU HTTP"));
        requestTitle.setAlignment(Pos.CENTER_LEFT);
        VBox requestBar = new VBox(12,
                requestTitle, fieldCaption("PHƯƠNG THỨC"), methodSelector,
                new javafx.scene.control.Separator(),
                fieldCaption("URL ĐÍCH"), urlRow, urlAnalysisCard);
        requestBar.setPadding(new Insets(19));
        requestBar.setStyle(CARD_STYLE);
        writeTokenField = new PasswordField();
        visibleWriteTokenField = new TextField();
        writeTokenField.setPromptText("Bearer token nếu máy chủ yêu cầu");
        visibleWriteTokenField.setPromptText(writeTokenField.getPromptText());
        HBox tokenRow = new HBox(12, fieldCaption("BEARER TOKEN"), writeTokenField);
        tokenRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(writeTokenField, Priority.ALWAYS);
        HBox.setHgrow(visibleWriteTokenField, Priority.ALWAYS);
        visibleWriteTokenField.textProperty().addListener(
                (observable, previous, value) -> writeTokenField.setText(value));
        Button revealTokenButton = secondaryButton("Hiện");
        revealTokenButton.setOnAction(event -> {
            if (tokenRow.getChildren().get(1) == writeTokenField) {
                visibleWriteTokenField.setText(writeTokenField.getText());
                tokenRow.getChildren().set(1, visibleWriteTokenField);
                revealTokenButton.setText("Ẩn");
            } else {
                writeTokenField.setText(visibleWriteTokenField.getText());
                tokenRow.getChildren().set(1, writeTokenField);
                revealTokenButton.setText("Hiện");
            }
        });
        tokenRow.getChildren().add(revealTokenButton);
        TitledPane authPane = new TitledPane("Xác thực tùy chọn", tokenRow);
        authPane.setGraphic(svgIcon("M12 2 4 5v6c0 5 3 9 8 11 5-2 8-6 8-11V5z M9 12l2 2 4-5",
                15, "#64748b"));
        authPane.setExpanded(false);
        authPane.setAnimated(false);
        authPane.setStyle("-fx-text-fill: #475569; -fx-font-size: 12px; "
                + "-fx-background-color: white; -fx-background-radius: 9; "
                + "-fx-border-color: #e2e8f0; -fx-border-radius: 9;");

        requestBodyArea = new TextArea();
        requestBodyArea.setPromptText("name=Student&message=Hello%20server");
        requestBodyArea.setPrefRowCount(3);
        requestBodyArea.setWrapText(true);
        requestBodyArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        Label requestBodyCaption = fieldCaption("NỘI DUNG POST");
        Label requestBodyHint = new Label(
                "Gửi body theo dạng application/x-www-form-urlencoded.");
        requestBodyHint.setStyle("-fx-text-fill: #858da1; -fx-font-size: 11px;");
        VBox postBodyCard = new VBox(8, requestBodyCaption, requestBodyArea, requestBodyHint);
        postBodyCard.setPadding(new Insets(13));
        postBodyCard.setStyle(CARD_STYLE);
        postBodyCard.setVisible(false);
        postBodyCard.setManaged(false);
        Runnable updateMethodSelection = () -> {
            boolean hasBody = "POST".equals(selectedHttpMethod);
            postBodyCard.setVisible(hasBody);
            postBodyCard.setManaged(hasBody);
            methodHelp.setText("GET".equals(selectedHttpMethod)
                    ? "GET tải trang và phân tích HTML."
                    : hasBody ? "POST gửi body và phân tích HTML phản hồi."
                            : "HEAD chỉ lấy status và metadata tài nguyên.");
            String methodTint = "GET".equals(selectedHttpMethod) ? "#eff6ff"
                    : hasBody ? "#f5f3ff" : "#ecfeff";
            String methodInk = "GET".equals(selectedHttpMethod) ? "#1d4ed8"
                    : hasBody ? "#6d28d9" : "#0e7490";
            methodHelp.setStyle("-fx-background-color: " + methodTint
                    + "; -fx-text-fill: " + methodInk
                    + "; -fx-background-radius: 8; -fx-padding: 8 11; -fx-font-size: 11px;");
            if (hasBody) {
                if (requestBodyArea.getText().isBlank()) {
                    requestBodyArea.setText("name=Student&message=Hello%20server");
                }
            }
            styleMethodButton(getMethodButton, selectedHttpMethod);
            styleMethodButton(postMethodButton, selectedHttpMethod);
            styleMethodButton(headMethodButton, selectedHttpMethod);
        };
        getMethodButton.setOnAction(event -> {
            if (!getMethodButton.isSelected()) getMethodButton.setSelected(true);
            selectedHttpMethod = "GET";
            updateMethodSelection.run();
        });
        postMethodButton.setOnAction(event -> {
            if (!postMethodButton.isSelected()) postMethodButton.setSelected(true);
            selectedHttpMethod = "POST";
            updateMethodSelection.run();
        });
        headMethodButton.setOnAction(event -> {
            if (!headMethodButton.isSelected()) headMethodButton.setSelected(true);
            selectedHttpMethod = "HEAD";
            updateMethodSelection.run();
        });
        updateMethodSelection.run();

        chooseUploadButton = secondaryButton("Chọn tệp");
        chooseUploadButton.setOnAction(event -> chooseUploadFile());
        uploadSummaryLabel = new Label("Đính kèm tệp như email · tối đa 50 MB mỗi tệp");
        uploadSummaryLabel.setStyle("-fx-text-fill: #68738a; -fx-font-size: 11px;");
        attachmentList = new VBox(7);
        Label attachmentEmpty = new Label("Chưa có tệp đính kèm. Chọn một hoặc nhiều tệp để gửi.");
        attachmentEmpty.setStyle("-fx-text-fill: #858da1; -fx-padding: 8 4;");
        attachmentList.getChildren().add(attachmentEmpty);
        transferMethodBox = new ComboBox<>(FXCollections.observableArrayList("POST", "PUT"));
        transferMethodBox.getSelectionModel().select("POST");
        transferMethodBox.setTooltip(new javafx.scene.control.Tooltip(
                "POST tạo tệp mới; PUT tạo mới hoặc thay thế tệp cùng tên trong /uploads."));
        uploadButton = primaryButton("Tải tệp lên");
        uploadButton.setGraphic(svgIcon("M10 22h4v-9h5l-7-8-7 8h5z", 15, "#ffffff"));
        uploadButton.setOnAction(event -> uploadSelectedFile());
        HBox composeToolbar = new HBox(10,
                fieldCaption("PHƯƠNG THỨC"), transferMethodBox,
                chooseUploadButton, uploadButton);
        composeToolbar.setAlignment(Pos.CENTER_LEFT);
        uploadProgressBar = new ProgressBar(0);
        uploadProgressBar.setMaxWidth(Double.MAX_VALUE);
        uploadProgressBar.setVisible(false);
        uploadProgressBar.setManaged(false);
        VBox uploadBar = new VBox(10,
                sectionTitle("GỬI TỆP ĐÍNH KÈM"),
                new Label("Chọn nội dung cần chuyển đến máy chủ. Server sẽ lưu tệp vào /uploads và trả status HTTP."),
                composeToolbar, attachmentList, uploadSummaryLabel, uploadProgressBar);
        uploadBar.getChildren().get(1).setStyle("-fx-text-fill: #858da1; -fx-font-size: 11px;");
        uploadBar.setPadding(new Insets(14));
        uploadBar.setStyle(CARD_STYLE);

        statusBadge = new Label("Sẵn sàng");
        statusBadge.setStyle(badgeStyle("#e9ecf4", "#626b80"));
        responseMeta = new Label("Nhập URL để bắt đầu");
        responseMeta.setStyle("-fx-text-fill: #858da1;");
        saveResponseButton = secondaryButton("Lưu phản hồi");
        saveResponseButton.setGraphic(svgIcon(
                "M10 2h4v9h4l-6 6-6-6h4z M4 19h16v3H4z",
                15, "#2563eb"));
        saveResponseButton.setDisable(true);
        saveResponseButton.setOnAction(event -> saveResponseToFile());
        Region responseSpacer = new Region();
        HBox.setHgrow(responseSpacer, Priority.ALWAYS);
        HBox responseHeader = new HBox(12, statusBadge, responseMeta, responseSpacer,
                saveResponseButton);
        responseHeader.setAlignment(Pos.CENTER_LEFT);
        responseHeader.setPadding(new Insets(10, 13, 10, 13));
        responseHeader.setStyle(CARD_STYLE);

        webView = new WebView();
        webView.setContextMenuEnabled(true);
        webView.getEngine().locationProperty().addListener((observable, previous, location) -> {
            if (interceptingBrowserNavigation || location == null
                    || !location.matches("(?i)^https?://.*")
                    || location.equals(lastClientLoadedUrl)) {
                return;
            }
            interceptingBrowserNavigation = true;
            webView.getEngine().getLoadWorker().cancel();
            urlField.setText(location);
            sendRequest();
            Platform.runLater(() -> interceptingBrowserNavigation = false);
        });
        headerArea = readOnlyTextArea();
        bodyArea = readOnlyTextArea();
        htmlAnalysisLabel = new Label(
                "GET và POST phân tích nội dung HTML; HEAD hiển thị thông tin tài nguyên.");
        htmlAnalysisLabel.setWrapText(true);
        htmlAnalysisLabel.setPadding(new Insets(10));
        htmlAnalysisLabel.setStyle("-fx-background-color: #f1f7f9; -fx-text-fill: #31566a; "
                + "-fx-background-radius: 8; -fx-font-size: 11px;");
        VBox previewContent = new VBox(10, htmlAnalysisLabel, webView);
        previewContent.setPadding(new Insets(10));
        VBox.setVgrow(webView, Priority.ALWAYS);
        TabPane resultTabs = new TabPane(
                createIconTab("Trang & phân tích", "M3 4h18v13H3z M8 21h8v-2H8z M7 8h10v2H7z M7 12h7v2H7z", previewContent),
                createIconTab("Headers", "M5 3h14v18H5z M8 7h8v2H8z M8 11h8v2H8z M8 15h5v2H8z", headerArea),
                createIconTab("Nội dung", "M5 3h14v18H5z M8 7h8v2H8z M8 11h8v2H8z M8 15h8v2H8z", bodyArea),
                createIconTab("Lịch sử", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 5v5l4 2-1 2-5-3V7z", createClientActivityTable()),
                createIconTab("Tệp & tải lên", "M3 5a2 2 0 0 1 2-2h6l2 2h6a2 2 0 0 1 2 2v1H8a3 3 0 0 0-2.7 1.7L3 16z M8 10h13l-3 9a2 2 0 0 1-2 1H5a2 2 0 0 1-1.9-2.6l2.8-6.2A2 2 0 0 1 8 10z", uploadBar));
        VBox.setVgrow(resultTabs, Priority.ALWAYS);

        VBox browserCard = new VBox(resultTabs);
        browserCard.setPadding(new Insets(1));
        browserCard.setStyle(CARD_STYLE);
        VBox requestComposer = new VBox(10, requestBar, authPane, postBodyCard);
        VBox content = new VBox(16, heading, requestComposer, responseHeader, browserCard);
        content.setPadding(new Insets(22));
        VBox.setVgrow(browserCard, Priority.ALWAYS);
        VBox.setVgrow(resultTabs, Priority.ALWAYS);
        webView.getEngine().load("about:blank");
        return content;
    }

    private void startServer() {
        if (server.isRunning()) {
            return;
        }
        if ("Client (máy khách)".equals(roleBox.getValue())) {
            return;
        }
        try {
            int port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            String rootValue = rootField.getText().trim();
            if (rootValue.isEmpty()) {
                throw new IllegalArgumentException("Vui lòng chọn document root.");
            }
            Path root = Path.of(rootValue);
            server.start(port, root, this::appendLog,
                    info -> Platform.runLater(() -> addRequest(info)));
            serverStartedAtMillis = System.currentTimeMillis();
            if (serverClock != null) {
                serverClock.stop();
            }
            serverClock = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateServerUptime()));
            serverClock.setCycleCount(Timeline.INDEFINITE);
            serverClock.play();
            updateServerUptime();
            serverTokenDisplay.setText(server.getWriteToken());
            String lanAddress = HttpServerCore.getLanAddress();
            String loopbackUrl = "http://127.0.0.1:" + server.getPort() + "/";
            String lanUrl = lanAddress == null ? null
                    : "http://" + lanAddress + ":" + server.getPort() + "/";
            addressBadge.setText(loopbackUrl);
            connectAddressLabel.setText(lanUrl == null
                    ? "Máy này: " + loopbackUrl + "  ·  Không tìm thấy IP LAN."
                    : "Máy này: " + loopbackUrl + "\nMáy khác cùng mạng: " + lanUrl);
            connectionHelpLabel.setText("Nếu mở trên máy khác, dùng URL LAN và cho phép "
                    + "java.exe qua Windows Firewall nếu được hỏi.");
            if (urlField != null && lanAddress != null
                    && (urlField.getText().isEmpty()
                            || urlField.getText().contains("localhost")
                            || urlField.getText().contains("127.0.0.1"))) {
                urlField.setText(loopbackUrl);
            }
            serverState.setText("●  RUNNING");
            serverState.setStyle(stateStyle(true));
            updateDashboardServerSummary();
            serverSettingsCard.setVisible(true);
            serverSettingsCard.setManaged(true);
            startButton.setDisable(true);
            stopButton.setDisable(false);
            portField.setDisable(true);
            rootField.setDisable(true);
            refreshEndpoints();
        } catch (BindException e) {
            String message = "Cổng " + portField.getText().trim()
                    + " đang được ứng dụng khác sử dụng. Hãy dừng ứng dụng đó hoặc chọn cổng khác.";
            showServerError(message);
            showInfo("Không thể khởi động máy chủ", message);
        } catch (NumberFormatException e) {
            showServerError("Port phải là số nguyên từ 1 đến 65535.");
        } catch (Exception e) {
            showServerError(e.getMessage() == null ? "Không thể khởi động server." : e.getMessage());
        }
    }

    private void runServerHealthCheck() {
        String healthUrl = "http://127.0.0.1:" + server.getPort() + "/__health";
        dashboardHealthStatus.setText("Đang gửi GET /__health…");
        dashboardHealthStatus.setStyle("-fx-text-fill: #9a6500; -fx-font-size: 11px;");
        Task<HttpClientCore.Response> healthRequest = new Task<HttpClientCore.Response>() {
            @Override
            protected HttpClientCore.Response call() throws Exception {
                return HttpClientCore.sendRequest(healthUrl, "GET");
            }
        };
        healthRequest.setOnSucceeded(event -> {
            HttpClientCore.Response response = healthRequest.getValue();
            boolean healthy = response.statusCode >= 200 && response.statusCode < 300;
            dashboardHealthStatus.setText((healthy ? "✓  " : "!  ")
                    + response.statusLine + " · " + response.body);
            dashboardHealthStatus.setStyle("-fx-text-fill: "
                    + (healthy ? "#14804a" : "#bf3f32") + "; -fx-font-size: 11px;");
            appendLog("Health check GET " + healthUrl + " → "
                    + response.statusLine + " · " + response.body);
        });
        healthRequest.setOnFailed(event -> {
            String error = healthRequest.getException().getMessage();
            dashboardHealthStatus.setText("✕  Health check thất bại: " + error);
            dashboardHealthStatus.setStyle("-fx-text-fill: #bf3f32; -fx-font-size: 11px;");
            appendLog("Health check GET " + healthUrl + " failed: " + error);
        });
        Thread worker = new Thread(healthRequest, "http-health-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void stopServer() {
        if (!server.isRunning()) {
            return;
        }
        server.stop();
        if (serverClock != null) {
            serverClock.stop();
        }
        serverTokenDisplay.clear();
        serverState.setText("●  STOPPED");
        serverState.setStyle(stateStyle(false));
        addressBadge.setText("SERVER ĐÃ DỪNG");
        connectAddressLabel.setText("Server đã dừng. Nhấn Start server để mở lại.");
        serverUptimeLabel.setText("◷  Thời gian chạy: —");
        dashboardHealthStatus.setText("Server đã dừng");
        dashboardHealthStatus.setStyle("-fx-text-fill: #748197; -fx-font-size: 11px;");
        updateDashboardServerSummary();
        startButton.setDisable(false);
        stopButton.setDisable(true);
        portField.setDisable(false);
        rootField.setDisable(false);
    }

    private void updateRoleControls() {
        if (roleBox == null || startButton == null) {
            return;
        }
        boolean clientOnly = "Client (máy khách)".equals(roleBox.getValue());
        boolean serverOnly = "Server (máy chủ)".equals(roleBox.getValue());
        if (clientOnly && server.isRunning()) {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Dừng HTTP Server?");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Chuyển máy này sang Client sẽ dừng server đang chạy. Tiếp tục?");
            confirmation.initOwner(stage);
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                roleBox.setValue(activeRole);
                return;
            }
        }
        roleDescription.setText(clientOnly
                ? "Kết nối tới máy chủ khác trong tab HTTP Client."
                : serverOnly
                        ? "Lắng nghe HTTP và chia sẻ thư mục đã chọn."
                        : "Chạy server và gửi request từ client tích hợp.");
        if (mainTabs != null) {
            mainTabs.getSelectionModel().select(clientOnly ? 4 : 0);
        }
        if (clientOnly && server.isRunning()) {
            stopServer();
        }
        portField.setDisable(clientOnly || server.isRunning());
        rootField.setDisable(clientOnly || server.isRunning());
        startButton.setDisable(clientOnly || server.isRunning());
        stopButton.setDisable(clientOnly || !server.isRunning());
        if (clientOnly) {
            serverState.setText("●  CLIENT MODE");
            serverState.setStyle(stateStyle(false));
            addressBadge.setText("CLIENT — nhập IP server");
            connectAddressLabel.setText("Nhập URL máy chủ trong trình duyệt Client.");
            connectionHelpLabel.setText("Dùng IP LAN của máy chủ, ví dụ http://192.168.1.25:8080/. Không dùng localhost để kết nối máy khác.");
            serverSettingsCard.setVisible(false);
            serverSettingsCard.setManaged(false);
            clientGuideCard.setVisible(true);
            clientGuideCard.setManaged(true);
        } else if (serverOnly) {
            serverSettingsCard.setVisible(true);
            serverSettingsCard.setManaged(true);
            clientGuideCard.setVisible(false);
            clientGuideCard.setManaged(false);
            if (!server.isRunning()) {
                serverState.setText("●  STOPPED");
                serverState.setStyle(stateStyle(false));
                addressBadge.setText("SERVER CHƯA CHẠY");
                connectAddressLabel.setText("Nhấn “Khởi động máy chủ”, rồi gửi URL LAN cho máy client.");
            }
        } else if (!server.isRunning()) {
            serverState.setText("●  STOPPED");
            serverState.setStyle(stateStyle(false));
            addressBadge.setText("SERVER CHƯA CHẠY");
            connectAddressLabel.setText("Nhấn Start server để xem URL dùng cho máy client.");
            connectionHelpLabel.setText("Hai máy cần cùng Wi-Fi/LAN. Nếu bị chặn, cho phép java.exe qua Windows Firewall trên mạng đang dùng.");
            serverSettingsCard.setVisible(true);
            serverSettingsCard.setManaged(true);
            clientGuideCard.setVisible(false);
            clientGuideCard.setManaged(false);
        }
        activeRole = roleBox.getValue();
        updateDashboardServerSummary();
    }

    private void updateDashboardServerSummary() {
        if (dashboardServerStatus == null || dashboardServerAddress == null) {
            return;
        }
        if (server.isRunning()) {
            dashboardServerStatus.setText("●  RUNNING");
            dashboardServerStatus.setStyle(stateStyle(true));
        } else if ("Client (máy khách)".equals(roleBox.getValue())) {
            dashboardServerStatus.setText("●  CLIENT MODE");
            dashboardServerStatus.setStyle(stateStyle(false));
        } else {
            dashboardServerStatus.setText("●  STOPPED");
            dashboardServerStatus.setStyle(stateStyle(false));
        }
        dashboardServerAddress.setText(connectAddressLabel.getText());
    }

    private void copyServerAddress() {
        if (!server.isRunning()) {
            showInfo("Server chưa chạy", "Chọn chế độ Server rồi nhấn Start server trước.");
            return;
        }
        String address = serverUrl("/");
        ClipboardContent content = new ClipboardContent();
        content.putString(address);
        Clipboard.getSystemClipboard().setContent(content);
        showInfo("Đã copy URL", "Gửi địa chỉ này cho máy client: " + address);
    }

    private void updateServerUptime() {
        if (!server.isRunning() || serverStartedAtMillis == 0 || serverUptimeLabel == null) {
            return;
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - serverStartedAtMillis) / 1000);
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainingSeconds = seconds % 60;
        serverUptimeLabel.setText(String.format(Locale.ROOT,
                "◷  Thời gian chạy: %02d:%02d:%02d  ·  TCP :%d",
                hours, minutes, remainingSeconds, server.getPort()));
    }

    private void filterRequests(String query) {
        if (filteredRequests == null) {
            return;
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filteredRequests.setPredicate(request -> normalized.isEmpty()
                || request.method.toLowerCase(Locale.ROOT).contains(normalized)
                || request.path.toLowerCase(Locale.ROOT).contains(normalized)
                || String.valueOf(request.statusCode).contains(normalized)
                || displayClientAddress(request.clientAddress).toLowerCase(Locale.ROOT)
                        .contains(normalized));
        updateRequestFilterSummary();
    }

    private void updateRequestFilterSummary() {
        if (filteredRequests != null && requestFilterSummary != null) {
            requestFilterSummary.setText(filteredRequests.size() + " / "
                    + requests.size() + " request");
        }
    }

    private void exportServerLog() {
        if (serverLog == null || serverLog.getText().isEmpty()) {
            showInfo("Chưa có log", "Chưa có nội dung log để xuất.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Xuất nhật ký máy chủ");
        chooser.setInitialFileName("lab7-server-" + LocalTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log");
        File destination = chooser.showSaveDialog(stage);
        if (destination == null) {
            return;
        }
        try {
            Files.writeString(destination.toPath(), serverLog.getText(),
                    java.nio.charset.StandardCharsets.UTF_8);
            showInfo("Đã xuất log", destination.getAbsolutePath());
        } catch (IOException e) {
            showInfo("Không thể xuất log", e.getMessage());
        }
    }

    private void copyWriteToken() {
        if (!server.isRunning() || serverTokenDisplay.getText().isEmpty()) {
            showInfo("Chưa có token", "Khởi động máy chủ để tạo token quyền ghi.");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(serverTokenDisplay.getText());
        Clipboard.getSystemClipboard().setContent(content);
        showInfo("Đã sao chép token", "Chỉ chia sẻ token với máy client đáng tin cậy.");
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void chooseDocumentRoot() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Chọn thư mục nội dung server");
        File current = Path.of(rootField.getText().trim()).toAbsolutePath().toFile();
        if (current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File selected = chooser.showDialog(stage);
        if (selected != null) {
            rootField.setText(selected.getAbsolutePath());
            refreshEndpoints();
        }
    }

    private void inspectUrl() {
        String address = urlField == null ? "" : urlField.getText().trim();
        if (address.isEmpty()) {
            urlInspectorStatus.setText("Nhập URL trước");
            urlInspectorStatus.setStyle("-fx-text-fill: #b45309; -fx-font-size: 11px;");
            return;
        }

        inspectUrlButton.setDisable(true);
        urlInspectorStatus.setText("Đang phân tích URL và tra DNS…");
        urlInspectorStatus.setStyle("-fx-text-fill: #0369a1; -fx-font-size: 11px;");
        Task<UrlInspector.Analysis> task = new Task<UrlInspector.Analysis>() {
            @Override
            protected UrlInspector.Analysis call() throws Exception {
                return UrlInspector.analyze(address);
            }
        };
        task.setOnSucceeded(event -> {
            UrlInspector.Analysis result = task.getValue();
            urlProtocolValue.setText(result.protocol);
            urlHostValue.setText(result.host);
            urlPortValue.setText(result.port < 0 ? "-1 · không ghi trong URL"
                    : Integer.toString(result.port));
            urlDefaultPortValue.setText(result.defaultPort < 0 ? "Không xác định"
                    : Integer.toString(result.defaultPort));
            urlEffectivePortValue.setText(result.effectivePort < 0 ? "Không xác định"
                    : Integer.toString(result.effectivePort));
            urlFileValue.setText(result.file.isEmpty() ? "(rỗng)" : result.file);
            urlIpValue.setText(result.addresses.isEmpty()
                    ? result.dnsError : String.join("\n", result.addresses));
            urlInspectorStatus.setText(result.dnsError == null
                    ? "Đã phân giải " + result.addresses.size() + " địa chỉ"
                    : result.dnsError);
            urlInspectorStatus.setStyle(result.dnsError == null
                    ? "-fx-text-fill: #047857; -fx-font-size: 11px;"
                    : "-fx-text-fill: #b45309; -fx-font-size: 11px;");
            inspectUrlButton.setDisable(false);
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            urlInspectorStatus.setText(error == null ? "URL không hợp lệ"
                    : "URL không hợp lệ: " + error.getMessage());
            urlInspectorStatus.setStyle("-fx-text-fill: #b91c1c; -fx-font-size: 11px;");
            inspectUrlButton.setDisable(false);
        });
        Thread thread = new Thread(task, "url-inspector-dns");
        thread.setDaemon(true);
        thread.start();
    }

    private void sendRequest() {
        String url = urlField.getText().trim();
        if (!url.isEmpty() && !url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
            urlField.setText(url);
        }
        String method = selectedHttpMethod;
        if (url.isEmpty()) {
            showResponseError("Nhập địa chỉ IP hoặc URL của máy chủ trước khi kết nối.");
            return;
        }
        try {
            java.net.URI parsedUrl = java.net.URI.create(url);
            if (!("http".equalsIgnoreCase(parsedUrl.getScheme())
                    || "https".equalsIgnoreCase(parsedUrl.getScheme()))
                    || parsedUrl.getHost() == null
                    || parsedUrl.getRawUserInfo() != null || parsedUrl.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            showResponseError(
                    "URL phải có dạng http(s)://host[:port]/path và không chứa user info/fragment.");
            return;
        }
        final String requestUrl = url;
        final Map<String, String> requestHeaders = new java.util.LinkedHashMap<>();
        if (!writeTokenField.getText().isBlank()) {
            requestHeaders.put("Authorization", "Bearer " + writeTokenField.getText().trim());
        }
        final byte[] requestBody;
        if ("POST".equals(method)) {
            requestHeaders.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            requestBody = requestBodyArea.getText().getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
        } else {
            requestBody = null;
        }
        final String requestSummary = formatRequestSummary(
                method, requestUrl, requestHeaders, requestBody);
        sendButton.setDisable(true);
        statusBadge.setText("ĐANG KẾT NỐI");
        statusBadge.setStyle(badgeStyle("#fff4d8", "#9a6500"));
        responseMeta.setText(method + "  " + url);
        final long requestStartedNanos = System.nanoTime();

        Task<HttpClientCore.Response> task = new Task<HttpClientCore.Response>() {
            @Override
            protected HttpClientCore.Response call() throws Exception {
                return HttpClientCore.sendRequest(
                        requestUrl, method, requestHeaders, requestBody);
            }
        };
        task.setOnSucceeded(event -> {
            sendButton.setDisable(false);
            saveResponseButton.setDisable(task.getValue().bodyBytes.length == 0);
            long elapsedMillis = (System.nanoTime() - requestStartedNanos) / 1_000_000;
            displayResponse(task.getValue(), elapsedMillis, method, requestSummary);
            addClientActivity(method, requestUrl, task.getValue().statusCode,
                    elapsedMillis, task.getValue().bodyBytes.length);
        });
        task.setOnFailed(event -> {
            sendButton.setDisable(false);
            saveResponseButton.setDisable(true);
            showResponseError(task.getException().getMessage());
            addClientActivity(method, requestUrl, 0,
                    (System.nanoTime() - requestStartedNanos) / 1_000_000, 0);
        });
        Thread thread = new Thread(task, "http-client-request");
        thread.setDaemon(true);
        thread.start();
    }

    private void chooseUploadFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn tệp đính kèm gửi đến máy chủ");
        List<File> selected = chooser.showOpenMultipleDialog(stage);
        if (selected != null) {
            for (File file : selected) {
                boolean duplicate = selectedUploadFiles.stream().anyMatch(existing ->
                        existing.toPath().toAbsolutePath().normalize().equals(
                                file.toPath().toAbsolutePath().normalize()));
                if (!duplicate) {
                    selectedUploadFiles.add(file);
                }
            }
            renderAttachments();
        }
    }

    private void uploadSelectedFile() {
        if (selectedUploadFiles.isEmpty()) {
            showInfo("Chưa có tệp đính kèm", "Chọn một hoặc nhiều tệp trước khi gửi.");
            return;
        }
        File oversized = selectedUploadFiles.stream()
                .filter(file -> file.length() > 50L * 1024 * 1024).findFirst().orElse(null);
        if (oversized != null) {
            showInfo("Tệp quá lớn", oversized.getName() + " vượt giới hạn 50 MB mỗi tệp.");
            return;
        }
        String baseUrl = urlField.getText().trim();
        if (!baseUrl.matches("(?i)^https?://.*")) {
            baseUrl = "http://" + baseUrl;
        }
        final String method = transferMethodBox.getValue();
        final java.net.URI serverUri;
        try {
            serverUri = java.net.URI.create(baseUrl);
            if (!"http".equalsIgnoreCase(serverUri.getScheme()) || serverUri.getHost() == null) {
                throw new IllegalArgumentException("URL không hợp lệ.");
            }
        } catch (Exception e) {
            showResponseError("Nhập URL máy chủ hợp lệ trước khi upload.");
            return;
        }
        final List<File> filesToSend = new java.util.ArrayList<>(selectedUploadFiles);
        final String writeToken = writeTokenField.getText().trim();
        uploadButton.setDisable(true);
        chooseUploadButton.setDisable(true);
        transferMethodBox.setDisable(true);
        uploadProgressBar.setProgress(0);
        uploadProgressBar.setVisible(true);
        uploadProgressBar.setManaged(true);
        statusBadge.setText("ĐANG GỬI TỆP · HTTP " + method);
        statusBadge.setStyle(badgeStyle("#fff4d8", "#9a6500"));
        responseMeta.setText("Đang gửi " + filesToSend.size() + " tệp đính kèm đến "
                + serverUri.getHost() + ":" + effectivePort(serverUri));
        final long uploadStartedNanos = System.nanoTime();

        Task<List<UploadOutcome>> task = new Task<List<UploadOutcome>>() {
            @Override
            protected List<UploadOutcome> call() throws Exception {
                List<UploadOutcome> outcomes = new java.util.ArrayList<>();
                for (int index = 0; index < filesToSend.size(); index++) {
                    File file = filesToSend.get(index);
                    long fileStartedNanos = System.nanoTime();
                    updateMessage("Gửi " + (index + 1) + "/" + filesToSend.size()
                            + " · " + file.getName() + " · " + formatBytes(file.length()));
                    try {
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        Map<String, String> headers = new java.util.LinkedHashMap<>();
                        headers.put("Content-Type", detectType(file.toPath()));
                        if (!writeToken.isEmpty()) {
                            headers.put("Authorization", "Bearer " + writeToken);
                        }
                        String path;
                        if ("POST".equals(method)) {
                            path = "/__upload";
                            headers.put("X-File-Name", java.net.URLEncoder.encode(file.getName(),
                                    java.nio.charset.StandardCharsets.UTF_8.name()));
                        } else {
                            path = "/uploads/" + encodePathSegment(file.getName());
                        }
                        String uploadUrl = serverUri.resolve(path).toString();
                        HttpClientCore.Response response = HttpClientCore.sendRequest(
                                uploadUrl, method, headers, bytes);
                        outcomes.add(new UploadOutcome(file, response, null,
                                (System.nanoTime() - fileStartedNanos) / 1_000_000));
                    } catch (Exception e) {
                        outcomes.add(new UploadOutcome(file, null, e.getMessage(),
                                (System.nanoTime() - fileStartedNanos) / 1_000_000));
                    }
                    updateProgress(index + 1, filesToSend.size());
                }
                return outcomes;
            }
        };
        uploadProgressBar.progressProperty().bind(task.progressProperty());
        uploadSummaryLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(event -> {
            uploadProgressBar.progressProperty().unbind();
            uploadSummaryLabel.textProperty().unbind();
            uploadButton.setDisable(false);
            chooseUploadButton.setDisable(false);
            transferMethodBox.setDisable(false);
            List<UploadOutcome> outcomes = task.getValue();
            for (UploadOutcome outcome : outcomes) {
                String path = "POST".equals(method) ? "/__upload"
                        : "/uploads/" + encodePathSegment(outcome.file.getName());
                int code = outcome.response == null ? 0 : outcome.response.statusCode;
                addClientActivity(method, serverUri.resolve(path).toString(), code,
                        outcome.durationMillis,
                        outcome.file.length());
            }
            long successCount = outcomes.stream().filter(outcome -> outcome.response != null
                    && outcome.response.statusCode >= 200 && outcome.response.statusCode < 300).count();
            long totalSize = filesToSend.stream().mapToLong(File::length).sum();
            renderAttachments(outcomes);
            uploadSummaryLabel.setText(successCount + "/" + outcomes.size()
                    + " tệp được máy chủ nhận · " + formatBytes(totalSize) + " · /uploads");
            UploadOutcome last = outcomes.stream().filter(outcome -> outcome.response != null)
                    .reduce((first, next) -> next).orElse(null);
            if (last != null) {
                displayResponse(last.response,
                        (System.nanoTime() - uploadStartedNanos) / 1_000_000, method, null);
                saveResponseButton.setDisable(last.response.bodyBytes.length == 0);
                statusBadge.setText(successCount == outcomes.size()
                        ? "ĐÃ GỬI " + successCount + " TỆP" : "CÓ TỆP GỬI THẤT BẠI");
                statusBadge.setStyle(badgeStyle(successCount == outcomes.size()
                        ? "#e7f8ef" : "#fff0ed", successCount == outcomes.size()
                        ? "#14804a" : "#bf3f32"));
            }
            refreshEndpoints();
            appendLog("CLIENT gửi " + successCount + "/" + outcomes.size()
                    + " tệp qua HTTP " + method + " · " + formatBytes(totalSize));
        });
        task.setOnFailed(event -> {
            uploadProgressBar.progressProperty().unbind();
            uploadSummaryLabel.textProperty().unbind();
            uploadButton.setDisable(false);
            chooseUploadButton.setDisable(false);
            transferMethodBox.setDisable(false);
            uploadSummaryLabel.setText("Không thể gửi tệp: " + task.getException().getMessage());
            showResponseError(task.getException().getMessage());
        });
        Thread thread = new Thread(task, "http-client-upload");
        thread.setDaemon(true);
        thread.start();
    }

    private void renderAttachments() {
        renderAttachments(java.util.Collections.emptyList());
    }

    private void renderAttachments(List<UploadOutcome> outcomes) {
        attachmentList.getChildren().clear();
        if (selectedUploadFiles.isEmpty()) {
            Label empty = new Label("Chưa có tệp đính kèm. Chọn một hoặc nhiều tệp để gửi.");
            empty.setStyle("-fx-text-fill: #858da1; -fx-padding: 8 4;");
            attachmentList.getChildren().add(empty);
            uploadSummaryLabel.setText("Đính kèm tệp như email · tối đa 50 MB mỗi tệp");
            return;
        }
        Map<String, UploadOutcome> results = outcomes.stream().collect(Collectors.toMap(
                outcome -> outcome.file.toPath().toAbsolutePath().normalize().toString(),
                outcome -> outcome, (first, next) -> next));
        for (File file : selectedUploadFiles) {
            String extension = fileExtension(file.getName());
            Label fileIcon = new Label(extension.isEmpty() ? "FILE" : extension.toUpperCase(Locale.ROOT));
            fileIcon.setMinWidth(52);
            fileIcon.setAlignment(Pos.CENTER);
            fileIcon.setStyle("-fx-background-color: #e8f3f7; -fx-text-fill: #155e75; "
                    + "-fx-font-size: 10px; -fx-font-weight: bold; -fx-background-radius: 7; "
                    + "-fx-padding: 9 6;");
            Label name = new Label(file.getName());
            name.setStyle("-fx-text-fill: #30384c; -fx-font-weight: bold;");
            Label metadata = new Label(extensionDescription(extension) + " · "
                    + detectType(file.toPath()) + " · " + formatBytes(file.length()));
            metadata.setStyle("-fx-text-fill: #858da1; -fx-font-size: 10px;");
            VBox info = new VBox(4, name, metadata);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            UploadOutcome outcome = results.get(
                    file.toPath().toAbsolutePath().normalize().toString());
            String stateText = outcome == null ? "SẴN SÀNG"
                    : outcome.response == null ? "LỖI KẾT NỐI"
                    : outcome.response.statusCode + " " + reasonPhrase(outcome.response.statusCode);
            Label state = new Label(stateText);
            boolean successful = outcome != null && outcome.response != null
                    && outcome.response.statusCode >= 200 && outcome.response.statusCode < 300;
            state.setStyle("-fx-text-fill: " + (outcome == null ? "#68738a"
                    : successful ? "#14804a" : "#bf3f32")
                    + "; -fx-font-size: 10px; -fx-font-weight: bold;");
            if (outcome != null && outcome.error != null) {
                state.setTooltip(new javafx.scene.control.Tooltip(outcome.error));
            }
            Button remove = secondaryButton("×");
            remove.setTooltip(new javafx.scene.control.Tooltip("Bỏ tệp đính kèm"));
            remove.setOnAction(event -> {
                selectedUploadFiles.remove(file);
                renderAttachments();
            });
            HBox card = new HBox(10, fileIcon, info, spacer, state, remove);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(8));
            card.setStyle("-fx-background-color: #f8f9fc; -fx-background-radius: 8; "
                    + "-fx-border-color: #e7eaf2; -fx-border-radius: 8;");
            attachmentList.getChildren().add(card);
        }
        uploadSummaryLabel.setText(selectedUploadFiles.size() + " tệp đính kèm · "
                + formatBytes(selectedUploadFiles.stream().mapToLong(File::length).sum())
                + " tổng cộng · tối đa 50 MB mỗi tệp");
    }

    private void saveResponseToFile() {
        if (latestResponse == null || latestResponse.bodyBytes.length == 0) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu nội dung nhận từ HTTP server");
        String name = "http-response";
        try {
            String path = java.net.URI.create(urlField.getText().trim()).getPath();
            if (path != null && !path.endsWith("/") && !path.isEmpty()) {
                name = Path.of(path).getFileName().toString();
            }
        } catch (Exception ignored) {
            name = "http-response";
        }
        chooser.setInitialFileName(name);
        File destination = chooser.showSaveDialog(stage);
        if (destination == null) {
            return;
        }
        try {
            Files.write(destination.toPath(), latestResponse.bodyBytes);
            showInfo("Đã lưu response", destination.getAbsolutePath());
        } catch (Exception e) {
            showInfo("Không thể lưu response", e.getMessage());
        }
    }

    private void displayResponse(HttpClientCore.Response response, long elapsedMillis,
            String method, String requestSummary) {
        latestResponse = response;
        statusBadge.setText(response.statusLine);
        boolean successful = response.statusCode >= 200 && response.statusCode < 300;
        statusBadge.setStyle(badgeStyle(successful ? "#e7f8ef" : "#fff0ed",
                successful ? "#14804a" : "#bf3f32"));
        String contentType = response.headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase("Content-Type"))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse("unknown content type");
        responseMeta.setText(String.format(Locale.ROOT, "%d ms  ·  %s  ·  %s  ·  %d headers",
                elapsedMillis, formatBytes(response.bodyBytes.length), contentType,
                response.headers.size()));
        StringBuilder formattedHeaders = new StringBuilder();
        if (requestSummary != null) {
            formattedHeaders.append("HTTP REQUEST\n").append(requestSummary)
                    .append("\n\n");
        }
        formattedHeaders.append("HTTP RESPONSE\n").append(response.statusLine).append('\n');
        for (Map.Entry<String, List<String>> entry : response.headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                formattedHeaders.append(entry.getKey()).append(": ").append(value).append('\n');
            }
        }
        headerArea.setText(formattedHeaders.toString());
        String normalizedContentType = contentType.toLowerCase(Locale.ROOT);
        if ("HEAD".equals(selectedHttpMethod)) {
            htmlAnalysisLabel.setText("HEAD RESOURCE · " + response.statusLine
                    + "  ·  Content-Type: " + headerValue(response.headers, "Content-Type")
                    + "  ·  Content-Length: " + headerValue(response.headers, "Content-Length")
                    + "  ·  Last-Modified: " + headerValue(response.headers, "Last-Modified")
                    + "  ·  Server: " + headerValue(response.headers, "Server")
                    + "  ·  ETag: " + headerValue(response.headers, "ETag")
                    + "  ·  response không có body. Headers đầy đủ ở tab HTTP headers.");
        } else if (isHtmlResponse(normalizedContentType, response.body)) {
            htmlAnalysisLabel.setText(analyzeHtml(response.body, response.bodyBytes.length));
        } else {
            htmlAnalysisLabel.setText("Response không phải HTML · " + contentType
                    + " · " + formatBytes(response.bodyBytes.length));
        }
        boolean textual = normalizedContentType.startsWith("text/")
                || normalizedContentType.contains("json")
                || normalizedContentType.contains("xml");
        boolean withinPreviewLimit = response.bodyBytes.length <= 1024 * 1024;
        if (response.body.isEmpty()) {
            bodyArea.clear();
            lastClientLoadedUrl = urlField.getText();
            webView.getEngine().loadContent("<html><body style='font-family: sans-serif;color:#77809a'>"
                    + "Máy chủ không gửi nội dung trang.</body></html>");
        } else if (!textual || !withinPreviewLimit) {
            bodyArea.setText("Nội dung nhị phân hoặc lớn được ẩn khỏi phần xem trước ("
                    + formatBytes(response.bodyBytes.length)
                    + "). Nhấn “Lưu response” để tải nguyên vẹn tệp về máy.");
            lastClientLoadedUrl = urlField.getText();
            webView.getEngine().loadContent("<html><body style='font-family:sans-serif;"
                    + "color:#77809a;padding:24px'>Nội dung này không phù hợp để xem trực tiếp. "
                    + "Dùng nút “Lưu response” để tải tệp về máy.</body></html>");
        } else {
            bodyArea.setText(response.body);
            lastClientLoadedUrl = urlField.getText();
            if (isHtmlResponse(normalizedContentType, response.body)) {
                webView.getEngine().loadContent(
                        addBaseUrl(response.body, urlField.getText()), "text/html");
            } else {
                webView.getEngine().loadContent("<html><body><pre style='white-space:pre-wrap'>"
                        + escapeHtml(response.body) + "</pre></body></html>");
            }
        }
    }

    private String addBaseUrl(String html, String pageUrl) {
        try {
            java.net.URI uri = java.net.URI.create(pageUrl);
            String path = uri.getPath();
            int lastSlash = path == null ? -1 : path.lastIndexOf('/');
            String basePath = lastSlash < 0 ? "/" : path.substring(0, lastSlash + 1);
            String base = new java.net.URI(uri.getScheme(), uri.getAuthority(), basePath,
                    null, null).toString();
            String baseTag = "<base href=\"" + escapeHtml(base) + "\">";
            java.util.regex.Matcher head = java.util.regex.Pattern.compile(
                    "(?i)<head(?:\\s[^>]*)?>").matcher(html);
            if (head.find()) {
                return html.substring(0, head.end()) + baseTag + html.substring(head.end());
            }
            return baseTag + html;
        } catch (Exception e) {
            return html;
        }
    }

    private boolean isHtmlResponse(String contentType, String body) {
        if (contentType.contains("text/html") || contentType.contains("application/xhtml+xml")) {
            return true;
        }
        return body != null && java.util.regex.Pattern.compile(
                "(?is)<\\s*(?:!doctype\\s+html|html|head|body|p|div|span|img)(?:\\s|/?>)")
                .matcher(body).find();
    }

    private String analyzeHtml(String html, int byteLength) {
        Map<String, Integer> tagCounts = countHtmlTags(html);
        return String.format(Locale.ROOT,
                "HTML RESPONSE  ·  %s nhận được · %d ký tự UTF-16 sau giải mã  ·  <p> %d  ·  <div> %d"
                        + "  ·  <span> %d  ·  <img> %d",
                formatBytes(byteLength), html.length(), tagCounts.get("p"),
                tagCounts.get("div"), tagCounts.get("span"), tagCounts.get("img"));
    }

    private String headerValue(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse("không có");
    }

    private Map<String, Integer> countHtmlTags(String html) {
        Map<String, Integer> tagCounts = new java.util.LinkedHashMap<>();
        for (String tagName : List.of("p", "div", "span", "img")) {
            tagCounts.put(tagName, 0);
        }
        HTMLEditorKit.ParserCallback callback = new HTMLEditorKit.ParserCallback() {
            @Override
            public void handleStartTag(HTML.Tag tag,
                    javax.swing.text.MutableAttributeSet attributes, int position) {
                addTag(tag);
            }

            @Override
            public void handleSimpleTag(HTML.Tag tag,
                    javax.swing.text.MutableAttributeSet attributes, int position) {
                addTag(tag);
            }

            private void addTag(HTML.Tag tag) {
                String tagName = tag.toString().toLowerCase(Locale.ROOT);
                if (tagCounts.containsKey(tagName)) {
                    tagCounts.compute(tagName, (name, count) -> count + 1);
                }
            }
        };
        try {
            new ParserDelegator().parse(new StringReader(html), callback, true);
        } catch (IOException e) {
            throw new IllegalStateException("Không thể phân tích response HTML.", e);
        }
        return tagCounts;
    }

    private String formatRequestSummary(String method, String requestUrl,
            Map<String, String> headers, byte[] body) {
        java.net.URI uri = java.net.URI.create(requestUrl);
        String target = uri.getRawPath();
        if (target == null || target.isEmpty()) {
            target = "/";
        }
        if (uri.getRawQuery() != null) {
            target += "?" + uri.getRawQuery();
        }
        StringBuilder summary = new StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(uri.getRawAuthority()).append("\r\n")
                .append("Accept: */*\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            String value = "authorization".equalsIgnoreCase(header.getKey())
                    ? "Bearer [REDACTED]" : header.getValue();
            summary.append(header.getKey()).append(": ").append(value).append("\r\n");
        }
        if (body != null) {
            summary.append("Content-Length: ").append(body.length).append("\r\n");
        }
        summary.append("\r\n");
        if (body != null) {
            summary.append(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }
        return summary.toString();
    }

    private void showResponseError(String message) {
        latestResponse = null;
        if (saveResponseButton != null) {
            saveResponseButton.setDisable(true);
        }
        statusBadge.setText("KHÔNG KẾT NỐI ĐƯỢC");
        statusBadge.setStyle(badgeStyle("#fff0ed", "#bf3f32"));
        String detail = message == null
                ? "Không thể kết nối máy chủ. Kiểm tra IP, cổng và kết nối mạng."
                : "Không thể kết nối: " + message
                        + "\n\nKiểm tra máy chủ đang chạy, hai máy cùng Wi-Fi/LAN, "
                        + "đúng IP/cổng và Windows Firewall cho phép kết nối.";
        responseMeta.setText(detail);
        if (htmlAnalysisLabel != null) {
            htmlAnalysisLabel.setText("Chưa có response HTTP để phân tích.");
        }
        bodyArea.setText(detail);
        headerArea.clear();
        webView.getEngine().loadContent("<html><body style='font-family: sans-serif;color:#bf3f32'>"
                + escapeHtml(detail) + "</body></html>");
    }

    private void showServerError(String message) {
        serverState.setText("●  ERROR");
        serverState.setStyle(stateStyle(false));
        appendLog("ERROR  " + message);
        statusBadge.setText("LỖI MÁY CHỦ");
        statusBadge.setStyle(badgeStyle("#fff0ed", "#bf3f32"));
    }

    private void addRequest(HttpServerCore.RequestInfo info) {
        requests.add(0, info);
        totalRequests++;
        if (info.statusCode >= 200 && info.statusCode < 300) {
            successfulRequests++;
        }
        requestTimes.add(System.currentTimeMillis());
        if (requests.size() > 1000) {
            requests.remove(requests.size() - 1);
        }
        if (requestTimes.size() > 1000) {
            requestTimes.remove(0);
        }
        totalBytes += info.bytes;
        totalLatency += info.durationMillis;
        updateStatistics();
        if (("POST".equals(info.method) || "PUT".equals(info.method)
                || "DELETE".equals(info.method)) && info.statusCode >= 200
                && info.statusCode < 300) {
            refreshEndpoints();
        }
        if (requestTable != null) {
            requestTable.refresh();
        }
        updateRequestFilterSummary();
    }

    private void updateStatistics() {
        requestCountLabel.setText(String.valueOf(totalRequests));
        long now = System.currentTimeMillis();
        long recent = requestTimes.stream().filter(time -> now - time <= 5000).count();
        rateLabel.setText(String.format(Locale.ROOT, "%.1f req/s", recent / 5.0));
        transferLabel.setText(formatBytes(totalBytes));
        latencyLabel.setText(totalRequests == 0 ? "0 ms"
                : String.format(Locale.ROOT, "%.0f ms", (double) totalLatency / totalRequests));
        double ratio = totalRequests == 0 ? 0 : (double) successfulRequests / totalRequests;
        successLabel.setText(String.format(Locale.ROOT, "%.0f%%", ratio * 100));
    }

    private void appendLog(String message) {
        Platform.runLater(() -> {
            if (serverLog == null) {
                return;
            }
            serverLog.appendText("[" + LocalTime.now().format(LOG_TIME) + "]  " + message + "\n");
            int excess = serverLog.getLength() - 180_000;
            if (excess > 0) {
                serverLog.deleteText(0, excess);
            }
            serverLog.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void refreshEndpoints() {
        if (rootField == null) {
            return;
        }
        Path root;
        try {
            root = Path.of(rootField.getText().trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                endpoints.clear();
                return;
            }
            try (Stream<Path> files = Files.walk(root)) {
                List<EndpointInfo> found = files.filter(Files::isRegularFile)
                        .sorted(Comparator.naturalOrder())
                        .map(path -> new EndpointInfo(root.relativize(path).toString(),
                                "/" + root.relativize(path).toString().replace(File.separatorChar, '/'),
                                extensionDescription(fileExtension(path.getFileName().toString()))
                                        + " · " + detectType(path),
                                formatBytes(sizeOf(path)), modifiedTime(path)))
                        .collect(Collectors.toList());
                endpoints.setAll(found);
            }
        } catch (Exception e) {
            appendLog("Không thể tải danh sách endpoint: " + e.getMessage());
            endpoints.clear();
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0;
        }
    }

    private String detectType(Path path) {
        try {
            String type = Files.probeContentType(path);
            return type == null ? "application/octet-stream" : type;
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    private String modifiedTime(Path path) {
        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(java.time.LocalDateTime.ofInstant(
                            Files.getLastModifiedTime(path).toInstant(),
                            java.time.ZoneId.systemDefault()));
        } catch (Exception e) {
            return "Không rõ";
        }
    }

    private String fileExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 || dot == fileName.length() - 1
                ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String extensionDescription(String extension) {
        if (extension.isEmpty()) {
            return "Tệp không phần mở rộng";
        }
        switch (extension) {
            case "png": case "jpg": case "jpeg": case "gif": case "webp":
                return "Ảnh · ." + extension;
            case "pdf": return "Tài liệu PDF";
            case "doc": case "docx": case "txt": case "rtf":
                return "Tài liệu · ." + extension;
            case "xls": case "xlsx": case "csv":
                return "Bảng tính · ." + extension;
            case "zip": case "rar": case "7z":
                return "Tệp nén · ." + extension;
            case "mp3": case "wav": case "mp4": case "mov":
                return "Media · ." + extension;
            default:
                return "Tệp ." + extension;
        }
    }

    private int effectivePort(java.net.URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : 80;
    }

    private String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private TableView<HttpServerCore.RequestInfo> createRequestTable() {
        TableView<HttpServerCore.RequestInfo> table = new TableView<>();
        table.setItems(requests);
        table.getColumns().setAll(java.util.Arrays.asList(
                requestColumn("THỜI GIAN", 100, request -> request.time),
                methodColumn(),
                requestColumn("ĐƯỜNG DẪN HTTP", 250, request -> request.path),
                statusColumn(),
                requestColumn("ĐỘ TRỄ", 100, request -> request.durationMillis + " ms"),
                requestColumn("DỮ LIỆU", 100, request -> formatBytes(request.bytes)),
                requestColumn("CLIENT IP", 145, request -> displayClientAddress(request.clientAddress))));
        table.setPlaceholder(new Label("Chưa có máy khách nào kết nối."));
        table.setRowFactory(view -> new javafx.scene.control.TableRow<HttpServerCore.RequestInfo>() {
            @Override
            protected void updateItem(HttpServerCore.RequestInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else {
                    setStyle(getIndex() % 2 == 0
                            ? "-fx-background-color: #ffffff; -fx-border-color: transparent transparent #edf1f6 transparent;"
                            : "-fx-background-color: #f8fafd; -fx-border-color: transparent transparent #edf1f6 transparent;");
                }
            }
        });
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setFixedCellSize(40);
        table.setStyle("-fx-background-color: white; -fx-background-radius: 9; "
                + "-fx-border-color: #e5eaf1; -fx-border-radius: 9; "
                + "-fx-table-cell-border-color: #edf1f5; -fx-selection-bar: #dbeafe; "
                + "-fx-selection-bar-non-focused: #e8eef7; -fx-font-size: 12px;");
        return table;
    }

    private TableColumn<HttpServerCore.RequestInfo, String> methodColumn() {
        TableColumn<HttpServerCore.RequestInfo, String> column = new TableColumn<>();
        setColumnHeader(column, "METHOD", "M5 3h14v3H5z M10 8h4v8h5l-7 7-7-7h5z");
        column.setPrefWidth(105);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().method));
        column.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String method, boolean empty) {
                super.updateItem(method, empty);
                if (empty || method == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                String color = "GET".equals(method) ? "#2563eb"
                        : "POST".equals(method) ? "#7c3aed"
                        : "HEAD".equals(method) ? "#b45309" : "#475569";
                String background = "GET".equals(method) ? "#eff6ff"
                        : "POST".equals(method) ? "#f5f3ff"
                        : "HEAD".equals(method) ? "#fffbeb" : "#f1f5f9";
                Label badge = new Label(method);
                badge.setStyle("-fx-background-color: " + background + "; -fx-text-fill: " + color
                        + "; -fx-padding: 4 9; -fx-background-radius: 6; -fx-font-size: 10px; "
                        + "-fx-font-weight: bold; -fx-font-family: 'Segoe UI';");
                setGraphic(badge);
                setText(null);
                setAlignment(Pos.CENTER_LEFT);
            }
        });
        return column;
    }

    private TableColumn<HttpServerCore.RequestInfo, String> statusColumn() {
        TableColumn<HttpServerCore.RequestInfo, String> column = new TableColumn<>();
        setColumnHeader(column, "STATUS", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-1 14-4-4 2-2 2 2 5-5 2 2z");
        column.setPrefWidth(125);
        column.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(
                cell.getValue().statusCode + " " + reasonPhrase(cell.getValue().statusCode)));
        column.setCellFactory(ignored -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                int code;
                try {
                    code = Integer.parseInt(status.substring(0, 3));
                } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
                    code = 0;
                }
                boolean good = code >= 200 && code < 400;
                Label badge = new Label(status);
                badge.setStyle("-fx-background-color: " + (good ? "#ecfdf3" : "#fff1f2")
                        + "; -fx-text-fill: " + (good ? "#15803d" : "#be123c")
                        + "; -fx-padding: 4 9; -fx-background-radius: 6; -fx-font-size: 10px; "
                        + "-fx-font-weight: bold; -fx-font-family: 'Segoe UI';");
                setGraphic(badge);
                setText(null);
                setAlignment(Pos.CENTER_LEFT);
            }
        });
        return column;
    }

    private TableColumn<HttpServerCore.RequestInfo, String> requestColumn(String title, double width,
            java.util.function.Function<HttpServerCore.RequestInfo, String> getter) {
        TableColumn<HttpServerCore.RequestInfo, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        setColumnHeader(column, title, columnIcon(title));
        column.setCellValueFactory(cell ->
                new javafx.beans.property.ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return column;
    }

    private String displayClientAddress(String address) {
        if (address == null || address.isEmpty()) {
            return "Không xác định";
        }
        String normalized = address.toLowerCase(Locale.ROOT);
        if ("::1".equals(normalized) || "0:0:0:0:0:0:0:1".equals(normalized)
                || "127.0.0.1".equals(normalized)) {
            return "127.0.0.1";
        }
        if (normalized.startsWith("::ffff:")) {
            return address.substring(address.lastIndexOf(':') + 1);
        }
        return address;
    }

    private TableView<ClientActivity> createClientActivityTable() {
        clientActivityTable = new TableView<>(clientActivities);
        clientActivityTable.getColumns().setAll(java.util.Arrays.asList(
                clientActivityColumn("THỜI GIAN", 100, activity -> activity.time),
                clientActivityColumn("METHOD", 90, activity -> activity.method),
                clientActivityColumn("URL / TÀI NGUYÊN", 360, activity -> activity.target),
                clientActivityColumn("STATUS", 140, activity -> activity.status),
                clientActivityColumn("THỜI GIAN XỬ LÝ", 130,
                        activity -> activity.durationMillis + " ms"),
                clientActivityColumn("DỮ LIỆU", 110, activity -> formatBytes(activity.bytes))));
        clientActivityTable.setPlaceholder(new Label("Chưa có giao dịch HTTP từ Client."));
        clientActivityTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        clientActivityTable.setFixedCellSize(40);
        clientActivityTable.setStyle("-fx-background-color: white; -fx-background-radius: 9; "
                + "-fx-border-color: #e5eaf1; -fx-border-radius: 9; "
                + "-fx-table-cell-border-color: #edf1f5; -fx-selection-bar: #dbeafe; "
                + "-fx-selection-bar-non-focused: #e8eef7;");
        return clientActivityTable;
    }

    private TableColumn<ClientActivity, String> clientActivityColumn(String title, double width,
            java.util.function.Function<ClientActivity, String> getter) {
        TableColumn<ClientActivity, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        setColumnHeader(column, title, columnIcon(title));
        column.setCellValueFactory(cell ->
                new javafx.beans.property.ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return column;
    }

    private void addClientActivity(String method, String target, int statusCode,
            long durationMillis, long bytes) {
        ClientActivity activity = new ClientActivity(
                LocalTime.now().format(LOG_TIME), method, target,
                statusCode == 0 ? "LỖI KẾT NỐI"
                        : statusCode + (reasonPhrase(statusCode).isEmpty()
                                ? "" : " " + reasonPhrase(statusCode)),
                durationMillis, bytes);
        clientActivities.add(0, activity);
        if (clientActivities.size() > 1000) {
            clientActivities.remove(clientActivities.size() - 1);
        }
        if (clientActivityTable != null) {
            clientActivityTable.refresh();
        }
    }

    private TableColumn<EndpointInfo, String> endpointColumn(String title, double width,
            java.util.function.Function<EndpointInfo, String> getter) {
        TableColumn<EndpointInfo, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        setColumnHeader(column, title, columnIcon(title));
        column.setCellValueFactory(cell ->
                new javafx.beans.property.ReadOnlyStringWrapper(getter.apply(cell.getValue())));
        return column;
    }

    private Tab createTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private Tab createIconTab(String title, String path, Node content) {
        StackPane icon = new StackPane(svgIcon(path, 15, "#16728b"));
        icon.setMinSize(25, 25);
        icon.setPrefSize(25, 25);
        icon.setMaxSize(25, 25);
        icon.setStyle("-fx-background-color: #e8f4f7; -fx-background-radius: 7;");
        Tab tab = new Tab(title, content);
        tab.setGraphic(icon);
        tab.setClosable(false);
        return tab;
    }

    private Node svgIcon(String path, double size, String color) {
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        shape.setFill(javafx.scene.paint.Color.web(color));
        shape.setScaleX(size / 24.0);
        shape.setScaleY(size / 24.0);
        return shape;
    }

    private String methodIconPath(String method) {
        if ("POST".equals(method)) {
            return "M10 22h4v-9h5l-7-8-7 8h5z";
        }
        if ("HEAD".equals(method)) {
            return "M12 4C6 4 2 12 2 12s4 8 10 8 10-8 10-8-4-8-10-8zm0 13a5 5 0 1 1 0-10 5 5 0 0 1 0 10zm0-3a2 2 0 1 0 0-4 2 2 0 0 0 0 4z";
        }
        return "M10 2h4v9h5l-7 8-7-8h5z";
    }

    private ToggleButton methodToggle(String method, ToggleGroup group) {
        ToggleButton button = new ToggleButton(method);
        button.setToggleGroup(group);
        button.setGraphic(svgIcon(methodIconPath(method), 16, methodColor(method)));
        button.setGraphicTextGap(8);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMinWidth(105);
        button.setPrefHeight(42);
        button.setTooltip(new javafx.scene.control.Tooltip(
                "GET tải nội dung; POST gửi dữ liệu; HEAD lấy metadata, không tải body."));
        button.setAccessibleText("Phương thức HTTP " + method);
        return button;
    }

    private void styleMethodButton(ToggleButton button, String selectedMethod) {
        boolean selected = button.getText().equals(selectedMethod);
        String accent = methodColor(button.getText());
        String background = selected ? accent : "#f8fafc";
        String foreground = selected ? "#ffffff" : "#475569";
        button.setGraphic(svgIcon(methodIconPath(button.getText()), 16,
                selected ? "#ffffff" : accent));
        button.setTextFill(javafx.scene.paint.Color.web(foreground));
        button.setStyle("-fx-background-color: " + background + "; "
                + "-fx-border-color: " + (selected ? accent : "#dbe3ed")
                + "; -fx-border-width: 1; -fx-border-radius: 10; "
                + "-fx-background-radius: 10; -fx-font-family: 'Segoe UI'; "
                + "-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 10 15; "
                + "-fx-cursor: hand;"
                + (selected ? " -fx-effect: dropshadow(gaussian, rgba(37,99,235,0.18), 8, 0, 0, 2);" : ""));
    }

    private String methodColor(String method) {
        if ("POST".equals(method)) {
            return "#7c3aed";
        }
        if ("HEAD".equals(method)) {
            return "#0891b2";
        }
        return "#2563eb";
    }

    private void setColumnHeader(TableColumn<?, ?> column, String title, String path) {
        Label label = new Label(title);
        label.setStyle("-fx-text-fill: #475569; -fx-font-size: 10px; -fx-font-weight: bold;");
        HBox graphic = new HBox(7, svgIcon(path, 12, "#64748b"), label);
        graphic.setAlignment(Pos.CENTER_LEFT);
        column.setText("");
        column.setGraphic(graphic);
    }

    private String columnIcon(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        if (normalized.contains("thời gian") || normalized.contains("sửa đổi")) {
            return "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 5v5l4 2-1 2-5-3V7z";
        }
        if (normalized.contains("method")) {
            return "M5 3h14v3H5z M10 8h4v8h5l-7 7-7-7h5z";
        }
        if (normalized.contains("status")) {
            return "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-1 14-4-4 2-2 2 2 5-5 2 2z";
        }
        if (normalized.contains("đường dẫn") || normalized.contains("url")) {
            return "M10 13a5 5 0 0 0 7.1 0l3-3A5 5 0 0 0 13 2.9l-1.7 1.7 1.4 1.4 1.7-1.7a3 3 0 0 1 4.3 4.3l-3 3a3 3 0 0 1-4.3 0z M14 11a5 5 0 0 0-7.1 0l-3 3a5 5 0 0 0 7.1 7.1l1.7-1.7-1.4-1.4-1.7 1.7a3 3 0 0 1-4.3-4.3l3-3a3 3 0 0 1 4.3 0z";
        }
        if (normalized.contains("dung lượng") || normalized.contains("dữ liệu")) {
            return "M4 4h16v16H4z M7 7h10v2H7z M7 11h10v2H7z M7 15h6v2H7z";
        }
        if (normalized.contains("client ip")) {
            return "M12 2a7 7 0 0 0-7 7c0 5 7 13 7 13s7-8 7-13a7 7 0 0 0-7-7zm0 10a3 3 0 1 1 0-6 3 3 0 0 1 0 6z";
        }
        if (normalized.contains("mime") || normalized.contains("định dạng")) {
            return "M6 2h8l5 5v15H6z M13 3v5h5 M9 12h7 M9 16h7";
        }
        return "M4 4h16v16H4z M8 8h8v2H8z M8 12h8v2H8z";
    }

    private void applyProfessionalTheme() {
        if (stage == null || stage.getScene() == null) {
            return;
        }
        for (TableView<?> table : new TableView<?>[] {
                requestTable, clientActivityTable, endpointTable }) {
            if (table == null) {
                continue;
            }
            table.applyCss();
            Node header = table.lookup(".column-header-background");
            if (header != null) {
                header.setStyle("-fx-background-color: #f3f6fa; "
                        + "-fx-border-color: transparent transparent #e5eaf1 transparent;");
            }
            for (Node columnHeader : table.lookupAll(".column-header")) {
                columnHeader.setStyle("-fx-background-color: #f3f6fa; "
                        + "-fx-size: 38px; -fx-border-color: transparent #e8edf3 #e5eaf1 transparent;");
                for (Node label : columnHeader.lookupAll(".label")) {
                    label.setStyle("-fx-text-fill: #526176; -fx-font-size: 10px; "
                            + "-fx-font-weight: bold; -fx-padding: 0 8 0 8;");
                }
            }
            for (Node cell : table.lookupAll(".table-cell")) {
                cell.setStyle("-fx-font-family: 'Segoe UI'; -fx-font-size: 12px; "
                        + "-fx-text-fill: #243247; -fx-padding: 0 10 0 10;");
            }
        }
        for (Node field : stage.getScene().getRoot().lookupAll(".text-field")) {
            field.setStyle("-fx-background-color: #ffffff; -fx-text-fill: #172033; "
                    + "-fx-prompt-text-fill: #94a3b8; -fx-border-color: #d8e1ec; "
                    + "-fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 9 11; "
                    + "-fx-font-size: 12px;");
        }
        for (Node combo : stage.getScene().getRoot().lookupAll(".combo-box-base")) {
            combo.setStyle("-fx-background-color: #ffffff; -fx-border-color: #d8e1ec; "
                    + "-fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 3 7;");
        }
        for (Node background : stage.getScene().getRoot().lookupAll(".tab-header-background")) {
            background.setStyle("-fx-background-color: #eef2f7; "
                    + "-fx-border-color: transparent transparent #dbe3ed transparent;");
        }
        for (Node contentArea : stage.getScene().getRoot().lookupAll(".tab-content-area")) {
            contentArea.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        }
        for (Node pane : stage.getScene().getRoot().lookupAll(".tab-pane")) {
            pane.setStyle("-fx-background-color: transparent;");
        }
        for (Node pane : stage.getScene().getRoot().lookupAll(".titled-pane")) {
            Node title = pane.lookup(".title");
            if (title != null) {
                title.setStyle("-fx-background-color: #f8fafc; -fx-background-radius: 9; "
                        + "-fx-border-color: #e2e8f0; -fx-border-radius: 9; -fx-padding: 10 12;");
            }
            Node paneContent = pane.lookup(".content");
            if (paneContent != null) {
                paneContent.setStyle("-fx-background-color: white; "
                        + "-fx-border-color: #e2e8f0; -fx-border-radius: 0 0 9 9;");
            }
        }
        for (Node paneNode : stage.getScene().getRoot().lookupAll(".tab-pane")) {
            if (!(paneNode instanceof TabPane)) {
                continue;
            }
            TabPane tabs = (TabPane) paneNode;
            Runnable styleTabs = () -> {
                for (Node tabNode : tabs.lookupAll(".tab")) {
                    boolean selected = tabNode.getPseudoClassStates().stream()
                            .anyMatch(state -> "selected".equals(state.getPseudoClassName()));
                    tabNode.setStyle("-fx-background-color: " + (selected ? "white" : "#f3f6fa")
                            + "; -fx-background-radius: 9 9 0 0; -fx-padding: 8 13 8 13; "
                            + "-fx-border-color: transparent transparent "
                            + (selected ? "#2563eb" : "transparent") + " transparent; "
                            + "-fx-border-width: 0 0 2 0;");
                }
            };
            tabs.getSelectionModel().selectedIndexProperty().addListener(
                    (observable, previous, selected) -> Platform.runLater(styleTabs));
            styleTabs.run();
        }
    }

    private void selectTab(int index) {
        if (mainTabs != null) {
            mainTabs.getSelectionModel().select(index);
        }
    }

    private VBox pageTitle(String title, String subtitle) {
        Label heading = new Label(title);
        heading.setFont(Font.font("Segoe UI", FontWeight.BOLD, 21));
        heading.setStyle("-fx-text-fill: #0f172a;");
        Label description = new Label(subtitle);
        description.setStyle("-fx-text-fill: #64748b; -fx-font-size: 12px;");
        return new VBox(6, heading, description);
    }

    private Label urlInfoValue() {
        Label value = new Label("—");
        value.setWrapText(true);
        value.setMinHeight(20);
        value.setStyle("-fx-text-fill: #172033; -fx-font-family: 'Consolas'; "
                + "-fx-font-size: 11px; -fx-font-weight: bold;");
        return value;
    }

    private VBox urlInfoItem(String title, Label value) {
        Label caption = new Label(title);
        caption.setStyle("-fx-text-fill: #64748b; -fx-font-size: 9px; "
                + "-fx-font-weight: bold;");
        VBox item = new VBox(4, caption, value);
        item.setPadding(new Insets(7, 8, 7, 8));
        item.setStyle("-fx-background-color: white; -fx-background-radius: 7; "
                + "-fx-border-color: #e2e8f0; -fx-border-radius: 7;");
        item.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(item, Priority.ALWAYS);
        return item;
    }

    private Label sectionTitle(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        label.setStyle("-fx-text-fill: #334155; -fx-letter-spacing: 0.5px;");
        return label;
    }

    private Label fieldCaption(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px; -fx-font-weight: bold;");
        return label;
    }

    private Label statValue(String text) {
        Label label = new Label(text);
        label.setFont(Font.font("Segoe UI", FontWeight.BOLD, 19));
        label.setStyle("-fx-text-fill: #172b3a;");
        return label;
    }

    private VBox metricCard(String title, Label value) {
        Label caption = new Label(title);
        caption.setStyle("-fx-text-fill: #64748b; -fx-font-size: 11px; "
                + "-fx-font-weight: bold;");
        VBox card = new VBox(6, caption, value);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setMinWidth(90);
        card.setStyle("-fx-background-color: white; -fx-background-radius: 12; "
                + "-fx-border-color: #e2e8f0; -fx-border-radius: 12; "
                + "-fx-effect: dropshadow(gaussian, rgba(15,23,42,0.045), 10, 0, 0, 3);");
        return card;
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: linear-gradient(to bottom right, #2563eb, #1d4ed8); "
                + "-fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 9; "
                + "-fx-padding: 11 16; -fx-cursor: hand; "
                + "-fx-effect: dropshadow(gaussian, rgba(37,99,235,0.20), 8, 0, 0, 2);");
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #ffffff; -fx-text-fill: #334155; "
                + "-fx-border-color: #dbe3ee; -fx-border-radius: 8; "
                + "-fx-background-radius: 8; -fx-padding: 9 13; -fx-cursor: hand; "
                + "-fx-font-weight: bold;");
        return button;
    }

    private Button dangerButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #fff1f2; -fx-text-fill: #be123c; "
                + "-fx-border-color: #fecdd3; -fx-border-radius: 8; "
                + "-fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 10 15; "
                + "-fx-cursor: hand;");
        return button;
    }

    private TextArea readOnlyTextArea() {
        TextArea area = new TextArea();
        area.setEditable(false);
        area.setWrapText(false);
        area.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");
        return area;
    }

    private String stateStyle(boolean running) {
        return "-fx-background-color: " + (running ? "#ecfdf3" : "#fff1f2")
                + "; -fx-text-fill: " + (running ? "#15803d" : "#be123c")
                + "; -fx-padding: 9 14; -fx-background-radius: 20; -fx-font-weight: bold;"
                + " -fx-border-color: " + (running ? "#bbf7d0" : "#fecdd3")
                + "; -fx-border-radius: 20;";
    }

    private String badgeStyle(String background, String foreground) {
        return "-fx-background-color: " + background + "; -fx-text-fill: " + foreground
                + "; -fx-padding: 7 11; -fx-background-radius: 7; -fx-font-weight: bold;";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    private String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 500: return "Server Error";
            default: return "";
        }
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class EndpointInfo {
        private final String fileName;
        private final String httpPath;
        private final String type;
        private final String size;
        private final String modified;

        private EndpointInfo(String fileName, String httpPath, String type, String size,
                String modified) {
            this.fileName = fileName;
            this.httpPath = httpPath;
            this.type = type;
            this.size = size;
            this.modified = modified;
        }

        private String getFileName() { return fileName; }
        private String getHttpPath() { return httpPath; }
        private String getType() { return type; }
        private String getSize() { return size; }
        private String getModified() { return modified; }
    }

    private static final class UploadOutcome {
        private final File file;
        private final HttpClientCore.Response response;
        private final String error;
        private final long durationMillis;

        private UploadOutcome(File file, HttpClientCore.Response response, String error,
                long durationMillis) {
            this.file = file;
            this.response = response;
            this.error = error;
            this.durationMillis = durationMillis;
        }
    }

    private static final class ClientActivity {
        private final String time;
        private final String method;
        private final String target;
        private final String status;
        private final long durationMillis;
        private final long bytes;

        private ClientActivity(String time, String method, String target, String status,
                long durationMillis, long bytes) {
            this.time = time;
            this.method = method;
            this.target = target;
            this.status = status;
            this.durationMillis = durationMillis;
            this.bytes = bytes;
        }
    }
}
