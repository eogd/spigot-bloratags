package com.blorasoft.bloratags;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

public final class BloraTags extends JavaPlugin {

    private Settings settings;
    private DatabaseManager databaseManager;
    private DataManager dataManager;
    private GuiHandler guiHandler;
    private CommandHandler commandHandler;
    private PluginListener pluginListener;
    private Integrations integrations;


    public record TagDefinition(
            @NotNull String id,
            @NotNull String display,
            @NotNull String description,
            @Nullable String permission,
            boolean buyable,
            double price,
            boolean requiresApplication,
            double applicationFee,
            boolean applicationRefund
    ) {
        public boolean hasPermission() {
            return permission != null && !permission.isBlank();
        }
    }

    public record TagColor(
            @NotNull String id,
            @NotNull String value,
            @Nullable String permission
    ) {
        public boolean hasPermission() {
            return permission != null && !permission.isBlank();
        }
    }

    public record TagGradient(
            @NotNull String id,
            @NotNull String startColorId,
            @NotNull String endColorId,
            @Nullable String permission
    ) {
        public boolean hasPermission() {
            return permission != null && !permission.isBlank();
        }
    }

    public record PlayerTagData(
            @NotNull UUID playerUuid,
            @Nullable String selectedTagId,
            @NotNull Set<String> ownedTagIds,
            @Nullable String selectedColorId,
            @Nullable String selectedGradientStartId,
            @Nullable String selectedGradientEndId
    ) {
        public PlayerTagData(@NotNull UUID playerUuid) {
            this(playerUuid, null, Set.of(), null, null, null);
        }

        public PlayerTagData withSelectedTagId(@Nullable String newSelectedTagId) {
            return new PlayerTagData(this.playerUuid, newSelectedTagId, this.ownedTagIds, this.selectedColorId, this.selectedGradientStartId, this.selectedGradientEndId);
        }

        public PlayerTagData withOwnedTagIds(@NotNull Set<String> newOwnedTagIds) {
            return new PlayerTagData(this.playerUuid, this.selectedTagId, newOwnedTagIds, this.selectedColorId, this.selectedGradientStartId, this.selectedGradientEndId);
        }

        public PlayerTagData withSelectedColorId(@Nullable String newSelectedColorId) {
            return new PlayerTagData(this.playerUuid, this.selectedTagId, this.ownedTagIds, newSelectedColorId, null, null);
        }

        public PlayerTagData withSelectedGradient(@Nullable String newStartId, @Nullable String newEndId) {
            return new PlayerTagData(this.playerUuid, this.selectedTagId, this.ownedTagIds, null, newStartId, newEndId);
        }

        public PlayerTagData withSelectedGradientStartId(@Nullable String newSelectedGradientStartId) {
            return new PlayerTagData(this.playerUuid, this.selectedTagId, this.ownedTagIds, null, newSelectedGradientStartId, this.selectedGradientEndId);
        }

        public PlayerTagData withSelectedGradientEndId(@Nullable String newSelectedGradientEndId) {
            return new PlayerTagData(this.playerUuid, this.selectedTagId, this.ownedTagIds, null, this.selectedGradientStartId, newSelectedGradientEndId);
        }

        public boolean ownsTag(@NotNull String tagId) {
            return ownedTagIds.contains(tagId.toLowerCase());
        }

        public boolean hasSelectedColor() {
            return selectedColorId != null;
        }

        public boolean hasSelectedGradient() {
            return selectedGradientStartId != null && selectedGradientEndId != null;
        }
    }


    public record TagApplication(
            long id,
            @NotNull UUID playerUuid,
            @NotNull String playerName,
            @NotNull String tagId,
            long applicationTimestamp
    ) {}

    @Override
    public void onEnable() {
        settings = new Settings(this);
        settings.loadConfigs();

        databaseManager = new DatabaseManager(this, settings);
        databaseManager.connect();

        dataManager = new DataManager(this, settings, databaseManager);

        integrations = new Integrations(this, settings, dataManager);
        integrations.initializeHooks();

        guiHandler = new GuiHandler(this, settings, dataManager);

        commandHandler = new CommandHandler(this, settings, dataManager, guiHandler);
        commandHandler.registerCommands();

        pluginListener = new PluginListener(this, dataManager, guiHandler);
        getServer().getPluginManager().registerEvents(pluginListener, this);

        getServer().getOnlinePlayers().forEach(player -> dataManager.loadPlayer(player.getUniqueId()));

        if (!databaseManager.isConnected()) {
            getLogger().severe("BloraTags enabled but FAILED TO CONNECT TO DATABASE. Most features will be unavailable.");
        } else {
            getLogger().info("BloraTags has been enabled!");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving data for online players...");
        if (dataManager != null) {
            getServer().getOnlinePlayers().forEach(player -> {
                dataManager.unloadPlayer(player.getUniqueId());
            });
        }

        try {
            getLogger().info("Waiting briefly for save tasks...");
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("Interrupted while waiting for save tasks.");
        }

        if (integrations != null) {
            integrations.shutdownHooks();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }

        getLogger().info("BloraTags has been disabled.");
    }

    public Settings getSettings() {
        return settings;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public DataManager getDataManager() {
        return dataManager;
    }

    public GuiHandler getGuiHandler() {
        return guiHandler;
    }

    public Integrations getIntegrations() {
        return integrations;
    }
}