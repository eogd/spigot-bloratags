package com.blorasoft.bloratags;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;


public class DatabaseManager {

    private final BloraTags plugin;
    private final Settings settings;
    private HikariDataSource dataSource;
    private final ExecutorService executor;
    private final Gson gson;
    private static final String PLAYER_DATA_TABLE = "bloratags_playerdata";
    private static final String APPLICATIONS_TABLE = "bloratags_applications";
    private static final String COL_UUID = "uuid";
    private static final String COL_SELECTED_TAG = "selected_tag";
    private static final String COL_OWNED_TAGS = "owned_tags";
    private static final String COL_SELECTED_COLOR = "selected_color";
    private static final String COL_GRADIENT_START = "gradient_start";
    private static final String COL_GRADIENT_END = "gradient_end";
    private static final String COL_APP_ID = "id";
    private static final String COL_APP_PLAYER_UUID = "player_uuid";
    private static final String COL_APP_PLAYER_NAME = "player_name";
    private static final String COL_APP_TAG_ID = "tag_id";
    private static final String COL_APP_TIMESTAMP = "timestamp";


    public DatabaseManager(@NotNull BloraTags plugin, @NotNull Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.executor = Executors.newCachedThreadPool();
        this.gson = new Gson();
    }

    public void connect() {
        try {
            HikariConfig config = new HikariConfig();
            String dbTypeSetting = settings.getDatabaseType();

            if (dbTypeSetting == null) {
                plugin.getLogger().severe("Config error: 'database.type' is missing or invalid in config.yml! Defaulting to SQLite.");
                dbTypeSetting = "sqlite";
            }
            String dbType = dbTypeSetting.toLowerCase();

            if (dbType.equals("mysql")) {
                config.setDataSourceClassName("com.mysql.cj.jdbc.MysqlDataSource");
                config.addDataSourceProperty("serverName", settings.getDatabaseHost());
                config.addDataSourceProperty("portNumber", settings.getDatabasePort());
                config.addDataSourceProperty("databaseName", settings.getDatabaseName());
                config.addDataSourceProperty("user", settings.getDatabaseUsername());
                config.addDataSourceProperty("password", settings.getDatabasePassword());
                config.addDataSourceProperty("useSSL", String.valueOf(settings.isDatabaseUseSSL()));
                config.addDataSourceProperty("verifyServerCertificate", "false");
                config.addDataSourceProperty("autoReconnect", "true");
                config.addDataSourceProperty("characterEncoding", "utf8");
                config.addDataSourceProperty("useUnicode", "true");
            } else {
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
                String dbPath = plugin.getDataFolder().getAbsolutePath() + "/" + settings.getDatabaseName() + ".db";
                config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            }

            config.setMaximumPoolSize(settings.getDatabasePoolSize());
            config.setConnectionTimeout(settings.getDatabaseTimeout());

            dataSource = new HikariDataSource(config);
            plugin.getLogger().info("数据库连接池初始化成功 (" + dbType + ")。");

            initializeTablesAsync();

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "数据库连接失败！插件功能将受限。", e);
            dataSource = null;
        }
    }

    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("数据库连接池已关闭。");
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }


    private Connection getConnection() throws SQLException {
        if (!isConnected()) {
            throw new SQLException("数据库未连接。");
        }
        return dataSource.getConnection();
    }

    public ExecutorService getExecutor() {
        return executor;
    }


    public CompletableFuture<Void> runAsyncTask(@NotNull Runnable task) {
        if (executor.isShutdown()) {
            plugin.getLogger().warning("Attempted to run async task after DatabaseManager shutdown.");
            return CompletableFuture.failedFuture(new IllegalStateException("Executor is shutdown"));
        }
        return CompletableFuture.runAsync(task, executor);
    }

    public void runSyncTask(@NotNull Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }


    private void initializeTablesAsync() {
        if (!isConnected()) {
            plugin.getLogger().warning("Skipping table initialization because database is not connected.");
            return;
        }
        runAsyncTask(() -> {
            String dbType = settings.getDatabaseType().toLowerCase();
            String createPlayerDataSql = "CREATE TABLE IF NOT EXISTS " + PLAYER_DATA_TABLE + " ("
                    + COL_UUID + " VARCHAR(36) PRIMARY KEY NOT NULL, "
                    + COL_SELECTED_TAG + " VARCHAR(100), "
                    + COL_OWNED_TAGS + " TEXT, "
                    + COL_SELECTED_COLOR + " VARCHAR(100), "
                    + COL_GRADIENT_START + " VARCHAR(100), "
                    + COL_GRADIENT_END + " VARCHAR(100)"
                    + ");";

            String createAppSql = "CREATE TABLE IF NOT EXISTS " + APPLICATIONS_TABLE + " ("
                    + COL_APP_ID + (dbType.equals("mysql") ? " BIGINT AUTO_INCREMENT" : " INTEGER PRIMARY KEY AUTOINCREMENT") + ", "
                    + COL_APP_PLAYER_UUID + " VARCHAR(36) NOT NULL, "
                    + COL_APP_PLAYER_NAME + " VARCHAR(16) NOT NULL, "
                    + COL_APP_TAG_ID + " VARCHAR(100) NOT NULL, "
                    + COL_APP_TIMESTAMP + " BIGINT NOT NULL"
                    + ");";

            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(createPlayerDataSql);
                stmt.execute(createAppSql);
                plugin.getLogger().info("数据库表初始化完成。");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "创建数据库表时出错！", e);
            }
        });
    }

    public CompletableFuture<BloraTags.PlayerTagData> loadPlayerDataAsync(@NotNull UUID playerUuid) {
        if (!isConnected()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM " + PLAYER_DATA_TABLE + " WHERE " + COL_UUID + " = ?";
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerUuid.toString());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    String selectedTag = rs.getString(COL_SELECTED_TAG);
                    String ownedTagsJson = rs.getString(COL_OWNED_TAGS);
                    String selectedColor = rs.getString(COL_SELECTED_COLOR);
                    String gradientStart = rs.getString(COL_GRADIENT_START);
                    String gradientEnd = rs.getString(COL_GRADIENT_END);

                    Set<String> ownedTags = new HashSet<>();
                    if (ownedTagsJson != null && !ownedTagsJson.isEmpty()) {
                        Type setType = new TypeToken<Set<String>>() {}.getType();
                        try {
                            Set<String> deserialized = gson.fromJson(ownedTagsJson, setType);
                            if (deserialized != null) {
                                ownedTags = deserialized;
                            }
                        } catch (Exception jsonEx) {
                            plugin.getLogger().log(Level.WARNING, "无法解析玩家 " + playerUuid + " 的 owned_tags JSON: " + ownedTagsJson, jsonEx);
                        }
                    }

                    return new BloraTags.PlayerTagData(
                            playerUuid,
                            selectedTag,
                            ownedTags,
                            selectedColor,
                            gradientStart,
                            gradientEnd
                    );
                } else {
                    return null;
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "加载玩家 " + playerUuid + " 数据时出错", e);
                return null;
            }
        }, executor);
    }

    public CompletableFuture<Boolean> savePlayerDataAsync(@NotNull BloraTags.PlayerTagData data) {
        if (!isConnected()) return CompletableFuture.completedFuture(false);

        return CompletableFuture.supplyAsync(() -> {
            String dbType = settings.getDatabaseType().toLowerCase();
            String sql;
            if (dbType.equals("mysql")) {
                sql = "INSERT INTO " + PLAYER_DATA_TABLE + " (" + COL_UUID + ", " + COL_SELECTED_TAG + ", " + COL_OWNED_TAGS + ", " + COL_SELECTED_COLOR + ", " + COL_GRADIENT_START + ", " + COL_GRADIENT_END + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE "
                        + COL_SELECTED_TAG + " = VALUES(" + COL_SELECTED_TAG + "), "
                        + COL_OWNED_TAGS + " = VALUES(" + COL_OWNED_TAGS + "), "
                        + COL_SELECTED_COLOR + " = VALUES(" + COL_SELECTED_COLOR + "), "
                        + COL_GRADIENT_START + " = VALUES(" + COL_GRADIENT_START + "), "
                        + COL_GRADIENT_END + " = VALUES(" + COL_GRADIENT_END + ");";
            } else {
                sql = "INSERT OR REPLACE INTO " + PLAYER_DATA_TABLE + " (" + COL_UUID + ", " + COL_SELECTED_TAG + ", " + COL_OWNED_TAGS + ", " + COL_SELECTED_COLOR + ", " + COL_GRADIENT_START + ", " + COL_GRADIENT_END + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?);";
            }

            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, data.playerUuid().toString());
                pstmt.setString(2, data.selectedTagId());
                pstmt.setString(3, gson.toJson(data.ownedTagIds()));
                pstmt.setString(4, data.selectedColorId());
                pstmt.setString(5, data.selectedGradientStartId());
                pstmt.setString(6, data.selectedGradientEndId());

                int affectedRows = pstmt.executeUpdate();
                return affectedRows > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "保存玩家 " + data.playerUuid() + " 数据时出错", e);
                return false;
            }
        }, executor);
    }



    public CompletableFuture<List<BloraTags.TagApplication>> loadPendingApplicationsAsync() {
        if (!isConnected()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.supplyAsync(() -> {
            List<BloraTags.TagApplication> apps = new ArrayList<>();
            String sql = "SELECT * FROM " + APPLICATIONS_TABLE;
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {

                while (rs.next()) {
                    apps.add(new BloraTags.TagApplication(
                            rs.getLong(COL_APP_ID),
                            UUID.fromString(rs.getString(COL_APP_PLAYER_UUID)),
                            rs.getString(COL_APP_PLAYER_NAME),
                            rs.getString(COL_APP_TAG_ID),
                            rs.getLong(COL_APP_TIMESTAMP)
                    ));
                }
                return apps;
            } catch (SQLException | IllegalArgumentException e) {
                plugin.getLogger().log(Level.SEVERE, "加载待处理申请时出错", e);
                return null;
            }
        }, executor);
    }

    public CompletableFuture<BloraTags.TagApplication> saveNewApplicationAsync(@NotNull BloraTags.TagApplication app) {
        if (!isConnected()) return CompletableFuture.completedFuture(null);

        return CompletableFuture.supplyAsync(() -> {
            String sql = "INSERT INTO " + APPLICATIONS_TABLE + " ("
                    + COL_APP_PLAYER_UUID + ", " + COL_APP_PLAYER_NAME + ", " + COL_APP_TAG_ID + ", " + COL_APP_TIMESTAMP
                    + ") VALUES (?, ?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

                pstmt.setString(1, app.playerUuid().toString());
                pstmt.setString(2, app.playerName());
                pstmt.setString(3, app.tagId());
                pstmt.setLong(4, app.applicationTimestamp());

                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) return null;

                try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        long newId = generatedKeys.getLong(1);
                        return new BloraTags.TagApplication(newId, app.playerUuid(), app.playerName(), app.tagId(), app.applicationTimestamp());
                    } else {
                        plugin.getLogger().severe("保存新申请后无法获取生成的 ID！");
                        return null;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "保存新申请时出错", e);
                return null;
            }
        }, executor);
    }

    public CompletableFuture<Boolean> deleteApplicationAsync(long applicationId) {
        if (!isConnected()) return CompletableFuture.completedFuture(false);

        return CompletableFuture.supplyAsync(() -> {
            String sql = "DELETE FROM " + APPLICATIONS_TABLE + " WHERE " + COL_APP_ID + " = ?";
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, applicationId);
                int affectedRows = pstmt.executeUpdate();
                return affectedRows > 0;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "删除申请 #" + applicationId + " 时出错", e);
                return false;
            }
        }, executor);
    }
}