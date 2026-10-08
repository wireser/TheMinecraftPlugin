package com.wireser.minecraft.utils;

import java.util.Objects;
import java.util.UUID;

/**
 * Utility methods for converting UUID values to and from their compact
 * 16-byte binary representation.
 *
 * <p>The byte layout is big-endian:</p>
 *
 * <ul>
 *     <li>bytes {@code 0-7} contain the UUID's most-significant bits,</li>
 *     <li>bytes {@code 8-15} contain the UUID's least-significant bits.</li>
 * </ul>
 *
 * <p>This representation is suitable for storing UUID values in fixed-width
 * binary database columns such as {@code BINARY(16)}.</p>
 */
public final class UuidUtils {

    private static final int UUID_BYTES = 16;

    private UuidUtils() {
    }

    /**
     * Converts a UUID into its 16-byte binary representation.
     *
     * @param uuid UUID to convert
     * @return 16-byte big-endian representation of the UUID
     * @throws NullPointerException if {@code uuid} is {@code null}
     */
    public static byte[] toBytes(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");

        byte[] bytes = new byte[UUID_BYTES];

        long mostSignificantBits = uuid.getMostSignificantBits();
        long leastSignificantBits = uuid.getLeastSignificantBits();

        for (int i = 0; i < Long.BYTES; i++) {
            bytes[i] = (byte) (
                    mostSignificantBits >>> (Byte.SIZE * (Long.BYTES - 1 - i))
            );

            bytes[Long.BYTES + i] = (byte) (
                    leastSignificantBits >>> (Byte.SIZE * (Long.BYTES - 1 - i))
            );
        }

        return bytes;
    }

    /**
     * Reconstructs a UUID from its 16-byte binary representation.
     *
     * @param bytes 16-byte big-endian UUID representation
     * @return reconstructed UUID
     * @throws NullPointerException if {@code bytes} is {@code null}
     * @throws IllegalArgumentException if {@code bytes} is not exactly 16 bytes long
     */
    public static UUID fromBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");

        if (bytes.length != UUID_BYTES) {
            throw new IllegalArgumentException(
                    "UUID byte array must contain exactly 16 bytes, got "
                            + bytes.length
            );
        }

        long mostSignificantBits = 0;
        long leastSignificantBits = 0;

        for (int i = 0; i < Long.BYTES; i++) {
            mostSignificantBits =
                    (mostSignificantBits << Byte.SIZE)
                            | (bytes[i] & 0xFFL);

            leastSignificantBits =
                    (leastSignificantBits << Byte.SIZE)
                            | (bytes[Long.BYTES + i] & 0xFFL);
        }

        return new UUID(
                mostSignificantBits,
                leastSignificantBits
        );
    }
    
}
