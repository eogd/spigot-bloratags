package com.blorasoft.bloratags;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

/**
 * Handles integration with Vault (Economy) and PlaceholderAPI.
 */
public class Integrations {

    private final BloraTags plugin;
    private final Settings settings;
    private final DataManager dataManager;

    private Economy vaultEconomy = null;
    private boolean vaultHooked = false;

    private BloraTagsExpansion papiExpansion = null;
    private boolean papiHooked = false;

    public Integrations(@NotNull BloraTags plugin, @NotNull Settings settings, @NotNull DataManager dataManager) {
        this.plugin = plugin;
        this.settings = settings;
        this.dataManager = dataManager;
    }

    public void initializeHooks() {
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            if (setupEconomy()) {
                plugin.getLogger().info("Successfully hooked into Vault Economy.");
                vaultHooked = true;
            } else {
                plugin.getLogger().warning("Vault found but failed to hook into Economy service!");
                vaultHooked = false;
            }
        } else {
            plugin.getLogger().info("Vault plugin not found. Shop features will be disabled.");
            vaultHooked = false;
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            papiExpansion = new BloraTagsExpansion(plugin, dataManager, settings);
            if (papiExpansion.register()) {
                plugin.getLogger().info("Successfully registered PlaceholderAPI expansion.");
                papiHooked = true;
            } else {
                plugin.getLogger().warning("PlaceholderAPI found but failed to register expansion!");
                papiHooked = false;
            }
        } else {
            plugin.getLogger().info("PlaceholderAPI plugin not found. Placeholders will be unavailable.");
            papiHooked = false;
        }
    }

    public void shutdownHooks() {
        if (papiHooked && papiExpansion != null) {
            try {
                if (papiExpansion.isRegistered()) {
                    papiExpansion.unregister();
                    plugin.getLogger().info("Unregistered PlaceholderAPI expansion.");
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error unregistering PlaceholderAPI expansion.", e);
            }
            papiHooked = false;
            papiExpansion = null;
        }
        vaultHooked = false;
        vaultEconomy = null;
    }


    private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        vaultEconomy = rsp.getProvider();
        return vaultEconomy != null;
    }

    public boolean hasEconomy() {
        return vaultHooked && vaultEconomy != null;
    }

    public boolean hasEnoughMoney(@NotNull OfflinePlayer player, double amount) {
        return hasEconomy() && vaultEconomy.has(player, amount);
    }

    public boolean withdrawMoney(@NotNull OfflinePlayer player, double amount) {
        if (!hasEconomy() || amount <= 0) return false;
        EconomyResponse response = vaultEconomy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean depositMoney(@NotNull OfflinePlayer player, double amount) {
        if (!hasEconomy() || amount <= 0) return false;
        EconomyResponse response = vaultEconomy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    @NotNull
    public String formatMoney(double amount) {
        if (!hasEconomy()) return String.valueOf(amount);
        try {
            return vaultEconomy.format(amount);
        } catch (AbstractMethodError | Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error calling Vault Economy format method.", e);
            String currency = vaultEconomy.currencyNamePlural() != null ? vaultEconomy.currencyNamePlural() : "";
            return String.format("%.2f %s", amount, currency).trim();
        }
    }


    private class BloraTagsExpansion extends PlaceholderExpansion {

        public BloraTagsExpansion(@NotNull BloraTags plugin, @NotNull DataManager dataManager, @NotNull Settings settings) {
        }

        @Override
        @NotNull
        public String getIdentifier() {
            return "bloratags";
        }

        @Override
        @NotNull
        public String getAuthor() {
            return String.join(", ", Integrations.this.plugin.getDescription().getAuthors());
        }

        @Override
        @NotNull
        public String getVersion() {
            return Integrations.this.plugin.getDescription().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String params) {
            if (offlinePlayer == null) return null;

            UUID playerUuid = offlinePlayer.getUniqueId();
            BloraTags.PlayerTagData data = Integrations.this.dataManager.getCachedPlayerData(playerUuid);

            if (data == null) {
                try {
                    data = Integrations.this.dataManager.getPlayerDataAsync(playerUuid).join();
                } catch (CompletionException | CancellationException e) {
                    Integrations.this.plugin.getLogger().log(Level.WARNING, "Error fetching player data synchronously for PAPI request: " + playerUuid, e);
                    return "";
                }
                if (data == null) {
                    Integrations.this.plugin.getLogger().fine("No PlayerTagData found for " + playerUuid + " during PAPI request.");
                    return "";
                }
            }

            String lowerParams = params.toLowerCase();

            if (lowerParams.equals("tag")) {
                String selectedTagId = data.selectedTagId();
                if (selectedTagId == null) return "";

                BloraTags.TagDefinition selectedTag = Integrations.this.dataManager.getTagById(selectedTagId);
                if (selectedTag == null) {
                    Integrations.this.plugin.getLogger().warning("Selected tag ID '" + selectedTagId + "' for player " + playerUuid + " not found in definitions.");
                    return "";
                }

                String tagDisplayRaw = selectedTag.display();
                String finalTagDisplay = tagDisplayRaw;

                if (data.hasSelectedGradient()) {
                    BloraTags.TagColor startColor = Integrations.this.dataManager.getColorById(data.selectedGradientStartId());
                    BloraTags.TagColor endColor = Integrations.this.dataManager.getColorById(data.selectedGradientEndId());
                    if (startColor != null && endColor != null) {
                        finalTagDisplay = String.format("<gradient:%s:%s>%s</gradient>", startColor.value(), endColor.value(), tagDisplayRaw);
                    } else {
                        Integrations.this.plugin.getLogger().warning("Invalid gradient colors ('" + data.selectedGradientStartId() + "', '" + data.selectedGradientEndId() + "') for player " + playerUuid);
                    }
                } else if (data.hasSelectedColor()) {
                    BloraTags.TagColor color = Integrations.this.dataManager.getColorById(data.selectedColorId());
                    if (color != null) {
                        finalTagDisplay = String.format("<%s>%s</%s>", color.value(), tagDisplayRaw, color.value());
                    } else {
                        Integrations.this.plugin.getLogger().warning("Invalid color ID '" + data.selectedColorId() + "' for player " + playerUuid);
                    }
                } else if (Integrations.this.settings.shouldApplyDefaultColor()) {
                    String defaultColorId = Integrations.this.settings.getDefaultColorId();
                    if (defaultColorId != null) {
                        BloraTags.TagColor defaultColor = Integrations.this.dataManager.getColorById(defaultColorId);
                        if (defaultColor != null) {
                            finalTagDisplay = String.format("<%s>%s</%s>", defaultColor.value(), tagDisplayRaw, defaultColor.value());
                        } else {
                            Integrations.this.plugin.getLogger().warning("Default color ID '" + defaultColorId + "' is invalid.");
                        }
                    }
                }


                return finalTagDisplay.isEmpty() ? "" : finalTagDisplay + " ";

            } else if (lowerParams.equals("tag_raw")) {
                String selectedTagId = data.selectedTagId();
                if (selectedTagId == null) return "";
                BloraTags.TagDefinition tag = Integrations.this.dataManager.getTagById(selectedTagId);
                return tag != null ? tag.display() : "";

            } else if (lowerParams.equals("tag_id")) {
                return data.selectedTagId() != null ? data.selectedTagId() : "";

            } else if (lowerParams.equals("color_id")) {
                return data.selectedColorId() != null ? data.selectedColorId() : "";

            } else if (lowerParams.equals("gradient_start_id")) {
                return data.selectedGradientStartId() != null ? data.selectedGradientStartId() : "";

            } else if (lowerParams.equals("gradient_end_id")) {
                return data.selectedGradientEndId() != null ? data.selectedGradientEndId() : "";
            }

            return null;
        }

        @Override
        public @NotNull List<String> getPlaceholders() {

            return List.of(
                    "%bloratags_tag%",
                    "%bloratags_tag_raw%",
                    "%bloratags_tag_id%",
                    "%bloratags_color_id%",
                    "%bloratags_gradient_start_id%",
                    "%bloratags_gradient_end_id%"
            );
        }
    }
}