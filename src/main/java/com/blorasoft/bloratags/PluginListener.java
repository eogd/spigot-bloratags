package com.blorasoft.bloratags;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public class PluginListener implements Listener {

    private final BloraTags plugin;
    private final DataManager dataManager;
    private final GuiHandler guiHandler;
    private final Settings settings;
    public PluginListener(@NotNull BloraTags plugin, @NotNull DataManager dataManager, @NotNull GuiHandler guiHandler) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.guiHandler = guiHandler;
        this.settings = plugin.getSettings();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        dataManager.loadPlayer(playerUuid);

        if (settings.isApplicationsEnabled() && settings.isNotifyAdminOnJoin() && player.hasPermission(settings.getAdminNotificationPermission())) {
            dataManager.getPendingApplicationsAsync().thenAcceptAsync((List<BloraTags.TagApplication> pendingList) -> {
                if (pendingList != null && !pendingList.isEmpty()) {
                    settings.sendMessage(player, "admin_notify_pending_join", Placeholder.unparsed("count", String.valueOf(pendingList.size())));
                }
            }, plugin.getDatabaseManager()::runSyncTask);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        dataManager.unloadPlayer(playerUuid);
        guiHandler.closeGui(playerUuid);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        guiHandler.handleClick(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID playerUuid = player.getUniqueId();
        if (event.getInventory().getHolder() instanceof GuiHandler && event.getInventory().getHolder() == this.guiHandler) {
            guiHandler.closeGui(playerUuid);
        }
    }
}