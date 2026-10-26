package local.paperclock;

import java.io.IOException;

/** Minimal NTPv4 wire format, independent of the device's wall clock. */
public final class NtpCodec {
    private static final long NTP_EPOCH_SECONDS = 2208988800L;
    private static final long NTP_ERA_MILLIS = 4294967296000L;
    private static final long MIN_UTC = 1577836800000L; // 2020-01-01
    private static final long MAX_UTC = 4102444800000L; // 2100-01-01

    private NtpCodec() { }

    public static byte[] request(long wallMillis, int nonce) {
        byte[] packet = new byte[48];
        packet[0] = 0x23; // version 4, client mode
        writeTimestamp(packet, 40, wallMillis);
        // Preserve the approximate timestamp and randomize its last 16 fraction bits.
        packet[46] = (byte) (nonce >>> 8);
        packet[47] = (byte) nonce;
        return packet;
    }

    static void writeTimestamp(byte[] packet, int offset, long utcMillis) {
        long seconds = utcMillis / 1000L + NTP_EPOCH_SECONDS;
        long fraction = (utcMillis % 1000L) * 0x100000000L / 1000L;
        write32(packet, offset, seconds);
        write32(packet, offset + 4, fraction);
    }

    private static void write32(byte[] packet, int offset, long value) {
        for (int i = 3; i >= 0; i--) {
            packet[offset + i] = (byte) value;
            value >>>= 8;
        }
    }

    private static long read32(byte[] packet, int offset) {
        long value = 0;
        for (int i = 0; i < 4; i++) value = (value << 8) | (packet[offset + i] & 0xffL);
        return value;
    }

    private static long readTimestamp(byte[] packet, int offset) throws IOException {
        long seconds = read32(packet, offset);
        long fraction = read32(packet, offset + 4);
        if (seconds == 0 && fraction == 0) throw new IOException("Missing server timestamp");
        long millis = (seconds - NTP_EPOCH_SECONDS) * 1000L
                + (fraction * 1000L + 0x80000000L) / 0x100000000L;
        if (millis < MIN_UTC) millis += NTP_ERA_MILLIS; // NTP era rollover in 2036
        if (millis < MIN_UTC || millis >= MAX_UTC) throw new IOException("Server date out of range");
        return millis;
    }

    public static long utcAtReceive(byte[] response, int length, byte[] request, long roundTripMillis)
            throws IOException {
        if (length < 48 || response.length < length || request.length < 48)
            throw new IOException("Truncated NTP packet");
        int leap = (response[0] >>> 6) & 3;
        int version = (response[0] >>> 3) & 7;
        int mode = response[0] & 7;
        int stratum = response[1] & 0xff;
        if (leap == 3 || (version != 3 && version != 4) || mode != 4 || stratum < 1 || stratum > 15)
            throw new IOException("Unusable NTP server reply");
        for (int i = 0; i < 8; i++) {
            if (response[24 + i] != request[40 + i]) throw new IOException("NTP request mismatch");
        }
        if (roundTripMillis < 0 || roundTripMillis > 5000) throw new IOException("Excessive network delay");
        long received = readTimestamp(response, 32);
        long transmitted = readTimestamp(response, 40);
        long processing = transmitted - received;
        if (processing < 0 || processing > roundTripMillis + 2)
            throw new IOException("Invalid server processing interval");
        return transmitted + Math.max(0, roundTripMillis - processing) / 2;
    }
}
