package com.blorasoft.bloratags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class CommandHandler implements CommandExecutor, TabCompleter {

    private final BloraTags plugin;
    private final Settings settings;
    private final DataManager dataManager;
    private final GuiHandler guiHandler;
    private final MiniMessage miniMessage;

    public static final String PERM_USE = "bloratags.use";
    public static final String PERM_SELECT = "bloratags.select";
    public static final String PERM_CLEAR = "bloratags.clear";
    public static final String PERM_COLOR = "bloratags.color";
    public static final String PERM_APPLY = "bloratags.apply";
    public static final String PERM_ADMIN = "bloratags.admin";
    public static final String PERM_ADMIN_RELOAD = "bloratags.admin.reload";
    public static final String PERM_ADMIN_REVIEW = "bloratags.admin.review";
    public static final String PERM_ADMIN_GRANT = "bloratags.admin.grant";
    public static final String PERM_ADMIN_REVOKE = "bloratags.admin.revoke";
    public static final String PERM_ADMIN_SETSTYLE = "bloratags.admin.setstyle";
    public static final String PERM_ADMIN_CLEARSTYLE = "bloratags.admin.clearstyle";
    public static final String PERM_ADMIN_INFO = "bloratags.admin.info";


    public CommandHandler(@NotNull BloraTags plugin, @NotNull Settings settings, @NotNull DataManager dataManager, @NotNull GuiHandler guiHandler) {
        this.plugin = plugin;
        this.settings = settings;
        this.dataManager = dataManager;
        this.guiHandler = guiHandler;
        this.miniMessage = MiniMessage.miniMessage();
    }

    public void registerCommands() { registerCommand("tags", this, this); registerCommand("btadmin", this, this); }
    private void registerCommand(String name,CommandExecutor ex,TabCompleter tc){ PluginCommand c=plugin.getCommand(name); if(c!=null){c.setExecutor(ex);if(tc!=null)c.setTabCompleter(tc);}else plugin.getLogger().severe("Fail register /"+name);}
    @Override public boolean onCommand(@NotNull CommandSender s,@NotNull Command c,@NotNull String l,@NotNull String[]a){String n=c.getName().toLowerCase(); if(n.equals("tags"))return handleTagsCommand(s,a); else if(n.equals("btadmin"))return handleAdminCommand(s,a); return false;}
    @Nullable @Override public List<String> onTabComplete(@NotNull CommandSender s,@NotNull Command c,@NotNull String l,@NotNull String[]a){String n=c.getName().toLowerCase(); if(n.equals("tags"))return handleTagsTabComplete(s,a); else if(n.equals("btadmin"))return handleAdminTabComplete(s,a); return Collections.emptyList();}


    private boolean handleTagsCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) { settings.sendMessage(sender, "player_only"); return true; }
        if (!player.hasPermission(PERM_USE)) { settings.sendMessage(player, "no_permission"); return true; }
        if (args.length == 0) { guiHandler.openMainMenu(player); return true; }
        String sub = args[0].toLowerCase(); String[] subArgs = args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0];
        switch (sub) {
            case "apply": return handleTagsApplyCommand(player, subArgs);
            case "select": return handleTagsSelectCommand(player, subArgs);
            case "clear": return handleTagsClearCommand(player, subArgs);
            case "color": return handleTagsColorCommand(player, subArgs);
            case "help": settings.sendMessage(player, "command_not_implemented"); return true;
            default: guiHandler.openMainMenu(player); return true;
        }
    }

    private boolean handleTagsApplyCommand(@NotNull Player player, @NotNull String[] args) {
        if (!player.hasPermission(PERM_APPLY)) { settings.sendMessage(player, "no_permission"); return true; }
        if (!settings.isApplicationsEnabled()) { settings.sendMessage(player, "apply_disabled"); return true; }
        if (args.length < 1) { settings.sendMessage(player, "apply_usage"); return true; }
        String tagId = args[0].toLowerCase(); BloraTags.TagDefinition td = dataManager.getTagById(tagId);
        if (td == null) { settings.sendMessage(player, "apply_tag_not_found", Placeholder.unparsed("tag_id", tagId)); return true; }
        if (!td.requiresApplication()) { settings.sendMessage(player, "apply_not_required", Placeholder.unparsed("tag_id", td.id())); return true; }
        dataManager.submitApplication(player, tagId, success -> {});
        return true;
    }

    private boolean handleTagsSelectCommand(@NotNull Player player, @NotNull String[] args) {
        if (!player.hasPermission(PERM_SELECT)) { settings.sendMessage(player, "no_permission"); return true; }
        if (args.length < 1) { settings.sendMessage(player, "select_tag_usage"); return true; }
        String tagId = args[0].toLowerCase();

        dataManager.getPlayerDataAsync(player.getUniqueId()).whenCompleteAsync((playerData, ex) -> {
            runOnMainThread(() -> {
                if (ex != null) { plugin.getLogger().log(Level.SEVERE, "Error fetch data select: " + player.getName(), ex); settings.sendMessage(player, "error_loading_data"); return; }
                if (playerData == null) { settings.sendMessage(player, "error_loading_data"); return; }

                BloraTags.TagDefinition tagDef = dataManager.getTagById(tagId);
                if (tagDef == null) { settings.sendMessage(player, "select_tag_not_found", Placeholder.unparsed("tag_id", tagId)); return; }
                if (!playerData.ownsTag(tagId)) { settings.sendMessage(player, "select_tag_not_owned", Placeholder.component("tag", miniMessage.deserialize(tagDef.display()))); return; }
                if (tagId.equals(playerData.selectedTagId())) { settings.sendMessage(player, "select_tag_already_selected", Placeholder.component("tag", miniMessage.deserialize(tagDef.display()))); return; }

                dataManager.setSelectedTagAsync(player.getUniqueId(), tagId, success -> {
                    runOnMainThread(() -> {
                        if (success) { settings.sendMessage(player, "select_tag_success", Placeholder.component("tag", miniMessage.deserialize(tagDef.display()))); }
                        else { settings.sendMessage(player, "error"); }
                    });
                });
            });
        });
        return true;
    }

    private boolean handleTagsClearCommand(@NotNull Player player, @NotNull String[] args) {
        if (!player.hasPermission(PERM_CLEAR)) { settings.sendMessage(player, "no_permission"); return true; }

        dataManager.getPlayerDataAsync(player.getUniqueId()).whenCompleteAsync((playerData, ex) -> {
            runOnMainThread(() -> {
                if (ex != null) { plugin.getLogger().log(Level.SEVERE, "Error fetch data clear: " + player.getName(), ex); settings.sendMessage(player, "error_loading_data"); return; }
                if (playerData == null) { settings.sendMessage(player, "error_loading_data"); return; }
                if (playerData.selectedTagId() == null) { settings.sendMessage(player, "clear_tag_none_selected"); return; }

                dataManager.clearSelectedTagAsync(player.getUniqueId(), success -> {
                    runOnMainThread(() -> {
                        if (success) { settings.sendMessage(player, "select_tag_cleared"); }
                        else { settings.sendMessage(player, "error"); }
                    });
                });
            });
        });
        return true;
    }

    private boolean handleTagsColorCommand(@NotNull Player player, @NotNull String[] args) {
        if (!player.hasPermission(PERM_COLOR)) { settings.sendMessage(player, "no_permission"); return true; }
        if (args.length < 1) { settings.sendMessage(player, "select_style_usage"); return true; }
        String styleIdInput = args[0]; String styleIdLower = styleIdInput.toLowerCase();

        if (styleIdLower.equals("clear") || styleIdLower.equals("none") || styleIdLower.equals("remove")) {
            dataManager.clearColorSelectionAsync(player.getUniqueId(), success -> {
                runOnMainThread(() -> { if (success) settings.sendMessage(player, "select_style_cleared"); else settings.sendMessage(player, "error"); });
            }); return true;
        }
        BloraTags.TagColor color = dataManager.getColorById(styleIdLower); BloraTags.TagGradient gradient = dataManager.getGradientById(styleIdLower);
        if (color == null && gradient == null) { settings.sendMessage(player, "select_style_not_found", Placeholder.unparsed("style_id", styleIdInput)); return true; }

        Consumer<Boolean> callback = success -> runOnMainThread(() -> {
            if (success) settings.sendMessage(player, "select_style_success", Placeholder.unparsed("style_id", styleIdInput));
            else settings.sendMessage(player, "error");
        });

        if (color != null) { if (color.permission() != null && !player.hasPermission(color.permission())) { settings.sendMessage(player, "no_permission_style"); return true; } dataManager.setSelectedColorAsync(player.getUniqueId(), color.id(), callback); }
        else { if (gradient.permission() != null && !player.hasPermission(gradient.permission())) { settings.sendMessage(player, "no_permission_style"); return true; } dataManager.setSelectedGradientAsync(player.getUniqueId(), gradient.startColorId(), gradient.endColorId(), callback); }
        return true;
    }
    private List<String> handleTagsTabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList(); if (!player.hasPermission(PERM_USE)) return Collections.emptyList();
        List<String> completions = new ArrayList<>(); String currentArg = args[args.length - 1].toLowerCase();
        if (args.length == 1) { List<String> subs=new ArrayList<>(); if(settings.isApplicationsEnabled()&&player.hasPermission(PERM_APPLY))subs.add("apply"); if(player.hasPermission(PERM_SELECT))subs.add("select"); if(player.hasPermission(PERM_CLEAR))subs.add("clear"); if(player.hasPermission(PERM_COLOR))subs.add("color"); StringUtil.copyPartialMatches(currentArg,subs,completions); }
        else if (args.length == 2) { String sub=args[0].toLowerCase(); switch(sub){ case "apply": if(player.hasPermission(PERM_APPLY)){ BloraTags.PlayerTagData d=dataManager.getCachedPlayerData(player.getUniqueId()); Set<String> o=d!=null?d.ownedTagIds():Collections.emptySet(); Collection<BloraTags.TagDefinition> all=dataManager.getAllTagDefinitions(); if(all!=null){List<String> app=all.stream().filter(BloraTags.TagDefinition::requiresApplication).map(BloraTags.TagDefinition::id).filter(id->!o.contains(id)).toList(); StringUtil.copyPartialMatches(currentArg,app,completions);} } break; case "select": if(player.hasPermission(PERM_SELECT)){ BloraTags.PlayerTagData d=dataManager.getCachedPlayerData(player.getUniqueId()); if(d!=null)StringUtil.copyPartialMatches(currentArg,d.ownedTagIds(),completions); } break; case "color": if(player.hasPermission(PERM_COLOR)){ Collection<BloraTags.TagColor> cs=dataManager.getAllColorDefinitions(); Collection<BloraTags.TagGradient> gs=dataManager.getAllGradientDefinitions(); Stream<String> cids=cs!=null?cs.stream().filter(c->c.permission()==null||player.hasPermission(c.permission())).map(BloraTags.TagColor::id):Stream.empty(); Stream<String> gids=gs!=null?gs.stream().filter(g->g.permission()==null||player.hasPermission(g.permission())).map(BloraTags.TagGradient::id):Stream.empty(); List<String> styles=Stream.concat(cids,gids).collect(Collectors.toList()); styles.add("clear"); StringUtil.copyPartialMatches(currentArg,styles,completions); } break; } }
        Collections.sort(completions); return completions;
    }



    private boolean handleAdminCommand(@NotNull CommandSender s,@NotNull String[]a){ if(!s.hasPermission(PERM_ADMIN)){settings.sendMessage(s,"no_permission");return true;} if(a.length==0){sendAdminHelp(s);return true;} String sub=a[0].toLowerCase(); String[]subArgs=a.length>1?Arrays.copyOfRange(a,1,a.length):new String[0]; switch(sub){ case "reload":return handleAdminReload(s,subArgs); case "review":return handleAdminReview(s,subArgs); case "grant":return handleAdminGrant(s,subArgs); case "revoke":return handleAdminRevoke(s,subArgs); case "setstyle":return handleAdminSetStyle(s,subArgs); case "clearstyle":return handleAdminClearStyle(s,subArgs); case "info":return handleAdminInfo(s,subArgs); case "help":sendAdminHelp(s);return true; default:settings.sendMessage(s,"unknown_command",Placeholder.unparsed("command","/btadmin "+sub));sendAdminHelp(s);return true; } }
    private void sendAdminHelp(@NotNull CommandSender s){ settings.sendMessage(s,"admin_usage_header"); if(s.hasPermission(PERM_ADMIN_RELOAD))settings.sendMessage(s,"admin_usage_reload"); if(s.hasPermission(PERM_ADMIN_REVIEW)&&settings.isApplicationsEnabled())settings.sendMessage(s,"admin_usage_review"); if(s.hasPermission(PERM_ADMIN_GRANT))settings.sendMessage(s,"admin_usage_grant"); if(s.hasPermission(PERM_ADMIN_REVOKE))settings.sendMessage(s,"admin_usage_revoke"); if(s.hasPermission(PERM_ADMIN_SETSTYLE))settings.sendMessage(s,"admin_usage_setstyle"); if(s.hasPermission(PERM_ADMIN_CLEARSTYLE))settings.sendMessage(s,"admin_usage_clearstyle"); if(s.hasPermission(PERM_ADMIN_INFO))settings.sendMessage(s,"admin_usage_info"); settings.sendMessage(s,"admin_usage_help"); }
    private boolean handleAdminReload(@NotNull CommandSender s,@NotNull String[]a){ if(!s.hasPermission(PERM_ADMIN_RELOAD)){settings.sendMessage(s,"no_permission");return true;} settings.sendMessage(s,"admin_reloading"); long t=System.currentTimeMillis(); settings.reloadConfigs(); settings.sendMessage(s,"admin_reload_success",Placeholder.unparsed("time",String.valueOf(System.currentTimeMillis()-t))); return true; }
    private boolean handleAdminReview(@NotNull CommandSender s,@NotNull String[]a){ if(!(s instanceof Player p)){settings.sendMessage(s,"player_only");return true;} if(!p.hasPermission(PERM_ADMIN_REVIEW)){settings.sendMessage(p,"no_permission");return true;} if(!settings.isApplicationsEnabled()){settings.sendMessage(p,"apply_disabled");return true;} guiHandler.openAdminReview(p,0); return true; }
    @Nullable private OfflinePlayer getOfflinePlayer(CommandSender s,String n){ OfflinePlayer p=Bukkit.getOfflinePlayer(n); if(!p.hasPlayedBefore()&&!p.isOnline()){settings.sendMessage(s,"admin_player_not_found",Placeholder.unparsed("player",n));return null;} return p; }


    private boolean handleAdminGrant(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN_GRANT)) { settings.sendMessage(sender, "no_permission"); return true; }
        if (args.length < 2) { settings.sendMessage(sender, "admin_grant_usage"); return true; }
        String playerName = args[0]; String tagId = args[1].toLowerCase();
        OfflinePlayer targetPlayer = getOfflinePlayer(sender, playerName); if (targetPlayer == null) return true;
        UUID targetUuid = targetPlayer.getUniqueId(); String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : playerName;
        BloraTags.TagDefinition tagDef = dataManager.getTagById(tagId);
        if (tagDef == null) { settings.sendMessage(sender, "admin_tag_not_found", Placeholder.unparsed("tag_id", args[1])); return true; }

        dataManager.addOwnedTagAsync(targetUuid, tagId, success -> runOnMainThread(() -> {
            if (success) {
                settings.sendMessage(sender, "admin_grant_success", Placeholder.unparsed("player", targetName), Placeholder.component("tag", miniMessage.deserialize(tagDef.display())));
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) settings.sendMessage(targetPlayer.getPlayer(), "admin_notify_granted", Placeholder.component("tag", miniMessage.deserialize(tagDef.display())));
            } else settings.sendMessage(sender, "admin_grant_fail_already_owned", Placeholder.unparsed("player", targetName), Placeholder.component("tag", miniMessage.deserialize(tagDef.display())));
        }));
        return true;
    }

    private boolean handleAdminRevoke(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN_REVOKE)) { settings.sendMessage(sender, "no_permission"); return true; }
        if (args.length < 2) { settings.sendMessage(sender, "admin_revoke_usage"); return true; }
        String playerName = args[0]; String tagId = args[1].toLowerCase();
        OfflinePlayer targetPlayer = getOfflinePlayer(sender, playerName); if (targetPlayer == null) return true;
        UUID targetUuid = targetPlayer.getUniqueId(); String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : playerName;
        BloraTags.TagDefinition tagDef = dataManager.getTagById(tagId); String tagNameForMessage = (tagDef != null) ? tagDef.display() : tagId;

        dataManager.removeOwnedTagAsync(targetUuid, tagId, success -> runOnMainThread(() -> {
            if (success) {
                settings.sendMessage(sender, "admin_revoke_success", Placeholder.unparsed("player", targetName), Placeholder.component("tag", miniMessage.deserialize(tagNameForMessage)));
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) settings.sendMessage(targetPlayer.getPlayer(), "admin_notify_revoked", Placeholder.component("tag", miniMessage.deserialize(tagNameForMessage)));
            } else settings.sendMessage(sender, "admin_revoke_not_owned", Placeholder.unparsed("player", targetName), Placeholder.component("tag", miniMessage.deserialize(tagNameForMessage)));


        }));
        return true;
    }

    private boolean handleAdminSetStyle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN_SETSTYLE)) { settings.sendMessage(sender, "no_permission"); return true; }
        if (args.length < 2) { settings.sendMessage(sender, "admin_setstyle_usage"); return true; }
        String playerName = args[0]; String styleIdInput = args[1]; String styleIdLower = styleIdInput.toLowerCase();
        OfflinePlayer targetPlayer = getOfflinePlayer(sender, playerName); if (targetPlayer == null) return true;
        UUID targetUuid = targetPlayer.getUniqueId(); String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : playerName;
        BloraTags.TagColor color = dataManager.getColorById(styleIdLower); BloraTags.TagGradient gradient = dataManager.getGradientById(styleIdLower);
        if (color == null && gradient == null) { settings.sendMessage(sender, "select_style_not_found", Placeholder.unparsed("style_id", styleIdInput)); return true; }

        Consumer<Boolean> callback = success -> runOnMainThread(() -> {
            if (success) {
                settings.sendMessage(sender, "admin_setstyle_success", Placeholder.unparsed("player", targetName), Placeholder.unparsed("style", styleIdInput));
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) settings.sendMessage(targetPlayer.getPlayer(), "admin_notify_style_set", Placeholder.unparsed("style", styleIdInput));
            } else settings.sendMessage(sender, "admin_setstyle_fail", Placeholder.unparsed("player", targetName), Placeholder.unparsed("style", styleIdInput));
        });

        if (color != null) dataManager.setSelectedColorAsync(targetUuid, color.id(), callback);
        else dataManager.setSelectedGradientAsync(targetUuid, gradient.startColorId(), gradient.endColorId(), callback);
        return true;
    }

    private boolean handleAdminClearStyle(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN_CLEARSTYLE)) { settings.sendMessage(sender, "no_permission"); return true; }
        if (args.length < 1) { settings.sendMessage(sender, "admin_clearstyle_usage"); return true; }
        String playerName = args[0]; OfflinePlayer targetPlayer = getOfflinePlayer(sender, playerName); if (targetPlayer == null) return true;
        UUID targetUuid = targetPlayer.getUniqueId(); String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : playerName;

        dataManager.clearColorSelectionAsync(targetUuid, success -> runOnMainThread(() -> {
            if (success) {
                settings.sendMessage(sender, "admin_clearstyle_success", Placeholder.unparsed("player", targetName));
                if (targetPlayer.isOnline() && targetPlayer.getPlayer() != null) settings.sendMessage(targetPlayer.getPlayer(), "admin_notify_style_cleared");
            } else settings.sendMessage(sender, "admin_clearstyle_fail", Placeholder.unparsed("player", targetName));
        }));
        return true;
    }

    private boolean handleAdminInfo(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN_INFO)) { settings.sendMessage(sender, "no_permission"); return true; }
        if (args.length < 1) { settings.sendMessage(sender, "admin_info_usage"); return true; }
        String playerName = args[0]; OfflinePlayer targetPlayer = getOfflinePlayer(sender, playerName); if (targetPlayer == null) return true;
        UUID targetUuid = targetPlayer.getUniqueId(); String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : playerName;


        dataManager.getPlayerDataAsync(targetUuid).whenCompleteAsync((playerData, ex) -> {
            runOnMainThread(() -> {
                settings.sendMessage(sender, "admin_info_header", Placeholder.unparsed("player", targetName));
                if (ex != null) { plugin.getLogger().log(Level.SEVERE, "Error fetch data info: " + targetName, ex); settings.sendMessage(sender, "error_loading_data_admin", Placeholder.unparsed("player", targetName)); return; }
                if (playerData == null) { settings.sendMessage(sender, "error_loading_data_admin", Placeholder.unparsed("player", targetName)); /* Show empty state */ return; }

                Component selectedTagComp = Component.text("None", NamedTextColor.GRAY);
                if (playerData.selectedTagId() != null) {
                    BloraTags.TagDefinition td = dataManager.getTagById(playerData.selectedTagId());
                    if (td != null) { selectedTagComp = miniMessage.deserialize(td.display()); Component styled = applyStyleToComponent(selectedTagComp, playerData); settings.sendMessage(sender, "admin_info_selected_tag", Placeholder.component("tag", styled)); }
                    else { settings.sendMessage(sender, "admin_info_selected_tag", Placeholder.component("tag", Component.text("Invalid: " + playerData.selectedTagId(), NamedTextColor.RED))); }
                } else { settings.sendMessage(sender, "admin_info_selected_tag", Placeholder.component("tag", selectedTagComp)); }

                Component selectedStyleComp = Component.text("None", NamedTextColor.GRAY);
                if (playerData.hasSelectedGradient()) {
                    BloraTags.TagColor sc = dataManager.getColorById(playerData.selectedGradientStartId()); BloraTags.TagColor ec = dataManager.getColorById(playerData.selectedGradientEndId());
                    Optional<String> gIdOpt = dataManager.findGradientId(playerData.selectedGradientStartId(), playerData.selectedGradientEndId()); String gId = gIdOpt.orElse("Unknown");
                    if (sc != null && ec != null) { selectedStyleComp = miniMessage.deserialize("<gradient:" + sc.value() + ":" + ec.value() + ">" + gId + "</gradient>"); }
                    else { selectedStyleComp = Component.text("Invalid Gradient", NamedTextColor.RED); }
                } else if (playerData.hasSelectedColor()) {
                    BloraTags.TagColor c = dataManager.getColorById(playerData.selectedColorId());
                    if (c != null) { selectedStyleComp = miniMessage.deserialize("<" + c.value() + ">" + c.id() + "</" + c.value() + ">"); }
                    else { selectedStyleComp = Component.text("Invalid Color", NamedTextColor.RED); }
                }
                settings.sendMessage(sender, "admin_info_selected_style", Placeholder.component("style", selectedStyleComp));

                Set<String> owned = playerData.ownedTagIds();
                if (owned.isEmpty()) { settings.sendMessage(sender, "admin_info_owned_tags_none"); }
                else { List<Component> tags=owned.stream().sorted().map(id->{BloraTags.TagDefinition td=dataManager.getTagById(id);return td!=null?miniMessage.deserialize(id+" (<gray>"+td.display()+"</gray>)"):Component.text("Invalid: "+id,NamedTextColor.RED);}).toList(); Component joined=Component.join(JoinConfiguration.separator(Component.text(", ",NamedTextColor.GRAY)),tags); settings.sendMessage(sender,"admin_info_owned_tags",Placeholder.unparsed("count",String.valueOf(owned.size())),Placeholder.component("tags",joined)); }
            });
        });
        return true;
    }

    private Component applyStyleToComponent(Component c, BloraTags.PlayerTagData d){ if(d.hasSelectedGradient()){ BloraTags.TagColor sc=dataManager.getColorById(d.selectedGradientStartId()); BloraTags.TagColor ec=dataManager.getColorById(d.selectedGradientEndId()); if(sc!=null&&ec!=null){String t=LegacyComponentSerializer.legacySection().serialize(c); return miniMessage.deserialize("<gradient:"+sc.value()+":"+ec.value()+">"+t+"</gradient>");} } else if(d.hasSelectedColor()){ BloraTags.TagColor co=dataManager.getColorById(d.selectedColorId()); if(co!=null){String t=LegacyComponentSerializer.legacySection().serialize(c); return miniMessage.deserialize("<"+co.value()+">"+t+"</"+co.value()+">");} } return c; }

    private List<String> handleAdminTabComplete(@NotNull CommandSender sender, @NotNull String[] args) {
        if (!sender.hasPermission(PERM_ADMIN)) return Collections.emptyList();
        List<String> completions = new ArrayList<>(); String currentArg = args[args.length - 1].toLowerCase();
        if (args.length == 1) { List<String> subs=new ArrayList<>(List.of("reload","help")); if(settings.isApplicationsEnabled()&&sender.hasPermission(PERM_ADMIN_REVIEW))subs.add("review"); if(sender.hasPermission(PERM_ADMIN_GRANT))subs.add("grant"); if(sender.hasPermission(PERM_ADMIN_REVOKE))subs.add("revoke"); if(sender.hasPermission(PERM_ADMIN_SETSTYLE))subs.add("setstyle"); if(sender.hasPermission(PERM_ADMIN_CLEARSTYLE))subs.add("clearstyle"); if(sender.hasPermission(PERM_ADMIN_INFO))subs.add("info"); StringUtil.copyPartialMatches(currentArg,subs,completions); }
        else if (args.length == 2) { String sub=args[0].toLowerCase(); if(Set.of("grant","revoke","setstyle","clearstyle","info").contains(sub)){List<String> names=Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()); StringUtil.copyPartialMatches(currentArg,names,completions);} }
        else if (args.length == 3) { String sub=args[0].toLowerCase(); switch(sub){ case "grant","revoke": if(sender.hasPermission(PERM_ADMIN_GRANT)||sender.hasPermission(PERM_ADMIN_REVOKE)){ Collection<String> ids=dataManager.getAllTagIds(); if(ids!=null)StringUtil.copyPartialMatches(currentArg,ids,completions); } break; case "setstyle": if(sender.hasPermission(PERM_ADMIN_SETSTYLE)){ Collection<BloraTags.TagColor> cs=dataManager.getAllColorDefinitions(); Collection<BloraTags.TagGradient> gs=dataManager.getAllGradientDefinitions(); Stream<String> cids=cs!=null?cs.stream().map(BloraTags.TagColor::id):Stream.empty(); Stream<String> gids=gs!=null?gs.stream().map(BloraTags.TagGradient::id):Stream.empty(); List<String> styles=Stream.concat(cids,gids).sorted().collect(Collectors.toList()); StringUtil.copyPartialMatches(currentArg,styles,completions); } break; } }
        Collections.sort(completions); return completions;
    }

    private void runOnMainThread(Runnable task) { if (Bukkit.isPrimaryThread()) task.run(); else Bukkit.getScheduler().runTask(plugin, task); }
}