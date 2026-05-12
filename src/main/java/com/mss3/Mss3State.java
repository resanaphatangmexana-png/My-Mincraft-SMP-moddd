package com.mss3;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * World-persistent state. Auto-saves to "data/mss3smp.dat" in the world folder.
 * Holds all player data + global shop listings.
 */
public class Mss3State extends PersistentState {
    private static final String DATA_KEY = "mss3smp";

    public Map<UUID, PlayerData> players = new HashMap<>();

    /** Get the state attached to the overworld of this server. */
    public static Mss3State get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) throw new IllegalStateException("Overworld not available");
        PersistentStateManager mgr = overworld.getPersistentStateManager();
        PersistentStateType<Mss3State> type = new PersistentStateType<>(
            DATA_KEY, Mss3State::new, Mss3State::fromNbt, null
        );
        return mgr.getOrCreate(type);
    }

    public PlayerData getOrCreatePlayer(UUID uuid) {
        return players.computeIfAbsent(uuid, PlayerData::new);
    }

    /** Return ALL active listings from all players, flattened. */
    public List<PlayerData.ShopListing> getAllListings() {
        List<PlayerData.ShopListing> all = new ArrayList<>();
        for (PlayerData d : players.values()) {
            all.addAll(d.listings);
        }
        return all;
    }

    /** Remove a listing (called when item is bought). */
    public boolean removeListing(UUID sellerId, int listingIndex) {
        PlayerData d = players.get(sellerId);
        if (d == null || listingIndex < 0 || listingIndex >= d.listings.size()) return false;
        d.listings.remove(listingIndex);
        markDirty();
        return true;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        NbtList list = new NbtList();
        for (PlayerData d : players.values()) {
            list.add(d.writeNbt(new NbtCompound(), registries));
        }
        nbt.put("players", list);
        return nbt;
    }

    public static Mss3State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        Mss3State s = new Mss3State();
        if (nbt.contains("players", NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList("players", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                PlayerData d = PlayerData.fromNbt(list.getCompound(i), registries);
                if (d.uuid != null) s.players.put(d.uuid, d);
            }
        }
        return s;
    }
}
