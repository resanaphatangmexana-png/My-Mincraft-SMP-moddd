package com.mss3;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;

import java.util.UUID;

/**
 * Per-player persistent data: money, admin rank, invisibility state, region.
 */
public class PlayerData {
    public UUID uuid;
    public long money = 0L;
    public String region = Mss3Mod.DEFAULT_REGION;
    public boolean isAdmin = false;
    public boolean isInvisible = false;
    public long lastSeen = System.currentTimeMillis();

    public PlayerData() {}
    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        nbt.putUuid("uuid", uuid);
        nbt.putLong("money", money);
        nbt.putString("region", region == null ? Mss3Mod.DEFAULT_REGION : region);
        nbt.putBoolean("isAdmin", isAdmin);
        nbt.putBoolean("isInvisible", isInvisible);
        nbt.putLong("lastSeen", lastSeen);
        return nbt;
    }

    public static PlayerData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        PlayerData d = new PlayerData();
        d.uuid = nbt.getUuid("uuid");
        d.money = nbt.getLong("money");
        d.region = nbt.contains("region") ? nbt.getString("region") : Mss3Mod.DEFAULT_REGION;
        d.isAdmin = nbt.contains("isAdmin") && nbt.getBoolean("isAdmin");
        d.isInvisible = nbt.contains("isInvisible") && nbt.getBoolean("isInvisible");
        d.lastSeen = nbt.contains("lastSeen") ? nbt.getLong("lastSeen") : System.currentTimeMillis();
        return d;
    }
}
