package com.custommusicdiscs.client.mixin;

import com.custommusicdiscs.client.DiscAudioCache;

import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;

/**
 * Intercepts the sound buffer library's resource reads so that audio bytes that were
 * transferred from the server (and are not present in any resource pack) can still be
 * opened and streamed by the client's audio engine.
 */
@Mixin(SoundBufferLibrary.class)
public abstract class SoundBufferLibraryMixin {

	@Redirect(
			method = "lambda$getStream$0",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/packs/resources/ResourceProvider;open(Lnet/minecraft/resources/Identifier;)Ljava/io/InputStream;"
			)
	)
	private InputStream customMusicDiscs$serveStream(ResourceProvider provider, Identifier id) throws IOException {
		InputStream custom = DiscAudioCache.open(id);
		return custom != null ? custom : provider.open(id);
	}

	@Redirect(
			method = "lambda$getCompleteBuffer$1",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/packs/resources/ResourceProvider;open(Lnet/minecraft/resources/Identifier;)Ljava/io/InputStream;"
			)
	)
	private InputStream customMusicDiscs$serveBuffer(ResourceProvider provider, Identifier id) throws IOException {
		InputStream custom = DiscAudioCache.open(id);
		return custom != null ? custom : provider.open(id);
	}
}
