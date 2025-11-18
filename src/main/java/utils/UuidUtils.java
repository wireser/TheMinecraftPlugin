package utils;

import java.util.UUID;

public class UuidUtils {

	public static byte[] uuidToBytes(UUID uuid) {
	    byte[] bytes = new byte[16];

	    long msb = uuid.getMostSignificantBits();
	    long lsb = uuid.getLeastSignificantBits();

	    for (int i = 0; i < 8; i++) {
	        bytes[i]     = (byte) (msb >>> (8 * (7 - i)));
	        bytes[8 + i] = (byte) (lsb >>> (8 * (7 - i)));
	    }

	    return bytes;
	}

	public static UUID bytesToUuid(byte[] bytes) {
	    if (bytes == null || bytes.length != 16) {
	        throw new IllegalArgumentException("UUID byte array must be length 16");
	    }

	    long msb = 0;
	    long lsb = 0;

	    for (int i = 0; i < 8; i++) {
	        msb = (msb << 8) | (bytes[i] & 0xFF);
	    }
	    for (int i = 8; i < 16; i++) {
	        lsb = (lsb << 8) | (bytes[i] & 0xFF);
	    }

	    return new UUID(msb, lsb);
	}
	
}
