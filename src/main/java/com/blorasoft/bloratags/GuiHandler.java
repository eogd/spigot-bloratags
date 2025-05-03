package com.blorasoft.bloratags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles all GUI creation, opening, and interaction.
 */
public class GuiHandler implements InventoryHolder {

    private final BloraTags plugin;
    private final Settings settings;
    private final DataManager dataManager;
    private final Integrations integrations;
    private final MiniMessage miniMessage;
    private final SimpleDateFormat dateFormat;

    private final Map<UUID, PlayerGuiState> openGuis = new ConcurrentHashMap<>();

    private enum GuiType { MAIN_MENU, MY_TAGS, STYLE_SELECTOR, SHOP, ADMIN_REVIEW }

    private static class PlayerGuiState {
        final Inventory inventory;
        final GuiType type;
        final Map<Integer, Consumer<InventoryClickEvent>> clickActions = new HashMap<>();
        int currentPage = 0;
        Object context;

        PlayerGuiState(Inventory inventory, GuiType type) {
            this.inventory = inventory;
            this.type = type;
        }
    }

    public GuiHandler(@NotNull BloraTags plugin, @NotNull Settings settings, @NotNull DataManager dataManager) {
        this.plugin = plugin;
        this.settings = settings;
        this.dataManager = dataManager;
        this.integrations = plugin.getIntegrations();
        this.miniMessage = MiniMessage.miniMessage();
        this.dateFormat = new SimpleDateFormat(settings.getGuiString("general.date_format", "yyyy-MM-dd HH:mm"));
    }

    public void handleClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;

        UUID playerUuid = player.getUniqueId();
        PlayerGuiState state = openGuis.get(playerUuid);

        if (state == null || !event.getInventory().equals(state.inventory)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        if (slot >= 0 && slot < state.inventory.getSize()) {
            Consumer<InventoryClickEvent> action = state.clickActions.get(slot);
            if (action != null) {
                try {
                    action.accept(event);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Error executing click action in GUI " + state.type + " slot " + slot, e);
                    settings.sendMessage(player, "error");
                    player.closeInventory();
                }
            }
        } else if (event.getClick().isShiftClick()) {
            event.setCancelled(true);
        } else if (event.getClickedInventory().equals(player.getOpenInventory().getBottomInventory())) {
            event.setCancelled(false);
        }
    }

    public void closeGui(@NotNull UUID playerUuid) {
        openGuis.remove(playerUuid);
    }

    private void registerOpenGui(@NotNull UUID playerUuid, @NotNull PlayerGuiState state) {
        openGuis.put(playerUuid, state);
    }


    public void openMainMenu(@NotNull Player player) {
        UUID playerUuid = player.getUniqueId();
        dataManager.getPlayerDataAsync(playerUuid).thenAcceptAsync(playerData -> {
            if (playerData == null) { settings.sendMessage(player, "error_loading_data"); return; }

            String titleStr = settings.getGuiString("main_menu.title", "BloraTags");
            int size = normalizeSize(settings.getGuiInt("main_menu.size", 27));

            Component titleComponent = Utils.parseMiniMessage(titleStr);
            String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);
            Inventory inventory = Bukkit.createInventory(this, size, legacyTitle);
            PlayerGuiState state = new PlayerGuiState(inventory, GuiType.MAIN_MENU);

            ConfigurationSection itemsSection = settings.getGuiSection("main_menu.items");
            if (itemsSection != null) {
                for (String key : itemsSection.getKeys(false)) {
                    ConfigurationSection itemConfig = itemsSection.getConfigurationSection(key);
                    if (itemConfig == null) continue;

                    int slot = itemConfig.getInt("slot", -1);
                    if (slot < 0 || slot >= size) continue;

                    String requiredPerm = itemConfig.getString("permission");
                    if (requiredPerm != null && !player.hasPermission(requiredPerm)) {
                        continue;
                    }

                    Material material = parseMaterial(itemConfig.getString("material", "STONE"), Material.STONE);
                    String nameStr = itemConfig.getString("name", " ");
                    List<String> loreStr = itemConfig.getStringList("lore");
                    boolean glow = itemConfig.getBoolean("glow", false);
                    String action = itemConfig.getString("action", "").toLowerCase();

                    BloraTags.TagDefinition currentTag = playerData.selectedTagId() != null ? dataManager.getTagById(playerData.selectedTagId()) : null;
                    String currentTagName = currentTag != null ? currentTag.display() : settings.getGuiString("general.no_tag_selected", "None");
                    TagResolver placeholders = TagResolver.builder()
                            .resolver(Placeholder.unparsed("player_name", player.getName()))
                            .resolver(Placeholder.component("current_tag", Utils.parseMiniMessage(currentTagName)))
                            .build();

                    ItemStack item = Utils.item(material)
                            .setName(Utils.parseMiniMessage(nameStr, placeholders))
                            .setLore(Utils.parseMiniMessageList(loreStr, placeholders))
                            .setGlow(glow)
                            .build();

                    setItem(state, slot, item, createMainMenuAction(player, action));
                }
            }

            Material fillerMat = parseMaterial(settings.getGuiString("main_menu.filler_item", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
            fillEmptySlots(state, Utils.createFiller(fillerMat));

            player.openInventory(inventory);
            registerOpenGui(playerUuid, state);

        }, plugin.getDatabaseManager()::runSyncTask);
    }

    private Consumer<InventoryClickEvent> createMainMenuAction(@NotNull Player player, @NotNull String action) {
        return switch (action) {
            case "open_my_tags" -> e -> openMyTags(player, 0);
            case "open_style_selector" -> e -> openStyleSelector(player, 0);
            case "open_shop" -> e -> {
                if (settings.isShopEnabled() && integrations != null && integrations.hasEconomy()) {
                    openShop(player, 0);
                } else {
                    settings.sendMessage(player, "shop_disabled_or_no_economy");
                    player.closeInventory();
                }
            };
            case "open_admin_review" -> e -> {
                if (player.hasPermission(CommandHandler.PERM_ADMIN_REVIEW)) {
                    openAdminReview(player, 0);
                } else {
                    settings.sendMessage(player, "no_permission");
                }
            };
            case "close" -> e -> player.closeInventory();
            default -> null;
        };
    }

    public void openMyTags(@NotNull Player player, int page) {
        UUID playerUuid = player.getUniqueId();
        dataManager.getPlayerDataAsync(playerUuid).thenAcceptAsync(playerData -> {
            if (playerData == null) { settings.sendMessage(player, "error_loading_data"); return; }

            String titleStr = settings.getGuiString("my_tags.title", "My Tags ({page}/{max_page})");
            int size = normalizeSize(settings.getGuiInt("my_tags.size", 54));

            List<BloraTags.TagDefinition> ownedTags = playerData.ownedTagIds().stream()
                    .map(dataManager::getTagById)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(BloraTags.TagDefinition::id))
                    .toList();

            int itemsPerPage = calculateItemsPerPage(size);
            int maxPage = calculateMaxPage(ownedTags.size(), itemsPerPage);
            int currentPage = sanitizePage(page, maxPage);

            Component titleComponent = Utils.parseMiniMessage(titleStr,
                    Placeholder.unparsed("page", String.valueOf(currentPage + 1)),
                    Placeholder.unparsed("max_page", String.valueOf(maxPage + 1))
            );
            String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);
            Inventory inventory = Bukkit.createInventory(this, size, legacyTitle);
            PlayerGuiState state = new PlayerGuiState(inventory, GuiType.MY_TAGS);
            state.currentPage = currentPage;

            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, ownedTags.size());
            int slotIndex = 0;
            for (int i = startIndex; i < endIndex; i++) {
                if (slotIndex >= itemsPerPage) break;
                BloraTags.TagDefinition tag = ownedTags.get(i);
                boolean isSelected = tag.id().equals(playerData.selectedTagId());

                Material material = parseMaterial(settings.getGuiString("my_tags.item.material", "NAME_TAG"), Material.NAME_TAG);
                String nameFormat = settings.getGuiString("my_tags.item.name", "<green>{tag_display}");
                List<String> loreFormat = settings.getGuiStringList("my_tags.item.lore");

                TagResolver placeholders = TagResolver.builder()
                        .resolver(Placeholder.component("tag_display", Utils.parseMiniMessage(tag.display())))
                        .resolver(Placeholder.unparsed("tag_id", tag.id()))
                        .resolver(Placeholder.unparsed("tag_description", tag.description()))
                        .build();

                Utils.GuiItemBuilder builder = Utils.item(material)
                        .setName(Utils.parseMiniMessage(nameFormat, placeholders));

                List<Component> lore = new ArrayList<>(Utils.parseMiniMessageList(loreFormat, placeholders));
                if (isSelected) {
                    lore.add(Utils.parseMiniMessage(settings.getGuiString("my_tags.item.lore_selected", "<yellow>▶ Selected")));
                    builder.setGlow(true);
                } else {
                    lore.add(Utils.parseMiniMessage(settings.getGuiString("my_tags.item.lore_select", "<gray>Click to select")));
                }
                lore.add(Utils.parseMiniMessage(settings.getGuiString("my_tags.item.lore_clear", "<red>Shift+Click to deselect")));
                builder.setLore(lore);

                setItem(state, slotIndex++, builder.build(), createMyTagsAction(player, tag, isSelected));
            }

            if (itemsPerPage < size) {
                setupPaginationControls(state, currentPage, maxPage,
                        p -> openMyTags(player, p),
                        p -> openMainMenu(player)
                );
            }

            Material fillerMat = parseMaterial(settings.getGuiString("my_tags.filler_item", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
            fillEmptySlots(state, Utils.createFiller(fillerMat));

            player.openInventory(inventory);
            registerOpenGui(playerUuid, state);

        }, plugin.getDatabaseManager()::runSyncTask);
    }

    private Consumer<InventoryClickEvent> createMyTagsAction(@NotNull Player player, @NotNull BloraTags.TagDefinition tag, boolean isSelected) {
        return event -> {
            PlayerGuiState currentState = openGuis.get(player.getUniqueId());
            int currentPage = (currentState != null && currentState.type == GuiType.MY_TAGS) ? currentState.currentPage : 0;

            if (event.getClick() == ClickType.SHIFT_LEFT) {
                if (isSelected) {
                    dataManager.clearSelectedTagAsync(player.getUniqueId(), success -> {
                        if (success) settings.sendMessage(player, "select_tag_cleared");
                        else settings.sendMessage(player, "error");
                        openMyTags(player, currentPage);
                    });
                }
            } else if (event.getClick() == ClickType.LEFT) {
                if (!isSelected) {
                    dataManager.setSelectedTagAsync(player.getUniqueId(), tag.id(), success -> {
                        if (success) settings.sendMessage(player, "select_tag_success", Placeholder.component("tag", Utils.parseMiniMessage(tag.display())));
                        else settings.sendMessage(player, "error");
                        openMyTags(player, currentPage);
                    });
                }
            }
        };
    }

    public void openStyleSelector(@NotNull Player player, int page) {
        UUID playerUuid = player.getUniqueId();
        dataManager.getPlayerDataAsync(playerUuid).thenAcceptAsync(playerData -> {
            if (playerData == null) { settings.sendMessage(player, "error_loading_data"); return; }

            String titleStr = settings.getGuiString("style_selector.title", "Select Style ({page}/{max_page})");
            int size = normalizeSize(settings.getGuiInt("style_selector.size", 54));

            List<Object> styles = Stream.concat(
                            dataManager.getAllLoadedColors().values().stream(),
                            dataManager.getAllLoadedGradients().values().stream()
                    )
                    .sorted(Comparator.comparing(o -> {
                        if (o instanceof BloraTags.TagColor c) return c.id();
                        if (o instanceof BloraTags.TagGradient g) return g.id();
                        return "";
                    }))
                    .collect(Collectors.toList());

            int itemsPerPage = calculateItemsPerPage(size);
            int maxPage = calculateMaxPage(styles.size(), itemsPerPage);
            int currentPage = sanitizePage(page, maxPage);

            Component titleComponent = Utils.parseMiniMessage(titleStr,
                    Placeholder.unparsed("page", String.valueOf(currentPage + 1)),
                    Placeholder.unparsed("max_page", String.valueOf(maxPage + 1))
            );
            String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);
            Inventory inventory = Bukkit.createInventory(this, size, legacyTitle);
            PlayerGuiState state = new PlayerGuiState(inventory, GuiType.STYLE_SELECTOR);
            state.currentPage = currentPage;
            state.context = styles;

            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, styles.size());
            int slotIndex = 0;
            for (int i = startIndex; i < endIndex; i++) {
                if (slotIndex >= itemsPerPage) break;
                Object style = styles.get(i);
                ItemStack item = createStyleItem(player, playerData, style);
                if (item != null) {
                    setItem(state, slotIndex++, item, createStyleAction(player, style));
                }
            }

            if (itemsPerPage < size) {
                setupPaginationControls(state, currentPage, maxPage,
                        p -> openStyleSelector(player, p),
                        p -> openMainMenu(player)
                );
                int clearSlot = settings.getGuiInt("style_selector.clear_style.slot", size - 5);
                if (clearSlot >= size - 9 && clearSlot < size) {
                    Material clearMat = parseMaterial(settings.getGuiString("style_selector.clear_style.material", "BARRIER"), Material.BARRIER);
                    String clearName = settings.getGuiString("style_selector.clear_style.name", "<red>Clear Style");
                    List<String> clearLore = settings.getGuiStringList("style_selector.clear_style.lore");
                    ItemStack clearItem = Utils.item(clearMat)
                            .setName(Utils.parseMiniMessage(clearName))
                            .setLore(Utils.parseMiniMessageList(clearLore))
                            .build();
                    setItem(state, clearSlot, clearItem, createClearStyleAction(player));
                }
            }

            Material fillerMat = parseMaterial(settings.getGuiString("style_selector.filler_item", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
            fillEmptySlots(state, Utils.createFiller(fillerMat));

            player.openInventory(inventory);
            registerOpenGui(playerUuid, state);

        }, plugin.getDatabaseManager()::runSyncTask);
    }

    @Nullable
    private ItemStack createStyleItem(@NotNull Player player, @NotNull BloraTags.PlayerTagData playerData, @NotNull Object style) {
        Material material;
        String nameFormat;
        List<String> loreFormat;
        boolean hasPermission;
        boolean isSelected;
        TagResolver placeholders;
        String styleId;

        if (style instanceof BloraTags.TagColor color) {
            styleId = color.id();
            material = parseMaterial(settings.getGuiString("style_selector.color_item.material", "WHITE_WOOL"), Material.WHITE_WOOL);
            nameFormat = settings.getGuiString("style_selector.color_item.name", "<{color_value}>{color_id}");
            loreFormat = settings.getGuiStringList("style_selector.color_item.lore");
            hasPermission = !color.hasPermission() || player.hasPermission(color.permission());
            isSelected = color.id().equals(playerData.selectedColorId());
            placeholders = TagResolver.builder()
                    .resolver(Placeholder.unparsed("color_id", color.id()))
                    .resolver(Placeholder.unparsed("color_value", color.value()))
                    .resolver(Placeholder.unparsed("permission", color.permission() != null ? color.permission() : "None"))
                    .build();

        } else if (style instanceof BloraTags.TagGradient gradient) {
            styleId = gradient.id();
            material = parseMaterial(settings.getGuiString("style_selector.gradient_item.material", "FILLED_MAP"), Material.FILLED_MAP);
            nameFormat = settings.getGuiString("style_selector.gradient_item.name", "<gradient:{start_color_value}:{end_color_value}>{gradient_id}");
            loreFormat = settings.getGuiStringList("style_selector.gradient_item.lore");
            hasPermission = !gradient.hasPermission() || player.hasPermission(gradient.permission());
            isSelected = gradient.startColorId().equals(playerData.selectedGradientStartId()) && gradient.endColorId().equals(playerData.selectedGradientEndId());

            BloraTags.TagColor startColor = dataManager.getColorById(gradient.startColorId());
            BloraTags.TagColor endColor = dataManager.getColorById(gradient.endColorId());
            String startValue = (startColor != null) ? startColor.value() : "white";
            String endValue = (endColor != null) ? endColor.value() : "black";

            placeholders = TagResolver.builder()
                    .resolver(Placeholder.unparsed("gradient_id", gradient.id()))
                    .resolver(Placeholder.unparsed("start_color_id", gradient.startColorId()))
                    .resolver(Placeholder.unparsed("end_color_id", gradient.endColorId()))
                    .resolver(Placeholder.unparsed("start_color_value", startValue))
                    .resolver(Placeholder.unparsed("end_color_value", endValue))
                    .resolver(Placeholder.unparsed("permission", gradient.permission() != null ? gradient.permission() : "None"))
                    .build();
        } else {
            return null;
        }

        Utils.GuiItemBuilder builder = Utils.item(material)
                .setName(Utils.parseMiniMessage(nameFormat, placeholders));

        List<Component> lore = new ArrayList<>(Utils.parseMiniMessageList(loreFormat, placeholders));

        if (hasPermission) {
            if (isSelected) {
                lore.add(Utils.parseMiniMessage(settings.getGuiString("style_selector.item_lore_selected", "<yellow>▶ Selected")));
                builder.setGlow(true);
            } else {
                lore.add(Utils.parseMiniMessage(settings.getGuiString("style_selector.item_lore_select", "<gray>Click to select")));
            }
        } else {
            material = parseMaterial(settings.getGuiString("style_selector.item_material_no_permission", "BARRIER"), Material.BARRIER);
            builder = Utils.item(material).setName(Utils.parseMiniMessage(nameFormat, placeholders));
            lore.add(Utils.parseMiniMessage(settings.getGuiString("style_selector.item_lore_no_permission", "<red>No permission for this style")));
            lore.add(Utils.parseMiniMessage("<dark_gray>Requires: {permission}", placeholders));
        }

        builder.setLore(lore);
        return builder.build();
    }

    private Consumer<InventoryClickEvent> createStyleAction(@NotNull Player player, @NotNull Object style) {
        return event -> {
            PlayerGuiState currentState = openGuis.get(player.getUniqueId());
            int currentPage = (currentState != null && currentState.type == GuiType.STYLE_SELECTOR) ? currentState.currentPage : 0;

            if (style instanceof BloraTags.TagColor color) {
                if (!color.hasPermission() || player.hasPermission(color.permission())) {
                    BloraTags.PlayerTagData pData = dataManager.getCachedPlayerData(player.getUniqueId());
                    boolean alreadySelected = pData != null && color.id().equals(pData.selectedColorId());
                    if (!alreadySelected) {
                        dataManager.setSelectedColorAsync(player.getUniqueId(), color.id(), success -> {
                            if (success) settings.sendMessage(player, "select_style_success", Placeholder.unparsed("style_id", color.id()));
                            else settings.sendMessage(player, "error");
                            openStyleSelector(player, currentPage);
                        });
                    }
                } else {
                    settings.sendMessage(player, "no_permission_style");
                }
            } else if (style instanceof BloraTags.TagGradient gradient) {
                if (!gradient.hasPermission() || player.hasPermission(gradient.permission())) {
                    BloraTags.PlayerTagData pData = dataManager.getCachedPlayerData(player.getUniqueId());
                    boolean alreadySelected = pData != null && gradient.startColorId().equals(pData.selectedGradientStartId()) && gradient.endColorId().equals(pData.selectedGradientEndId());
                    if (!alreadySelected) {
                        dataManager.setSelectedGradientAsync(player.getUniqueId(), gradient.startColorId(), gradient.endColorId(), success -> {
                            if (success) settings.sendMessage(player, "select_style_success", Placeholder.unparsed("style_id", gradient.id()));
                            else settings.sendMessage(player, "error");
                            openStyleSelector(player, currentPage);
                        });
                    }
                } else {
                    settings.sendMessage(player, "no_permission_style");
                }
            }
        };
    }

    private Consumer<InventoryClickEvent> createClearStyleAction(@NotNull Player player) {
        return event -> {
            PlayerGuiState currentState = openGuis.get(player.getUniqueId());
            int currentPage = (currentState != null && currentState.type == GuiType.STYLE_SELECTOR) ? currentState.currentPage : 0;
            dataManager.clearColorSelectionAsync(player.getUniqueId(), success -> {
                if (success) settings.sendMessage(player, "select_style_cleared");
                else settings.sendMessage(player, "error");
                openStyleSelector(player, currentPage);
            });
        };
    }

    public void openShop(@NotNull Player player, int page) {
        if (!settings.isShopEnabled() || integrations == null || !integrations.hasEconomy()) {
            settings.sendMessage(player, "shop_disabled_or_no_economy");
            return;
        }

        UUID playerUuid = player.getUniqueId();
        dataManager.getPlayerDataAsync(playerUuid).thenAcceptAsync(playerData -> {
            if (playerData == null) { settings.sendMessage(player, "error_loading_data"); return; }

            String titleStr = settings.getGuiString("shop.title", "Tag Shop ({page}/{max_page})");
            int size = normalizeSize(settings.getGuiInt("shop.size", 54));

            List<BloraTags.TagDefinition> buyableTags = dataManager.getAllLoadedTags().values().stream()
                    .filter(BloraTags.TagDefinition::buyable)
                    .sorted(Comparator.comparing(BloraTags.TagDefinition::id))
                    .toList();

            int itemsPerPage = calculateItemsPerPage(size);
            int maxPage = calculateMaxPage(buyableTags.size(), itemsPerPage);
            int currentPage = sanitizePage(page, maxPage);

            Component titleComponent = Utils.parseMiniMessage(titleStr,
                    Placeholder.unparsed("page", String.valueOf(currentPage + 1)),
                    Placeholder.unparsed("max_page", String.valueOf(maxPage + 1))
            );
            String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);
            Inventory inventory = Bukkit.createInventory(this, size, legacyTitle);
            PlayerGuiState state = new PlayerGuiState(inventory, GuiType.SHOP);
            state.currentPage = currentPage;
            state.context = buyableTags;

            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, buyableTags.size());
            int slotIndex = 0;
            for (int i = startIndex; i < endIndex; i++) {
                if (slotIndex >= itemsPerPage) break;
                BloraTags.TagDefinition tag = buyableTags.get(i);
                ItemStack item = createShopItem(player, playerData, tag);
                if (item != null) {
                    setItem(state, slotIndex++, item, createShopAction(player, tag));
                }
            }

            if (itemsPerPage < size) {
                setupPaginationControls(state, currentPage, maxPage,
                        p -> openShop(player, p),
                        p -> openMainMenu(player)
                );
            }

            Material fillerMat = parseMaterial(settings.getGuiString("shop.filler_item", "YELLOW_STAINED_GLASS_PANE"), Material.YELLOW_STAINED_GLASS_PANE);
            fillEmptySlots(state, Utils.createFiller(fillerMat));

            player.openInventory(inventory);
            registerOpenGui(playerUuid, state);

        }, plugin.getDatabaseManager()::runSyncTask);
    }

    @Nullable
    private ItemStack createShopItem(@NotNull Player player, @NotNull BloraTags.PlayerTagData playerData, @NotNull BloraTags.TagDefinition tag) {
        Material material = parseMaterial(settings.getGuiString("shop.item.material", "EMERALD"), Material.EMERALD);
        String nameFormat = settings.getGuiString("shop.item.name", "<gold>{tag_display}");
        List<String> loreFormat = settings.getGuiStringList("shop.item.lore");

        boolean ownsTag = playerData.ownsTag(tag.id());
        boolean hasPerm = !tag.hasPermission() || player.hasPermission(tag.permission());
        boolean canAfford = integrations.hasEnoughMoney(player, tag.price());
        String formattedPrice = integrations.formatMoney(tag.price());

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.component("tag_display", Utils.parseMiniMessage(tag.display())))
                .resolver(Placeholder.unparsed("tag_id", tag.id()))
                .resolver(Placeholder.unparsed("tag_description", tag.description()))
                .resolver(Placeholder.unparsed("price", String.valueOf(tag.price())))
                .resolver(Placeholder.unparsed("formatted_price", formattedPrice))
                .resolver(Placeholder.unparsed("permission", tag.permission() != null ? tag.permission() : "None"))
                .build();

        Utils.GuiItemBuilder builder = Utils.item(material)
                .setName(Utils.parseMiniMessage(nameFormat, placeholders));

        List<Component> lore = new ArrayList<>(Utils.parseMiniMessageList(loreFormat, placeholders));

        if (ownsTag) {
            material = parseMaterial(settings.getGuiString("shop.item.material_owned", "GRAY_DYE"), Material.GRAY_DYE);
            builder = Utils.item(material).setName(Utils.parseMiniMessage(nameFormat, placeholders));
            lore.add(Utils.parseMiniMessage(settings.getGuiString("shop.item.lore_owned", "<gray>Already Owned")));
        } else if (!hasPerm) {
            material = parseMaterial(settings.getGuiString("shop.item.material_no_permission", "BARRIER"), Material.BARRIER);
            builder = Utils.item(material).setName(Utils.parseMiniMessage(nameFormat, placeholders));
            lore.add(Utils.parseMiniMessage(settings.getGuiString("shop.item.lore_no_permission", "<red>No permission to buy")));
            lore.add(Utils.parseMiniMessage("<dark_gray>Requires: {permission}", placeholders));
        } else {
            lore.add(Utils.parseMiniMessage(settings.getGuiString("shop.item.lore_price", "<gold>Price: {formatted_price}"), placeholders));
            if (canAfford) {
                lore.add(Utils.parseMiniMessage(settings.getGuiString("shop.item.lore_can_afford", "<green>Click to buy")));
            } else {
                material = parseMaterial(settings.getGuiString("shop.item.material_cannot_afford", "RED_DYE"), Material.RED_DYE);
                builder = Utils.item(material).setName(Utils.parseMiniMessage(nameFormat, placeholders));
                lore.add(Utils.parseMiniMessage(settings.getGuiString("shop.item.lore_cannot_afford", "<red>Cannot afford")));
            }
        }

        builder.setLore(lore);
        return builder.build();
    }

    private Consumer<InventoryClickEvent> createShopAction(@NotNull Player player, @NotNull BloraTags.TagDefinition tag) {
        return event -> {
            PlayerGuiState currentState = openGuis.get(player.getUniqueId());
            int currentPage = (currentState != null && currentState.type == GuiType.SHOP) ? currentState.currentPage : 0;

            dataManager.getPlayerDataAsync(player.getUniqueId()).thenAcceptAsync(playerData -> {
                if (playerData == null) { settings.sendMessage(player, "error_loading_data"); player.closeInventory(); return; }

                boolean ownsTag = playerData.ownsTag(tag.id());
                boolean hasPerm = !tag.hasPermission() || player.hasPermission(tag.permission());
                boolean canAfford = integrations.hasEnoughMoney(player, tag.price());

                if (ownsTag) {
                    settings.sendMessage(player, "shop_already_owned", Placeholder.component("tag", Utils.parseMiniMessage(tag.display())));
                } else if (!hasPerm) {
                    settings.sendMessage(player, "no_permission_purchase");
                } else if (!canAfford) {
                    settings.sendMessage(player, "shop_cannot_afford", Placeholder.unparsed("formatted_price", integrations.formatMoney(tag.price())));
                } else {
                    if (integrations.withdrawMoney(player, tag.price())) {
                        dataManager.addOwnedTagAsync(player.getUniqueId(), tag.id(), grantSuccess -> {
                            if (grantSuccess) {
                                settings.sendMessage(player, "shop_purchase_success",
                                        Placeholder.component("tag", Utils.parseMiniMessage(tag.display())),
                                        Placeholder.unparsed("formatted_price", integrations.formatMoney(tag.price()))
                                );
                                openShop(player, currentPage);
                            } else {
                                settings.sendMessage(player, "shop_purchase_grant_fail", Placeholder.component("tag", Utils.parseMiniMessage(tag.display())));
                                if (integrations.depositMoney(player, tag.price())) {
                                    settings.sendMessage(player, "shop_purchase_refunded");
                                } else {
                                    plugin.getLogger().severe("CRITICAL: Failed to refund " + tag.price() + " to player " + player.getName() + " after failed tag grant for tag '" + tag.id() + "'! Manual refund required!");
                                    settings.sendMessage(player, "shop_purchase_refund_fail");
                                }
                                openShop(player, currentPage);
                            }
                        });
                    } else {
                        settings.sendMessage(player, "shop_purchase_withdraw_fail", Placeholder.unparsed("formatted_price", integrations.formatMoney(tag.price())));
                        openShop(player, currentPage);
                    }
                }
            }, plugin.getDatabaseManager()::runSyncTask);
        };
    }


    public void openAdminReview(@NotNull Player player, int page) {
        if (!settings.isApplicationsEnabled()) {
            settings.sendMessage(player, "apply_disabled");
            return;
        }

        UUID playerUuid = player.getUniqueId();

        dataManager.getPendingApplicationsAsync().thenAcceptAsync(pendingApplications -> {
            if (pendingApplications == null) {
                settings.sendMessage(player, "error_loading_applications");
                return;
            }

            String titleStr = settings.getGuiString("admin_review.title", "Review Applications ({page}/{max_page})");
            int size = normalizeSize(settings.getGuiInt("admin_review.size", 54));

            pendingApplications.sort(Comparator.comparingLong(BloraTags.TagApplication::applicationTimestamp));

            int itemsPerPage = calculateItemsPerPage(size);
            int maxPage = calculateMaxPage(pendingApplications.size(), itemsPerPage);
            int currentPage = sanitizePage(page, maxPage);

            Component titleComponent = Utils.parseMiniMessage(titleStr,
                    Placeholder.unparsed("page", String.valueOf(currentPage + 1)),
                    Placeholder.unparsed("max_page", String.valueOf(maxPage + 1))
            );
            String legacyTitle = LegacyComponentSerializer.legacySection().serialize(titleComponent);
            Inventory inventory = Bukkit.createInventory(this, size, legacyTitle);
            PlayerGuiState state = new PlayerGuiState(inventory, GuiType.ADMIN_REVIEW);
            state.currentPage = currentPage;
            state.context = pendingApplications;

            int startIndex = currentPage * itemsPerPage;
            int endIndex = Math.min(startIndex + itemsPerPage, pendingApplications.size());
            int slotIndex = 0;
            for (int i = startIndex; i < endIndex; i++) {
                if (slotIndex >= itemsPerPage) break;
                BloraTags.TagApplication app = pendingApplications.get(i);
                ItemStack item = createAdminReviewItem(player, app);
                if (item != null) {
                    setItem(state, slotIndex++, item, createAdminReviewAction(player, app));
                }
            }

            if (itemsPerPage < size) {
                setupPaginationControls(state, currentPage, maxPage,
                        p -> openAdminReview(player, p),
                        p -> openMainMenu(player)
                );
            }

            Material fillerMat = parseMaterial(settings.getGuiString("admin_review.filler_item", "BLUE_STAINED_GLASS_PANE"), Material.BLUE_STAINED_GLASS_PANE);
            fillEmptySlots(state, Utils.createFiller(fillerMat));

            if (startIndex >= pendingApplications.size() && currentPage > 0) {
                openAdminReview(player, currentPage - 1);
                return;
            } else if (pendingApplications.isEmpty() && startIndex == 0) {
                settings.sendMessage(player, "review_no_pending");
            }

            player.openInventory(inventory);
            registerOpenGui(playerUuid, state);

        }, plugin.getDatabaseManager()::runSyncTask);
    }

    @Nullable
    private ItemStack createAdminReviewItem(@NotNull Player reviewer, @NotNull BloraTags.TagApplication app) {
        Material material = parseMaterial(settings.getGuiString("admin_review.item.material", "PAPER"), Material.PAPER);
        String nameFormat = settings.getGuiString("admin_review.item.name", "<yellow>App: <white>{player_name} - <gold>{tag_id}");
        List<String> loreFormat = settings.getGuiStringList("admin_review.item.lore");

        BloraTags.TagDefinition tagDef = dataManager.getTagById(app.tagId());
        String tagDisplayStr = (tagDef != null) ? tagDef.display() : "<italic><gray>Unknown Tag</italic>";
        String formattedDate = dateFormat.format(new Date(app.applicationTimestamp()));

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("player_name", app.playerName()))
                .resolver(Placeholder.unparsed("player_uuid", app.playerUuid().toString()))
                .resolver(Placeholder.unparsed("tag_id", app.tagId()))
                .resolver(Placeholder.component("tag_display", Utils.parseMiniMessage(tagDisplayStr)))
                .resolver(Placeholder.unparsed("timestamp", String.valueOf(app.applicationTimestamp())))
                .resolver(Placeholder.unparsed("formatted_date", formattedDate))
                .resolver(Placeholder.unparsed("application_id", String.valueOf(app.id())))
                .build();

        Utils.GuiItemBuilder builder = Utils.item(material)
                .setName(Utils.parseMiniMessage(nameFormat, placeholders));

        List<Component> lore = new ArrayList<>(Utils.parseMiniMessageList(loreFormat, placeholders));
        lore.add(Component.empty());
        lore.add(Utils.parseMiniMessage(settings.getGuiString("admin_review.item.lore_approve", "<green>Left-Click to Approve")));
        lore.add(Utils.parseMiniMessage(settings.getGuiString("admin_review.item.lore_reject", "<red>Right-Click to Reject")));

        builder.setLore(lore);
        return builder.build();
    }

    private Consumer<InventoryClickEvent> createAdminReviewAction(@NotNull Player reviewer, @NotNull BloraTags.TagApplication app) {
        return event -> {
            PlayerGuiState currentState = openGuis.get(reviewer.getUniqueId());
            int currentPage = (currentState != null && currentState.type == GuiType.ADMIN_REVIEW) ? currentState.currentPage : 0;

            if (event.getClick() == ClickType.LEFT) {
                dataManager.approveApplication(app, reviewer, success -> {
                    openAdminReview(reviewer, currentPage);
                });
            } else if (event.getClick() == ClickType.RIGHT) {
                dataManager.rejectApplication(app, reviewer, null, success -> {
                    openAdminReview(reviewer, currentPage);
                });
            }
        };
    }



    private void setItem(@NotNull PlayerGuiState state, int slot, @Nullable ItemStack item, @Nullable Consumer<InventoryClickEvent> action) {
        if (slot >= 0 && slot < state.inventory.getSize()) {
            state.inventory.setItem(slot, item);
            if (action != null) { state.clickActions.put(slot, action); }
            else { state.clickActions.remove(slot); }
        } else {
            plugin.getLogger().warning("Attempted to set item in invalid slot (" + slot + ") for GUI " + state.type);
        }
    }

    private void fillEmptySlots(@NotNull PlayerGuiState state, @NotNull ItemStack fillerItem) {
        for (int i = 0; i < state.inventory.getSize(); i++) {
            if (state.inventory.getItem(i) == null) {
                setItem(state, i, fillerItem.clone(), null);
            }
        }
    }

    private void setupPaginationControls(@NotNull PlayerGuiState state, int currentPage, int maxPage,
                                         @NotNull Consumer<Integer> pageAction, @NotNull Consumer<Player> backAction) {
        int size = state.inventory.getSize();
        int baseSlot = size - 9;

        int prevSlot = settings.getGuiInt("general.pagination.previous_page.slot", baseSlot + 0);
        Material prevMat = parseMaterial(settings.getGuiString("general.pagination.previous_page.material", "ARROW"), Material.ARROW);
        Material prevMatDisabled = parseMaterial(settings.getGuiString("general.pagination.previous_page.material_disabled", "GRAY_DYE"), Material.GRAY_DYE);
        String prevName = settings.getGuiString("general.pagination.previous_page.name", "<yellow>Previous Page ({page})");
        String prevNameDisabled = settings.getGuiString("general.pagination.previous_page.name_disabled", "<gray>First Page");
        List<String> prevLore = settings.getGuiStringList("general.pagination.previous_page.lore");

        if (currentPage > 0) {
            TagResolver ph = Placeholder.unparsed("page", String.valueOf(currentPage));
            ItemStack prevItem = Utils.item(prevMat)
                    .setName(Utils.parseMiniMessage(prevName, ph))
                    .setLore(Utils.parseMiniMessageList(prevLore, ph))
                    .build();
            setItem(state, prevSlot, prevItem, e -> pageAction.accept(currentPage - 1));
        } else {
            ItemStack prevDisabled = Utils.item(prevMatDisabled)
                    .setName(Utils.parseMiniMessage(prevNameDisabled))
                    .build();
            setItem(state, prevSlot, prevDisabled, null);
        }

        int nextSlot = settings.getGuiInt("general.pagination.next_page.slot", baseSlot + 8);
        Material nextMat = parseMaterial(settings.getGuiString("general.pagination.next_page.material", "ARROW"), Material.ARROW);
        Material nextMatDisabled = parseMaterial(settings.getGuiString("general.pagination.next_page.material_disabled", "GRAY_DYE"), Material.GRAY_DYE);
        String nextName = settings.getGuiString("general.pagination.next_page.name", "<yellow>Next Page ({page})");
        String nextNameDisabled = settings.getGuiString("general.pagination.next_page.name_disabled", "<gray>Last Page");
        List<String> nextLore = settings.getGuiStringList("general.pagination.next_page.lore");

        if (currentPage < maxPage) {
            TagResolver ph = Placeholder.unparsed("page", String.valueOf(currentPage + 2));
            ItemStack nextItem = Utils.item(nextMat)
                    .setName(Utils.parseMiniMessage(nextName, ph))
                    .setLore(Utils.parseMiniMessageList(nextLore, ph))
                    .build();
            setItem(state, nextSlot, nextItem, e -> pageAction.accept(currentPage + 1));
        } else {
            ItemStack nextDisabled = Utils.item(nextMatDisabled)
                    .setName(Utils.parseMiniMessage(nextNameDisabled))
                    .build();
            setItem(state, nextSlot, nextDisabled, null);
        }

        int backSlot = settings.getGuiInt("general.pagination.back.slot", baseSlot + 4);
        Material backMat = parseMaterial(settings.getGuiString("general.pagination.back.material", "BARRIER"), Material.BARRIER);
        String backName = settings.getGuiString("general.pagination.back.name", "<red>Back");
        List<String> backLore = settings.getGuiStringList("general.pagination.back.lore");
        ItemStack backItem = Utils.item(backMat)
                .setName(Utils.parseMiniMessage(backName))
                .setLore(Utils.parseMiniMessageList(backLore))
                .build();
        setItem(state, backSlot, backItem, e -> backAction.accept((Player) e.getWhoClicked()));

        int pageIndicatorSlot = settings.getGuiInt("general.pagination.page_indicator.slot", -1);
        if (pageIndicatorSlot >= baseSlot && pageIndicatorSlot < size) {
            Material mat = parseMaterial(settings.getGuiString("general.pagination.page_indicator.material", "PAPER"), Material.PAPER);
            String name = settings.getGuiString("general.pagination.page_indicator.name", "<gray>Page {page}/{max_page}");
            TagResolver ph = TagResolver.builder()
                    .resolver(Placeholder.unparsed("page", String.valueOf(currentPage + 1)))
                    .resolver(Placeholder.unparsed("max_page", String.valueOf(maxPage + 1)))
                    .build();
            ItemStack indicator = Utils.item(mat)
                    .setName(Utils.parseMiniMessage(name, ph))
                    .setAmount(Math.max(1, currentPage + 1))
                    .build();
            setItem(state, pageIndicatorSlot, indicator, null);
        }
    }

    private int normalizeSize(int size) {
        return Math.max(9, Math.min(54, ((size + 8) / 9) * 9));
    }

    private int calculateItemsPerPage(int size) {
        return Math.max(1, size - (size > 9 ? 9 : 0));
    }

    private int calculateMaxPage(int totalItems, int itemsPerPage) {
        if (totalItems <= 0 || itemsPerPage <= 0) return 0;
        return (totalItems - 1) / itemsPerPage;
    }


    private int sanitizePage(int page, int maxPage) {
        return Math.max(0, Math.min(page, maxPage));
    }

    @NotNull
    private Material parseMaterial(@Nullable String name, @NotNull Material defaultMaterial) {
        if (name == null) return defaultMaterial;
        Material mat = Material.matchMaterial(name.toUpperCase());
        return mat != null ? mat : defaultMaterial;
    }

    @Override @NotNull
    public Inventory getInventory() {
        return Bukkit.createInventory(this, 9, LegacyComponentSerializer.legacySection().serialize(Component.text("BloraTags GUI Holder")));
    }
}