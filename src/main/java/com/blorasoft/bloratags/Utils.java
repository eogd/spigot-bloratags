package com.blorasoft.bloratags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.stream.Collectors;

public final class Utils {

    private static final MiniMessage miniMessage = MiniMessage.miniMessage();

    private Utils() { }

    @NotNull
    public static Component parseMiniMessage(@Nullable String text, @NotNull TagResolver... resolvers) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return miniMessage.deserialize(text, resolvers).decoration(TextDecoration.ITALIC, false);
        } catch (Exception e) {
            Bukkit.getLogger().warning("[BloraTags] Failed to parse MiniMessage: " + text + " - Error: " + e.getMessage());
            return Component.text("<Invalid MiniMessage: " + text + ">");
        }
    }

    @NotNull
    public static List<Component> parseMiniMessageList(@Nullable List<String> lines, @NotNull TagResolver... resolvers) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        return lines.stream()
                .map(line -> parseMiniMessage(line, resolvers))
                .collect(Collectors.toList());
    }

    @NotNull
    public static ItemStack createFiller(@NotNull Material material) {
        return item(material).setName(Component.empty()).build();
    }

    @NotNull
    public static GuiItemBuilder item(@NotNull Material material) {
        return new GuiItemBuilder(material);
    }

    public static class GuiItemBuilder {
        private final ItemStack itemStack;
        private final ItemMeta itemMeta;

        private GuiItemBuilder(@NotNull Material material) {
            this.itemStack = new ItemStack(material);
            this.itemMeta = itemStack.getItemMeta();
            if (this.itemMeta == null) {
                Bukkit.getLogger().warning("[BloraTags] Failed to get ItemMeta for material: " + material);
            }
        }

        public GuiItemBuilder setName(@NotNull Component name) {
            if (itemMeta != null) {
                itemMeta.setDisplayName(LegacyComponentSerializer.legacySection().serialize(name.decoration(TextDecoration.ITALIC, false)));
            }
            return this;
        }

        public GuiItemBuilder setLore(@NotNull List<Component> lore) {
            if (itemMeta != null) {
                List<String> legacyLore = lore.stream()
                        .map(line -> LegacyComponentSerializer.legacySection().serialize(line.decoration(TextDecoration.ITALIC, false)))
                        .collect(Collectors.toList());
                itemMeta.setLore(legacyLore);
            }
            return this;
        }

        public GuiItemBuilder setAmount(int amount) {
            itemStack.setAmount(amount);
            return this;
        }

        public <T, Z> GuiItemBuilder addPersistentData(@NotNull BloraTags plugin, @NotNull String key, @NotNull PersistentDataType<T, Z> type, @NotNull Z value) {
            if (itemMeta != null) {
                NamespacedKey namespacedKey = new NamespacedKey(plugin, key);
                itemMeta.getPersistentDataContainer().set(namespacedKey, type, value);
            }
            return this;
        }

        public GuiItemBuilder setGlow(boolean glow) {
            if (itemMeta != null) {
                if (glow) {
                    Enchantment glowEnchant = Enchantment.UNBREAKING;
                    try {

                    } catch (Exception ignored) {}

                    if (glowEnchant != null) {
                        itemMeta.addEnchant(glowEnchant, 1, true);
                        itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    } else {
                        Bukkit.getLogger().warning("[BloraTags] Could not apply glow effect: Unbreaking enchantment not found?");
                    }
                } else {
                    itemMeta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
            }
            return this;
        }

        public GuiItemBuilder addItemFlags(ItemFlag... flags) {
            if (itemMeta != null) {
                itemMeta.addItemFlags(flags);
            }
            return this;
        }

        @NotNull
        public ItemStack build() {
            if (itemMeta != null) {
                itemStack.setItemMeta(itemMeta);
            }
            return itemStack;
        }
    }
}