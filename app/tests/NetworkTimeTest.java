package local.paperclock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class NetworkTimeTest {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static byte[] reply(byte[] request, long receive, long transmit) {
        byte[] response = new byte[48];
        response[0] = 0x24;
        response[1] = 1;
        System.arraycopy(request, 40, response, 24, 8);
        NtpCodec.writeTimestamp(response, 32, receive);
        NtpCodec.writeTimestamp(response, 40, transmit);
        return response;
    }

    private static void reject(byte[] response, int length, byte[] request, long rtt, String label) {
        try {
            NtpCodec.utcAtReceive(response, length, request, rtt);
            throw new AssertionError(label);
        } catch (IOException expected) { }
    }

    public static void main(String[] args) throws Exception {
        long utc = 1793005945000L;
        // A wrong 2012 device date must have no effect on the returned UTC time.
        byte[] request = NtpCodec.request(1325404800000L, 12345);
        byte[] response = reply(request, utc, utc + 20);
        check(NtpCodec.utcAtReceive(response, 48, request, 120) == utc + 70, "clock-independent RTT correction");
        reject(response, 47, request, 120, "short packet");
        byte[] bad = response.clone(); bad[0] = (byte) 0xe4;
        reject(bad, 48, request, 120, "unsynchronized server");
        bad = response.clone(); bad[0] = 0x23;
        reject(bad, 48, request, 120, "wrong mode");
        bad = response.clone(); bad[1] = 0;
        reject(bad, 48, request, 120, "kiss-of-death response");
        bad = response.clone(); bad[24] ^= 1;
        reject(bad, 48, request, 120, "origin does not match request");
        bad = response.clone(); for (int i = 40; i < 48; i++) bad[i] = 0;
        reject(bad, 48, request, 120, "missing transmit time");
        reject(response, 48, request, 6000, "excessive RTT");
        reject(reply(request, utc, utc + 500), 48, request, 100, "impossible processing time");
        reject(reply(request, 1325404800000L, 1325404800020L), 48, request, 100, "obsolete server date");
        long afterRollover = 2208988800000L; // 2040-01-01
        check(NtpCodec.utcAtReceive(reply(request, afterRollover, afterRollover), 48, request, 0)
                == afterRollover, "2036 NTP era rollover");

        check(TimeSyncPolicy.isDue(1000, -1, 0, false), "first sync");
        check(!TimeSyncPolicy.isDue(2000, 1000, 0, false), "six-hour throttle");
        check(TimeSyncPolicy.isDue(TimeSyncPolicy.INTERVAL + 1000, 1000, 0, false), "periodic sync");
        check(!TimeSyncPolicy.isDue(1000, -1, 5000, false), "failure backoff");
        check(TimeSyncPolicy.isDue(1000, 900, 5000, true), "reconnection bypasses backoff");
        check(TimeSyncPolicy.retryDelay(1) == 60000 && TimeSyncPolicy.retryDelay(10) == 900000,
                "bounded exponential retry");

        final AtomicReference<Throwable> serverError = new AtomicReference<Throwable>();
        final DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        final long[] serverUtc = new long[1];
        Thread responder = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server.setSoTimeout(2000);
                    byte[] buffer = new byte[48];
                    DatagramPacket incoming = new DatagramPacket(buffer, buffer.length);
                    server.receive(incoming);
                    serverUtc[0] = System.currentTimeMillis();
                    byte[] data = reply(buffer, serverUtc[0], serverUtc[0]);
                    server.send(new DatagramPacket(data, data.length, incoming.getAddress(), incoming.getPort()));
                } catch (Throwable e) { serverError.set(e); }
                finally { server.close(); }
            }
        });
        responder.start();
        NetworkTimeClient.ElapsedClock clock = new NetworkTimeClient.ElapsedClock() {
            @Override public long millis() { return System.nanoTime() / 1000000L; }
        };
        NetworkTimeClient.Sample sample = NetworkTimeClient.query("127.0.0.1", server.getLocalPort(), clock, 2000);
        responder.join();
        check(serverError.get() == null && Math.abs(sample.utcMillis - serverUtc[0]) < 100,
                "real UDP request, origin validation, and response parsing");
        DatagramSocket silent = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        try {
            try {
                NetworkTimeClient.query("127.0.0.1", silent.getLocalPort(), clock, 80);
                throw new AssertionError("network timeout");
            } catch (SocketTimeoutException expected) { }
        } finally { silent.close(); }
        System.out.println("PASS: NTP validation, wrong device date, 2036 rollover, retry policy, real UDP exchange, timeout");
    }
}
