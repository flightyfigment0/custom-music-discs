package com.custommusicdiscs.client.mixin;

import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Provides access to the {@link SoundManager} sound registry so that custom,
 * server-transferred discs can register a {@link WeighedSoundEvents} entry and be
 * looked up by the jukebox.
 */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
	@Accessor("registry")
	Map<Identifier, WeighedSoundEvents> customMusicDiscs$getRegistry();
}
