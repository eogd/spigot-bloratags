package com.blorasoft.bloratags;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Manages runtime data, assuming DatabaseManager methods returning PlayerTagData might return null.
 */
public class DataManager {

    private final BloraTags plugin;
    private final Settings settings;
    private final DatabaseManager databaseManager;
    private final Integrations integrations;
    private final Gson gson;
    private final MiniMessage miniMessage;

    private final Map<UUID, BloraTags.PlayerTagData> playerDataCache = new ConcurrentHashMap<>();
    private final Map<String, BloraTags.TagDefinition> tagDefinitions = new ConcurrentHashMap<>();
    private final Map<String, BloraTags.TagColor> tagColors = new ConcurrentHashMap<>();
    private final Map<String, BloraTags.TagGradient> tagGradients = new ConcurrentHashMap<>();
    private final List<BloraTags.TagApplication> pendingApplications = new CopyOnWriteArrayList<>();

    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> savingPlayers = ConcurrentHashMap.newKeySet();

    public DataManager(@NotNull BloraTags plugin, @NotNull Settings settings, @NotNull DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.settings = settings;
        this.databaseManager = databaseManager;
        this.integrations = plugin.getIntegrations();
        this.gson = new GsonBuilder().create();
        this.miniMessage = MiniMessage.miniMessage();
        loadPendingApplicationsAsync();
    }

    public void loadStaticData() {
        tagDefinitions.clear(); tagColors.clear(); tagGradients.clear();
        loadTagDefinitionsFromFile(); loadTagColorsFromFile(); loadTagGradientsFromFile();
        validateGradients();
    }
    private void loadTagDefinitionsFromFile() {
        File tagsFile = new File(plugin.getDataFolder(), "tags.yml");
        if (!tagsFile.exists()) { plugin.getLogger().warning("tags.yml not found!"); return; }
        FileConfiguration tagsConfig = YamlConfiguration.loadConfiguration(tagsFile);
        ConfigurationSection tagsSection = tagsConfig.getConfigurationSection("tags");
        if (tagsSection == null) { plugin.getLogger().warning("No 'tags' section in tags.yml!"); return; }
        for (String id : tagsSection.getKeys(false)) {
            ConfigurationSection tagSection = tagsSection.getConfigurationSection(id);
            if (tagSection != null) {
                tagDefinitions.put(id.toLowerCase(), new BloraTags.TagDefinition(
                        id.toLowerCase(), tagSection.getString("display", id), tagSection.getString("description", ""),
                        tagSection.getString("permission"), tagSection.getBoolean("buyable", false), tagSection.getDouble("price", 0.0),
                        tagSection.getBoolean("requires_application", false), tagSection.getDouble("application_fee", 0.0),
                        tagSection.getBoolean("application_refund_on_reject", true) ));
            }
        }
        plugin.getLogger().info("Loaded " + tagDefinitions.size() + " tag definitions from tags.yml.");
    }
    private void loadTagColorsFromFile() {
        File colorsFile = new File(plugin.getDataFolder(), "colors.yml");
        if (!colorsFile.exists()) { plugin.getLogger().warning("colors.yml not found!"); return; }
        FileConfiguration colorsConfig = YamlConfiguration.loadConfiguration(colorsFile);
        ConfigurationSection colorsSection = colorsConfig.getConfigurationSection("colors");
        if (colorsSection == null) { plugin.getLogger().warning("No 'colors' section in colors.yml!"); return; }
        for (String id : colorsSection.getKeys(false)) {
            ConfigurationSection colorSection = colorsSection.getConfigurationSection(id);
            if (colorSection != null) {
                tagColors.put(id.toLowerCase(), new BloraTags.TagColor(
                        id.toLowerCase(), colorSection.getString("value", "white"), colorSection.getString("permission") ));
            }
        }
        plugin.getLogger().info("Loaded " + tagColors.size() + " color definitions from colors.yml.");
    }
    private void loadTagGradientsFromFile() {
        File gradientsFile = new File(plugin.getDataFolder(), "gradients.yml");
        if (!gradientsFile.exists()) { plugin.getLogger().warning("gradients.yml not found!"); return; }
        FileConfiguration gradientsConfig = YamlConfiguration.loadConfiguration(gradientsFile);
        ConfigurationSection gradientsSection = gradientsConfig.getConfigurationSection("gradients");
        if (gradientsSection == null) { plugin.getLogger().warning("No 'gradients' section in gradients.yml!"); return; }
        for (String id : gradientsSection.getKeys(false)) {
            ConfigurationSection gradientSection = gradientsSection.getConfigurationSection(id);
            if (gradientSection != null) {
                String start = gradientSection.getString("start_color_id"); String end = gradientSection.getString("end_color_id");
                if (start != null && end != null) {
                    tagGradients.put(id.toLowerCase(), new BloraTags.TagGradient(
                            id.toLowerCase(), start.toLowerCase(), end.toLowerCase(), gradientSection.getString("permission") ));
                } else { plugin.getLogger().warning("Gradient '" + id + "' missing start/end color ID."); }
            }
        }
        plugin.getLogger().info("Loaded " + tagGradients.size() + " gradient definitions from gradients.yml.");
    }
    private void validateGradients() {
        List<String> invalidGradientIds = new ArrayList<>();
        for (BloraTags.TagGradient gradient : tagGradients.values()) {
            if (!tagColors.containsKey(gradient.startColorId().toLowerCase()) || !tagColors.containsKey(gradient.endColorId().toLowerCase())) {
                plugin.getLogger().warning("Gradient '" + gradient.id() + "' references undefined color(s).");
                invalidGradientIds.add(gradient.id().toLowerCase());
            }
        }
        invalidGradientIds.forEach(tagGradients::remove);
        if (!invalidGradientIds.isEmpty()) { plugin.getLogger().warning("Removed " + invalidGradientIds.size() + " invalid gradients."); }
    }
    private void loadPendingApplicationsAsync() {
        databaseManager.loadPendingApplicationsAsync().thenAcceptAsync(apps -> {
            pendingApplications.clear();
            if (apps != null) { pendingApplications.addAll(apps); plugin.getLogger().info("Loaded " + pendingApplications.size() + " pending applications."); }
            else { plugin.getLogger().severe("Failed to load pending applications!"); }
        }, databaseManager::runSyncTask);
    }

    public void loadPlayer(@NotNull UUID playerUuid) {
        if (playerDataCache.containsKey(playerUuid) || !loadingPlayers.add(playerUuid)) { return; }
        plugin.getLogger().fine("Loading data for " + playerUuid + "...");
        databaseManager.loadPlayerDataAsync(playerUuid).whenCompleteAsync((playerData, ex) -> {
            try {
                if (ex != null) { plugin.getLogger().log(Level.SEVERE, "Error loading data for " + playerUuid, ex); playerDataCache.put(playerUuid, new BloraTags.PlayerTagData(playerUuid)); }
                else if (playerData != null) { playerDataCache.put(playerUuid, playerData); plugin.getLogger().fine("Data for " + playerUuid + " loaded."); }
                else { BloraTags.PlayerTagData d = new BloraTags.PlayerTagData(playerUuid); playerDataCache.put(playerUuid, d); plugin.getLogger().fine("Created default data for " + playerUuid + "."); }
            } finally { loadingPlayers.remove(playerUuid); }
        }, databaseManager.getExecutor());
    }
    public void unloadPlayer(@NotNull UUID u){loadingPlayers.remove(u); BloraTags.PlayerTagData d=playerDataCache.remove(u); if(d!=null)savePlayer(d);}
    private void savePlayer(@NotNull BloraTags.PlayerTagData data){UUID u=data.playerUuid(); if(!savingPlayers.add(u))return; databaseManager.savePlayerDataAsync(data).whenCompleteAsync((s,e)->{savingPlayers.remove(u); if(e!=null)plugin.getLogger().log(Level.SEVERE,"Err saving "+u,e); else if(s==null||!s)plugin.getLogger().warning("Failed save "+u);},databaseManager.getExecutor());}

    @Nullable public BloraTags.PlayerTagData getCachedPlayerData(@NotNull UUID playerUuid) { return playerDataCache.get(playerUuid); }

    /**
     * Gets player data, loading it asynchronously if not cached.
     * Returns a CompletableFuture which might complete with null if loading fails or data not found.
     *
     * @param playerUuid UUID of the player.
     * @return A CompletableFuture containing the PlayerTagData (nullable).
     */
    @NotNull
    public CompletableFuture<BloraTags.PlayerTagData> getPlayerDataAsync(@NotNull UUID playerUuid) {
        if (playerDataCache.containsKey(playerUuid)) {
            return CompletableFuture.completedFuture(playerDataCache.get(playerUuid));
        }
        if (loadingPlayers.contains(playerUuid)) {
            return CompletableFuture.supplyAsync(() -> {
                int attempts = 0;
                while (loadingPlayers.contains(playerUuid) && attempts < 40) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                    attempts++;
                }
                return playerDataCache.get(playerUuid);
            }, databaseManager.getExecutor());
        }
        if (loadingPlayers.add(playerUuid)) {
            plugin.getLogger().fine("Initiating data load via getPlayerDataAsync for " + playerUuid);
            return databaseManager.loadPlayerDataAsync(playerUuid).whenCompleteAsync((playerData, ex) -> {
                        try {
                            if (ex != null) { plugin.getLogger().log(Level.SEVERE, "Error getPlayerDataAsync load " + playerUuid, ex); playerDataCache.put(playerUuid, new BloraTags.PlayerTagData(playerUuid)); }
                            else if (playerData != null) { playerDataCache.put(playerUuid, playerData); plugin.getLogger().fine("Loaded via getPlayerDataAsync " + playerUuid); }
                            else { playerDataCache.put(playerUuid, new BloraTags.PlayerTagData(playerUuid)); plugin.getLogger().fine("Created default via getPlayerDataAsync " + playerUuid); }
                        } finally { loadingPlayers.remove(playerUuid); }
                    }, databaseManager.getExecutor())
                    .thenApply(ignoredVoid -> playerDataCache.get(playerUuid));
        } else {
            return CompletableFuture.supplyAsync(() -> {
                int attempts = 0;
                while (!playerDataCache.containsKey(playerUuid) && attempts < 20) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
                    attempts++;
                }
                return playerDataCache.get(playerUuid);
            }, databaseManager.getExecutor());
        }
    }

    @Nullable public BloraTags.TagDefinition getTagById(@NotNull String id){return tagDefinitions.get(id.toLowerCase());}
    @Nullable public BloraTags.TagColor getColorById(@NotNull String id){return tagColors.get(id.toLowerCase());}
    @Nullable public BloraTags.TagGradient getGradientById(@NotNull String id){return tagGradients.get(id.toLowerCase());}
    @NotNull public Collection<String> getAllTagIds(){return Collections.unmodifiableSet(tagDefinitions.keySet());}
    @NotNull public Collection<BloraTags.TagDefinition> getAllTagDefinitions(){return Collections.unmodifiableCollection(tagDefinitions.values());}
    @NotNull public Collection<BloraTags.TagColor> getAllColorDefinitions(){return Collections.unmodifiableCollection(tagColors.values());}
    @NotNull public Collection<BloraTags.TagGradient> getAllGradientDefinitions(){return Collections.unmodifiableCollection(tagGradients.values());}
    @NotNull public Optional<String> findGradientId(@Nullable String sId,@Nullable String eId){if(sId==null||eId==null)return Optional.empty(); String s=sId.toLowerCase(),e=eId.toLowerCase(); for(var g:tagGradients.values())if(g.startColorId().equals(s)&&g.endColorId().equals(e))return Optional.of(g.id()); return Optional.empty();}
    @NotNull public Map<String,BloraTags.TagDefinition> getAllLoadedTags(){return Collections.unmodifiableMap(tagDefinitions);}
    @NotNull public Map<String,BloraTags.TagColor> getAllLoadedColors(){return Collections.unmodifiableMap(tagColors);}
    @NotNull public Map<String,BloraTags.TagGradient> getAllLoadedGradients(){return Collections.unmodifiableMap(tagGradients);}
    @NotNull public CompletableFuture<List<BloraTags.TagApplication>>getPendingApplicationsAsync(){return CompletableFuture.completedFuture(new ArrayList<>(pendingApplications));}

    private void handlePlayerData(UUID playerUuid, Consumer<BloraTags.PlayerTagData> dataConsumer, Consumer<Boolean> finalCallback) {
        getPlayerDataAsync(playerUuid).whenCompleteAsync((playerData, ex) -> {
            if (ex != null) { plugin.getLogger().log(Level.SEVERE, "Error getting player data for operation: " + playerUuid, ex); runOnMainThread(() -> finalCallback.accept(false)); return; }
            if (playerData != null) {
                dataConsumer.accept(playerData);
            } else { plugin.getLogger().warning("Player data null for operation: " + playerUuid); runOnMainThread(() -> finalCallback.accept(false)); }
        }, databaseManager.getExecutor());
    }

    public void setSelectedTagAsync(@NotNull UUID u, @Nullable String id, @NotNull Consumer<Boolean> cb){handlePlayerData(u, d->{String fId=(id!=null)?id.toLowerCase():null; if(fId!=null&&!d.ownsTag(fId)){runOnMainThread(()->cb.accept(false));return;} BloraTags.PlayerTagData nD=d.withSelectedTagId(fId); playerDataCache.put(u,nD); savePlayer(nD); runOnMainThread(()->cb.accept(true));}, cb);}
    public void clearSelectedTagAsync(@NotNull UUID u, @NotNull Consumer<Boolean> cb){setSelectedTagAsync(u,null,cb);}
    public void setSelectedColorAsync(@NotNull UUID u, @NotNull String id, @NotNull Consumer<Boolean> cb){handlePlayerData(u,d->{BloraTags.TagColor c=getColorById(id); if(c==null){runOnMainThread(()->cb.accept(false));return;} BloraTags.PlayerTagData nD=d.withSelectedColorId(c.id().toLowerCase()); playerDataCache.put(u,nD); savePlayer(nD); runOnMainThread(()->cb.accept(true));},cb);}
    public void setSelectedGradientAsync(@NotNull UUID u, @NotNull String sId, @NotNull String eId, @NotNull Consumer<Boolean> cb){handlePlayerData(u,d->{BloraTags.TagColor sc=getColorById(sId); BloraTags.TagColor ec=getColorById(eId); if(sc==null||ec==null){runOnMainThread(()->cb.accept(false));return;} BloraTags.PlayerTagData nD=d.withSelectedGradient(sc.id().toLowerCase(),ec.id().toLowerCase()); playerDataCache.put(u,nD); savePlayer(nD); runOnMainThread(()->cb.accept(true));},cb);}
    public void clearColorSelectionAsync(@NotNull UUID u, @NotNull Consumer<Boolean> cb){handlePlayerData(u,d->{if(!d.hasSelectedColor()&&!d.hasSelectedGradient()){runOnMainThread(()->cb.accept(true));return;} BloraTags.PlayerTagData nD=d.withSelectedGradient(null,null); playerDataCache.put(u,nD); savePlayer(nD); runOnMainThread(()->cb.accept(true));},cb);}
    public void addOwnedTagAsync(@NotNull UUID u, @NotNull String id, @NotNull Consumer<Boolean> cb){handlePlayerData(u,d->{String lId=id.toLowerCase(); if(d.ownsTag(lId)){runOnMainThread(()->cb.accept(true));return;} Set<String> nO=new HashSet<>(d.ownedTagIds()); nO.add(lId); BloraTags.PlayerTagData nD=d.withOwnedTagIds(nO); playerDataCache.put(u,nD); savePlayer(nD); runOnMainThread(()->cb.accept(true));},cb);}
    public void removeOwnedTagAsync(@NotNull UUID u, @NotNull String id, @NotNull Consumer<Boolean> cb){handlePlayerData(u,d->{String lId=id.toLowerCase(); if(!d.ownsTag(lId)){runOnMainThread(()->cb.accept(true));return;} Set<String> nO=new HashSet<>(d.ownedTagIds()); nO.remove(lId); String nSId=lId.equals(d.selectedTagId())?null:d.selectedTagId(); BloraTags.PlayerTagData nD=d.withOwnedTagIds(nO).withSelectedTagId(nSId); playerDataCache.put(u,nD); savePlayer(nD); runOnMainThread(()->cb.accept(true));},cb);}

    public void submitApplication(@NotNull Player p, @NotNull String id, @NotNull Consumer<Boolean> cb){ UUID u=p.getUniqueId(); String lId=id.toLowerCase(); handlePlayerData(u, d->runOnMainThread(()->{ BloraTags.TagDefinition td=getTagById(lId); if(td==null){settings.sendMessage(p,"apply_tag_not_found",Placeholder.unparsed("tag_id",id));cb.accept(false);return;} if(!td.requiresApplication()){settings.sendMessage(p,"apply_not_required",Placeholder.unparsed("tag_id",td.id()));cb.accept(false);return;} if(d.ownsTag(lId)){settings.sendMessage(p,"apply_already_owned",Placeholder.component("tag",miniMessage.deserialize(td.display())));cb.accept(false);return;} boolean ap=pendingApplications.stream().anyMatch(a->a.playerUuid().equals(u)&&a.tagId().equals(lId)); if(ap){settings.sendMessage(p,"apply_already_pending",Placeholder.component("tag",miniMessage.deserialize(td.display())));cb.accept(false);return;} if(td.applicationFee()>0){ if(integrations==null||!integrations.hasEconomy()){settings.sendMessage(p,"apply_economy_error");cb.accept(false);return;} if(!integrations.hasEnoughMoney(p,td.applicationFee())){settings.sendMessage(p,"apply_cannot_afford",Placeholder.unparsed("formatted_price",integrations.formatMoney(td.applicationFee())));cb.accept(false);return;} if(!integrations.withdrawMoney(p,td.applicationFee())){settings.sendMessage(p,"apply_withdraw_fail",Placeholder.unparsed("formatted_price",integrations.formatMoney(td.applicationFee())));cb.accept(false);return;} } BloraTags.TagApplication app=new BloraTags.TagApplication(-1L,u,p.getName(),lId,System.currentTimeMillis()); databaseManager.saveNewApplicationAsync(app).whenCompleteAsync((sa,se)->runOnMainThread(()->{ if(se!=null){plugin.getLogger().log(Level.SEVERE,"Err saving app",se); settings.sendMessage(p,"apply_submit_fail",Placeholder.component("tag",miniMessage.deserialize(td.display()))); tryRefund(p,td); cb.accept(false);} else if(sa!=null){pendingApplications.add(sa); settings.sendMessage(p,"apply_submit_success",Placeholder.component("tag",miniMessage.deserialize(td.display()))); notifyAdminsOfNewApplication(sa); cb.accept(true);} else{plugin.getLogger().warning("saveNewApp null"); settings.sendMessage(p,"apply_submit_fail",Placeholder.component("tag",miniMessage.deserialize(td.display()))); tryRefund(p,td); cb.accept(false);} }),databaseManager.getExecutor()); }), cb); }
    private void tryRefund(Player p,BloraTags.TagDefinition t){if(t.applicationFee()>0&&integrations!=null&&integrations.hasEconomy()){if(integrations.depositMoney(p,t.applicationFee()))settings.sendMessage(p,"shop_purchase_refunded");else{plugin.getLogger().severe("CRIT REFUND FAIL "+p.getName());settings.sendMessage(p,"shop_purchase_refund_fail");}}}
    public void approveApplication(@NotNull BloraTags.TagApplication app, @NotNull Player r, @NotNull Consumer<Boolean> cb){ CompletableFuture.runAsync(()->{ if(!pendingApplications.remove(app)){runOnMainThread(()->cb.accept(false));return;} databaseManager.deleteApplicationAsync(app.id()).whenCompleteAsync((d,e)->{ if(e!=null)plugin.getLogger().log(Level.SEVERE,"Err del app#"+app.id(),e); else if(d==null||!d)plugin.getLogger().warning("Failed del app#"+app.id()); addOwnedTagAsync(app.playerUuid(),app.tagId(),gs->{runOnMainThread(()->{ if(gs){ settings.sendMessage(r,"review_approve_success_admin",Placeholder.unparsed("player_name",app.playerName()),Placeholder.unparsed("tag_id",app.tagId())); OfflinePlayer t=Bukkit.getOfflinePlayer(app.playerUuid()); if(t.isOnline()&&t.getPlayer()!=null)settings.sendMessage(t.getPlayer(),"review_approve_success_player",Placeholder.unparsed("tag_id",app.tagId()),Placeholder.unparsed("reviewer_name",r.getName())); cb.accept(true); }else{ settings.sendMessage(r,"review_approve_fail_admin",Placeholder.unparsed("player_name",app.playerName()),Placeholder.unparsed("tag_id",app.tagId())); cb.accept(false); } }); }); },databaseManager.getExecutor()); },databaseManager.getExecutor()); }
    public void rejectApplication(@NotNull BloraTags.TagApplication app, @NotNull Player r, @Nullable String reason, @NotNull Consumer<Boolean> cb){ CompletableFuture.runAsync(()->{ if(!pendingApplications.remove(app)){runOnMainThread(()->cb.accept(false));return;} databaseManager.deleteApplicationAsync(app.id()).whenCompleteAsync((d,e)->{ runOnMainThread(()->{ if(e!=null)plugin.getLogger().log(Level.SEVERE,"Err del app#"+app.id(),e); else if(d==null||!d)plugin.getLogger().warning("Failed del app#"+app.id()); settings.sendMessage(r,"review_reject_success_admin",Placeholder.unparsed("player_name",app.playerName()),Placeholder.unparsed("tag_id",app.tagId())); OfflinePlayer t=Bukkit.getOfflinePlayer(app.playerUuid()); if(t.isOnline()&&t.getPlayer()!=null)settings.sendMessage(t.getPlayer(),"review_reject_success_player",Placeholder.unparsed("tag_id",app.tagId()),Placeholder.unparsed("reviewer_name",r.getName())); BloraTags.TagDefinition td=getTagById(app.tagId()); if(td!=null&&td.applicationFee()>0&&td.applicationRefund()){ if(integrations!=null&&integrations.hasEconomy()){ if(t.hasPlayedBefore()||t.isOnline()){ if(integrations.depositMoney(t,td.applicationFee())){settings.sendMessage(r,"review_refund_success",Placeholder.unparsed("player_name",app.playerName()));if(t.isOnline()&&t.getPlayer()!=null)settings.sendMessage(t.getPlayer(),"review_refund_success_player");} else{plugin.getLogger().severe("CRIT REFUND FAIL "+app.playerName());settings.sendMessage(r,"review_refund_fail_admin",Placeholder.unparsed("player_name",app.playerName()));if(t.isOnline()&&t.getPlayer()!=null)settings.sendMessage(t.getPlayer(),"review_refund_fail_player");} }else{plugin.getLogger().warning("Could not refund offline "+app.playerName());settings.sendMessage(r,"review_refund_fail_offline",Placeholder.unparsed("player_name",app.playerName()));} }else{plugin.getLogger().warning("No economy for refund");settings.sendMessage(r,"review_refund_fail_no_economy");} } cb.accept(true); }); },databaseManager.getExecutor()); },databaseManager.getExecutor()); }
    private void notifyAdminsOfNewApplication(BloraTags.TagApplication app){ if(!settings.isNotifyAdminOnSubmit())return; String p=settings.getAdminNotificationPermission(); Bukkit.getOnlinePlayers().stream().filter(player->player.hasPermission(p)).forEach(player->settings.sendMessage(player,"admin_notify_new_application",Placeholder.unparsed("player_name",app.playerName()),Placeholder.unparsed("tag_id",app.tagId()))); plugin.getLogger().info("New app by "+app.playerName()+" for '"+app.tagId()+"' (ID: "+app.id()+")"); }
    private void runOnMainThread(Runnable task){ if(Bukkit.isPrimaryThread())task.run(); else Bukkit.getScheduler().runTask(plugin,task); }
}