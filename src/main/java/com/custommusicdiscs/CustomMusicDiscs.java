package com.custommusicdiscs;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.custommusicdiscs.command.DiscsCommand;
import com.custommusicdiscs.command.GetDiscCommand;
import com.custommusicdiscs.disc.DiscManager;
import com.custommusicdiscs.network.DiscNetworking;

public class CustomMusicDiscs implements ModInitializer {
	public static final String MOD_ID = "custom-music-discs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		DiscManager.init();
		DiscNetworking.init();
		GetDiscCommand.register();
		DiscsCommand.register();
		LOGGER.info("Custom Music Discs initialized");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Turns a lower-case registry id/file name into a nicer title, e.g. "my_track" -> "My Track". */
	public static String formatName(String id) {
		String[] parts = id.split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (!part.isEmpty()) {
				if (sb.length() > 0) {
					sb.append(' ');
				}
				sb.append(Character.toUpperCase(part.charAt(0)));
				if (part.length() > 1) {
					sb.append(part.substring(1));
				}
			}
		}
		return sb.length() == 0 ? id : sb.toString();
	}
}
