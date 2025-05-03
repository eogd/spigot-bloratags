package com.blorasoft.bloratags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.logging.Level;
public class Settings {

    private final BloraTags plugin;
    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration gui;
    private File configFile;
    private File messagesFile;
    private File guiFile;

    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacyAmpersand();

    private String prefix;
    private String defaultColorId;
    private boolean applyDefaultColor;
    private boolean shopEnabled;
    private boolean applicationsEnabled;
    private boolean notifyAdminOnJoin;
    private boolean notifyAdminOnSubmit;
    private String adminNotificationPermission;
    private String dbType, dbHost, dbName, dbUser, dbPass;
    private int dbPort, dbPoolSize, dbTimeout;
    private boolean dbUseSSL;


    public Settings(@NotNull BloraTags plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void loadConfigs() {
        configFile = new File(plugin.getDataFolder(), "config.yml");
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        guiFile = new File(plugin.getDataFolder(), "gui.yml");

        if (!plugin.getDataFolder().exists()) { plugin.getDataFolder().mkdirs(); }

        saveDefaultConfigResource("config.yml");
        saveDefaultConfigResource("messages.yml");
        saveDefaultConfigResource("gui.yml");
        saveDefaultConfigResource("tags.yml");
        saveDefaultConfigResource("colors.yml");
        saveDefaultConfigResource("gradients.yml");


        config = YamlConfiguration.loadConfiguration(configFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
        gui = YamlConfiguration.loadConfiguration(guiFile);

        cacheSettings();
    }

    private void saveDefaultConfigResource(@NotNull String resourcePath) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            plugin.getLogger().info("Creating default configuration file: " + resourcePath);
            try { plugin.saveResource(resourcePath, false); }
            catch (IllegalArgumentException e) { plugin.getLogger().severe("Failed to save default resource '" + resourcePath + "'."); }
        }
    }

    public void reloadConfigs() {
        try {
            config = YamlConfiguration.loadConfiguration(configFile);
            messages = YamlConfiguration.loadConfiguration(messagesFile);
            gui = YamlConfiguration.loadConfiguration(guiFile);
            cacheSettings();

            DataManager dataManager = plugin.getDataManager();
            if (dataManager != null) { dataManager.loadStaticData(); }
            else { plugin.getLogger().severe("DataManager null during config reload!"); }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "配置文件重载失败！", e);
        }
    }

    private void cacheSettings() {
        prefix = getRawMessage("prefix", "<dark_gray>[<aqua>BloraTags</aqua>]</dark_gray> ");
        defaultColorId = config.getString("defaults.color_id", null);
        applyDefaultColor = config.getBoolean("defaults.apply_default_color_if_none", true);
        shopEnabled = config.getBoolean("shop.enabled", false);
        applicationsEnabled = config.getBoolean("applications.enabled", false);
        notifyAdminOnJoin = config.getBoolean("applications.notifications.notify_admin_on_join", true);
        notifyAdminOnSubmit = config.getBoolean("applications.notifications.notify_admin_on_submit", true);
        adminNotificationPermission = config.getString("applications.notifications.permission", "bloratags.admin.notify");
        dbType = config.getString("database.type", "sqlite");
        dbHost = config.getString("database.host", "localhost");
        dbPort = config.getInt("database.port", 3306);
        dbName = config.getString("database.database", plugin.getDataFolder().getAbsolutePath() + File.separator + "bloratags.db");
        dbUser = config.getString("database.username", "user");
        dbPass = config.getString("database.password", "password");
        dbUseSSL = config.getBoolean("database.useSSL", false);
        dbPoolSize = config.getInt("database.pool-size", 10);
        dbTimeout = config.getInt("database.timeout", 5000);
        if (adminNotificationPermission == null || adminNotificationPermission.isEmpty()) { adminNotificationPermission = "bloratags.admin.notify"; }
        if (dbType == null || !(dbType.equalsIgnoreCase("sqlite") || dbType.equalsIgnoreCase("mysql"))) { dbType = "sqlite"; }
    }

    @NotNull public String getPrefixFormat() { return prefix; }
    @Nullable public String getDefaultColorId() { return defaultColorId; }
    public boolean shouldApplyDefaultColor() { return applyDefaultColor; }
    public boolean isShopEnabled() { return shopEnabled; }
    public boolean isApplicationsEnabled() { return applicationsEnabled; }
    public boolean isNotifyAdminOnJoin() { return notifyAdminOnJoin; }
    public boolean isNotifyAdminOnSubmit() { return notifyAdminOnSubmit; }
    @NotNull public String getAdminNotificationPermission() { return adminNotificationPermission; }
    @NotNull public String getDatabaseType() { return dbType; }
    @NotNull public String getDatabaseHost() { return dbHost; }
    public int getDatabasePort() { return dbPort; }
    @NotNull public String getDatabaseName() { return dbName; }
    @NotNull public String getDatabaseUsername() { return dbUser; }
    @NotNull public String getDatabasePassword() { return dbPass; }
    public boolean isDatabaseUseSSL() { return dbUseSSL; }
    public int getDatabasePoolSize() { return dbPoolSize; }
    public int getDatabaseTimeout() { return dbTimeout; }

    @NotNull public String getRawMessage(@NotNull String path, @NotNull String def) { String msg = messages.getString(path, def); return msg != null ? msg : def; }
    @NotNull public List<String> getStringList(@NotNull String path) { return messages.getStringList(path); }
    @Nullable public String getGuiString(@NotNull String path, @Nullable String def) { return gui.getString(path, def); }
    @NotNull public List<String> getGuiStringList(@NotNull String path) { return gui.getStringList(path); }
    public int getGuiInt(@NotNull String path, int def) { return gui.getInt(path, def); }
    @Nullable public ConfigurationSection getGuiSection(@NotNull String path) { return gui.getConfigurationSection(path); }

    public void sendMessage(@NotNull CommandSender target, @NotNull String path, @NotNull TagResolver... resolvers) {
        String messageFormat = messages.getString(path);
        if (messageFormat == null || messageFormat.isEmpty()) { messageFormat = "<red>Error: Message '" + path + "' not defined!</red>"; }

        String formatWithPapi = messageFormat;
        if (target instanceof Player player && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            formatWithPapi = PlaceholderAPI.setPlaceholders(player, messageFormat);
        }

        TagResolver prefixResolver = Placeholder.parsed("prefix", prefix);
        TagResolver combinedResolver = TagResolver.resolver(prefixResolver, TagResolver.resolver(resolvers));

        Component messageComponent;
        try { messageComponent = miniMessage.deserialize(formatWithPapi, combinedResolver); }
        catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to parse MiniMessage format for path '" + path + "': " + formatWithPapi, e);
            target.sendMessage(org.bukkit.ChatColor.RED + "[BloraTags] Error parsing message: " + path); return;
        }

        String legacyString = legacySerializer.serialize(messageComponent);
        target.sendMessage(legacyString);
    }
}