package com.custommusicdiscs.disc;

/**
 * Reads the real playing time of an Ogg Vorbis file so the jukebox stops at the right
 * moment instead of guessing from the file size.
 * <p>
 * No audio decoding is needed. The sample rate sits in the Vorbis identification
 * header and the total sample count is the largest granule position on that stream's
 * pages, so walking the page headers is enough. Files that carry another stream
 * first (an embedded cover art, for example) are handled too: the Vorbis header is
 * looked up by scanning pages, and only pages of that bitstream are counted.
 */
public final class OggDuration {
	/** Bytes before the segment table in a page header: "OggS" + version + type + granule + serial + seq + crc. */
	private static final int PAGE_HEADER = 27;

	/** Identification header: 1 byte packet type, "vorbis", 4 byte version, 1 byte channels. */
	private static final int SAMPLE_RATE_OFFSET = 12;

	/** The Vorbis identification header's packet type. */
	private static final byte VORBIS_ID_PACKET = 0x01;

	private OggDuration() {
	}

	/**
	 * Returns the duration in seconds, or {@code -1} when the bytes are not a readable
	 * Ogg Vorbis stream. Callers should fall back to an estimate in that case.
	 */
	public static float seconds(byte[] ogg) {
		if (ogg == null || ogg.length < PAGE_HEADER) {
			return -1f;
		}
		int sampleRate = -1;
		int audioStream = -1;
		int pos = 0;
		while (pos + PAGE_HEADER <= ogg.length && isPageStart(ogg, pos)) {
			int segments = ogg[pos + 26] & 0xFF;
			if (pos + PAGE_HEADER + segments > ogg.length) {
				break;
			}
			if (sampleRate < 0 && isVorbisIdHeader(ogg, pos, segments)) {
				sampleRate = readIntLE(ogg, pos + PAGE_HEADER + segments + SAMPLE_RATE_OFFSET);
				audioStream = readIntLE(ogg, pos + 14);
			}
			pos = nextPage(ogg, pos, segments);
		}
		if (sampleRate <= 0) {
			return -1f;
		}
		long samples = lastGranulePosition(ogg, audioStream);
		if (samples <= 0) {
			return -1f;
		}
		return (float) (samples / (double) sampleRate);
	}

	/** True when this page's first packet is the Vorbis identification header. */
	private static boolean isVorbisIdHeader(byte[] ogg, int pos, int segments) {
		int start = pos + PAGE_HEADER + segments;
		if (start + SAMPLE_RATE_OFFSET + 4 > ogg.length || ogg[start] != VORBIS_ID_PACKET) {
			return false;
		}
		int off = start + 1;
		return ogg[off] == 'v' && ogg[off + 1] == 'o' && ogg[off + 2] == 'r' && ogg[off + 3] == 'b'
				&& ogg[off + 4] == 'i' && ogg[off + 5] == 's';
	}

	/**
	 * Walks the pages and returns the largest granule position seen on the given
	 * bitstream, which is its total number of PCM samples. Stops at the first
	 * malformed or truncated page rather than throwing.
	 */
	private static long lastGranulePosition(byte[] ogg, int audioStream) {
		int pos = 0;
		long granule = 0;
		while (pos + PAGE_HEADER <= ogg.length && isPageStart(ogg, pos)) {
			int segments = ogg[pos + 26] & 0xFF;
			if (pos + PAGE_HEADER + segments > ogg.length) {
				break;
			}
			// Only the audio bitstream counts; other streams measure in their own units.
			// A granule of -1 means no packet ends on that page, so only positive values count.
			if (audioStream < 0 || readIntLE(ogg, pos + 14) == audioStream) {
				long value = readLongLE(ogg, pos + 6);
				if (value > granule) {
					granule = value;
				}
			}
			pos = nextPage(ogg, pos, segments);
		}
		return granule;
	}

	/** The offset of the page after this one, or -1 if the body runs past the end. */
	private static int nextPage(byte[] ogg, int pos, int segments) {
		int body = 0;
		for (int i = 0; i < segments; i++) {
			body += ogg[pos + PAGE_HEADER + i] & 0xFF;
		}
		int next = pos + PAGE_HEADER + segments + body;
		return next > ogg.length ? -1 : next;
	}

	private static boolean isPageStart(byte[] ogg, int pos) {
		return ogg[pos] == 'O' && ogg[pos + 1] == 'g' && ogg[pos + 2] == 'g' && ogg[pos + 3] == 'S';
	}

	private static int readIntLE(byte[] b, int off) {
		if (off < 0 || off + 4 > b.length) {
			return -1;
		}
		return (b[off] & 0xFF)
				| ((b[off + 1] & 0xFF) << 8)
				| ((b[off + 2] & 0xFF) << 16)
				| ((b[off + 3] & 0xFF) << 24);
	}

	private static long readLongLE(byte[] b, int off) {
		if (off < 0 || off + 8 > b.length) {
			return -1L;
		}
		long value = 0;
		for (int i = 7; i >= 0; i--) {
			value = (value << 8) | (b[off + i] & 0xFFL);
		}
		return value;
	}
}
