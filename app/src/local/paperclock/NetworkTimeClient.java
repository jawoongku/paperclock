package local.paperclock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.SecureRandom;

/** One bounded SNTP exchange; callers run this off the UI thread. */
public final class NetworkTimeClient {
    public interface ElapsedClock { long millis(); }

    public static final class Sample {
        public final long utcMillis;
        public final long elapsedMillis;
        public final long roundTripMillis;
        public final String server;

        Sample(long utc, long elapsed, long roundTrip, String name) {
            utcMillis = utc; elapsedMillis = elapsed; roundTripMillis = roundTrip; server = name;
        }
    }

    private NetworkTimeClient() { }

    public static Sample query(String host, ElapsedClock clock) throws IOException {
        return query(host, 123, clock, 3000);
    }

    static Sample query(String host, int port, ElapsedClock clock, int timeout) throws IOException {
        InetAddress address = InetAddress.getByName(host);
        byte[] request = NtpCodec.request(System.currentTimeMillis(), new SecureRandom().nextInt());
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.connect(address, port); // Accept replies only from this peer.
            socket.setSoTimeout(timeout);
            long sentAt = clock.millis();
            socket.send(new DatagramPacket(request, request.length));
            byte[] response = new byte[512];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            long receivedAt = clock.millis();
            long utc = NtpCodec.utcAtReceive(response, packet.getLength(), request, receivedAt - sentAt);
            return new Sample(utc, receivedAt, receivedAt - sentAt, host);
        } finally {
            socket.close();
        }
    }
}
