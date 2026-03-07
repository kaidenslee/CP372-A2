import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

public class Sender {

    private static final int MOD = 128;

    // -----------------------------
    // Config: parsed CLI settings
    // -----------------------------
    private static class Config {
        InetAddress rcvIp;
        int rcvDataPort;
        int senderAckPort;
        String inputFile;
        int timeoutMs;

        boolean gbnEnabled;
        int windowSize;
    }

    // -----------------------------
    // Sockets: UDP sockets used
    // -----------------------------
    private static class Sockets {
        DatagramSocket sendSocket;
        DatagramSocket ackSocket;
    }

    // -----------------------------
    // Main
    // -----------------------------
    public static void main(String[] args) {
        Config cfg = new Config();
        Sockets socks = new Sockets();

        boolean ok = true;

        ok = parseArgs(args, cfg);
        if (ok) {
            ok = setupSockets(cfg, socks);
        }

        long startNs = 0L;
        long endNs = 0L;

        if (ok) {
            startNs = System.nanoTime();
            ok = runSender(cfg, socks);
            endNs = System.nanoTime();
        }

        closeSockets(socks);

        if (ok) {
            double totalSeconds = (endNs - startNs) / 1_000_000_000.0;
            System.out.printf("Total Transmission Time: %.3f s%n", totalSeconds);
        }
    }

    // -----------------------------
    // Run protocol: handshake -> data -> EOT
    // -----------------------------
    private static boolean runSender(Config cfg, Sockets socks) {
        boolean ok = true;

        ok = doHandshake(cfg, socks);

        List<byte[]> chunks = new ArrayList<>();
        if (ok) {
            chunks = readFileChunks(cfg.inputFile);
            if (chunks == null) {
                ok = false;
            }
        }

        int lastDataSeq = 0;

        if (ok) {
            if (cfg.gbnEnabled) {
                lastDataSeq = goBackNSend(cfg, socks, chunks);
                if (lastDataSeq < 0) {
                    ok = false;
                }
            } else {
                lastDataSeq = stopAndWaitSend(cfg, socks, chunks);
                if (lastDataSeq < 0) {
                    ok = false;
                }
            }
        }

        if (ok) {
            int eotSeq;
            if (chunks.size() == 0) {
                eotSeq = 1; // empty file rule
            } else {
                eotSeq = (lastDataSeq + 1) % MOD;
            }

            ok = sendEOT(cfg, socks, eotSeq);
        }

        return ok;
    }

    // -----------------------------
    // STEP 1: Parse CLI args
    // -----------------------------
    private static boolean parseArgs(String[] args, Config cfg) {
        boolean ok = true;

        if (args == null || (args.length != 5 && args.length != 6)) {
            ok = false;
            printUsage();
        }

        if (ok) {
            try {
                cfg.rcvIp = InetAddress.getByName(args[0]);
                cfg.rcvDataPort = Integer.parseInt(args[1]);
                cfg.senderAckPort = Integer.parseInt(args[2]);
                cfg.inputFile = args[3];
                cfg.timeoutMs = Integer.parseInt(args[4]);

                cfg.gbnEnabled = (args.length == 6);
                cfg.windowSize = 0;

                if (cfg.gbnEnabled) {
                    cfg.windowSize = Integer.parseInt(args[5]);
                    if (cfg.windowSize <= 0 || cfg.windowSize > 128 || (cfg.windowSize % 4) != 0) {
                        ok = false;
                        System.out.println("Error: window_size must be a positive multiple of 4 and <= 128.");
                        printUsage();
                    }
                }

                if (ok) {
                    if (cfg.rcvDataPort <= 0 || cfg.rcvDataPort > 65535 ||
                        cfg.senderAckPort <= 0 || cfg.senderAckPort > 65535) {
                        ok = false;
                        System.out.println("Error: ports must be in range 1..65535.");
                    }
                }

                if (ok && cfg.timeoutMs <= 0) {
                    ok = false;
                    System.out.println("Error: timeout_ms must be > 0.");
                }

                if (ok) {
                    File f = new File(cfg.inputFile);
                    if (!f.exists() || !f.isFile()) {
                        ok = false;
                        System.out.println("Error: input_file does not exist or is not a file.");
                    }
                }

            } catch (Exception e) {
                ok = false;
                System.out.println("Error parsing arguments: " + e.getMessage());
                printUsage();
            }
        }

        return ok;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> <input_file> <timeout_ms> [window_size]");
        System.out.println("  (omit window_size => Stop-and-Wait, include window_size => Go-Back-N)");
    }

    // -----------------------------
    // STEP 2: Setup sockets
    // -----------------------------
    private static boolean setupSockets(Config cfg, Sockets socks) {
        boolean ok = true;

        try {
            socks.sendSocket = new DatagramSocket();
            socks.ackSocket = new DatagramSocket(cfg.senderAckPort);
            socks.ackSocket.setSoTimeout(cfg.timeoutMs);
        } catch (Exception e) {
            ok = false;
            System.out.println("Socket setup failed: " + e.getMessage());
        }

        return ok;
    }

    private static void closeSockets(Sockets socks) {
        if (socks != null) {
            if (socks.sendSocket != null) {
                socks.sendSocket.close();
                socks.sendSocket = null;
            }
            if (socks.ackSocket != null) {
                socks.ackSocket.close();
                socks.ackSocket = null;
            }
        }
    }

    // -----------------------------
    // Handshake: send SOT until ACK0, 3 timeouts => fail
    // -----------------------------
    private static boolean doHandshake(Config cfg, Sockets socks) {
        boolean ok = true;

        DSPacket sot = new DSPacket(DSPacket.TYPE_SOT, 0, null);

        int consecutiveTimeouts = 0;
        boolean done = false;

        while (ok && !done) {
            ok = sendPacket(cfg, socks, sot);

            boolean gotCorrectAck = false;
            boolean timedOut = false;

            while (ok && !gotCorrectAck && !timedOut) {
                ReceiveResult rr = receiveAckTimeoutAware(socks);
                if (rr.timedOut) {
                    timedOut = true;
                } else if (rr.packet != null) {
                    if (rr.packet.getType() == DSPacket.TYPE_ACK && rr.packet.getSeqNum() == 0) {
                        gotCorrectAck = true;
                    }
                }
            }

            if (ok) {
                if (gotCorrectAck) {
                    done = true;
                } else {
                    if (timedOut) {
                        consecutiveTimeouts += 1;
                        if (consecutiveTimeouts >= 3) {
                            System.out.println("Unable to transfer file.");
                            ok = false;
                        }
                    }
                }
            }
        }

        return ok;
    }

    // -----------------------------
    // Read input file, split into <=124 byte chunks
    // -----------------------------
    private static List<byte[]> readFileChunks(String path) {
        List<byte[]> chunks = new ArrayList<>();
        // boolean ok = true;

        FileInputStream fis = null;

        try {
            File f = new File(path);
            long len = f.length();

            if (len == 0) {
                // empty list is valid
            } else {
                fis = new FileInputStream(f);

                byte[] buf = new byte[DSPacket.MAX_PAYLOAD_SIZE];
                int read = 0;

                while (true) {
                    read = fis.read(buf);

                    if (read == -1) {
                        break;
                    }

                    if (read > 0) {
                        byte[] piece = new byte[read];
                        System.arraycopy(buf, 0, piece, 0, read);
                        chunks.add(piece);
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("File read failed: " + e.getMessage());
            chunks = null;
        } finally {
            try {
                if (fis != null) {
                    fis.close();
                }
            } catch (Exception e2) {
                // ignore close errors
            }
        }

        return chunks;
    }

    // -----------------------------
    // Stop-and-Wait DATA transfer
    // Returns lastDataSeq, or -1 on failure.
    // -----------------------------
    private static int stopAndWaitSend(Config cfg, Sockets socks, List<byte[]> chunks) {
        boolean ok = true;

        int seq = 1;
        int lastDataSeq = 0;

        int i = 0;
        while (ok && i < chunks.size()) {
            byte[] payload = chunks.get(i);
            DSPacket dataPkt = new DSPacket(DSPacket.TYPE_DATA, seq, payload);

            int consecutiveTimeouts = 0;
            boolean acked = false;

            while (ok && !acked) {
                ok = sendPacket(cfg, socks, dataPkt);

                boolean gotCorrectAck = false;
                boolean timedOut = false;

                while (ok && !gotCorrectAck && !timedOut) {
                    ReceiveResult rr = receiveAckTimeoutAware(socks);

                    if (rr.timedOut) {
                        timedOut = true;
                    } else if (rr.packet != null) {
                        if (rr.packet.getType() == DSPacket.TYPE_ACK && rr.packet.getSeqNum() == seq) {
                            gotCorrectAck = true;
                        }
                    }
                }

                if (ok) {
                    if (gotCorrectAck) {
                        acked = true;
                        lastDataSeq = seq;
                        seq = (seq + 1) % MOD;
                    } else {
                        if (timedOut) {
                            consecutiveTimeouts += 1;
                            if (consecutiveTimeouts >= 3) {
                                System.out.println("Unable to transfer file.");
                                ok = false;
                            }
                        }
                    }
                }
            }

            if (ok) {
                i += 1;
            }
        }

        if (!ok) {
            lastDataSeq = -1;
        }

        return lastDataSeq;
    }

    // -----------------------------
    // Go-Back-N DATA transfer
    // Returns lastDataSeq, or -1 on failure.
    // -----------------------------
    private static int goBackNSend(Config cfg, Sockets socks, List<byte[]> chunks) {
        boolean ok = true;

        int totalPackets = chunks.size();
        int lastDataSeq = 0;

        DSPacket[] packetsByIndex = new DSPacket[totalPackets];
        int idx = 0;
        while (idx < totalPackets) {
            int seq = seqForIndex(idx);
            packetsByIndex[idx] = new DSPacket(DSPacket.TYPE_DATA, seq, chunks.get(idx));
            lastDataSeq = seq;
            idx += 1;
        }

        int baseIndex = 0;     // oldest unacked packet index
        int nextIndex = 0;     // next unsent packet index

        int baseIndexAtLastTimeout = 0;
        int consecutiveTimeouts = 0;

        while (ok && baseIndex < totalPackets) {

            // Send as much as window allows (including chaos permutation groups of 4)
            int windowUsed = nextIndex - baseIndex;
            while (ok && nextIndex < totalPackets && windowUsed < cfg.windowSize) {
                int start = nextIndex;
                int maxToSend = cfg.windowSize - windowUsed;
                int groupSize = 0;

                List<DSPacket> group = new ArrayList<>(4);

                while (groupSize < 4 && groupSize < maxToSend && (start + groupSize) < totalPackets) {
                    group.add(packetsByIndex[start + groupSize]);
                    groupSize += 1;
                }

                if (group.size() == 4) {
                    List<DSPacket> permuted = ChaosEngine.permutePackets(group);
                    int p = 0;
                    while (ok && p < permuted.size()) {
                        ok = sendPacket(cfg, socks, permuted.get(p));
                        p += 1;
                    }
                } else {
                    int p = 0;
                    while (ok && p < group.size()) {
                        ok = sendPacket(cfg, socks, group.get(p));
                        p += 1;
                    }
                }

                if (ok) {
                    nextIndex += groupSize;
                    windowUsed = nextIndex - baseIndex;
                }
            }

            // Wait for ACK (cumulative)
            boolean advanced = false;
            boolean timedOut = false;

            boolean waiting = true;
            while (ok && waiting) {
                ReceiveResult rr = receiveAckTimeoutAware(socks);

                if (rr.timedOut) {
                    timedOut = true;
                    waiting = false;
                } else if (rr.packet != null) {
                    if (rr.packet.getType() == DSPacket.TYPE_ACK) {
                        int ackSeq = rr.packet.getSeqNum();

                        int candidateIndex = baseIndex;
                        int matchedIndex = -1;

                        while (candidateIndex < nextIndex && matchedIndex == -1) {
                            if (seqForIndex(candidateIndex) == ackSeq) {
                                matchedIndex = candidateIndex;
                            }
                            candidateIndex += 1;
                        }

                        if (matchedIndex != -1) {
                            int newBase = matchedIndex + 1;

                            if (newBase > baseIndex) {
                                baseIndex = newBase;
                                advanced = true;
                                waiting = false;
                            }
                        }
                    }
                    // if not ACK, ignore and keep waiting until timeout
                }
            }

            if (ok) {
                if (advanced) {
                    consecutiveTimeouts = 0;
                    baseIndexAtLastTimeout = baseIndex;
                } else {
                    if (timedOut) {
                        if (baseIndex == baseIndexAtLastTimeout) {
                            consecutiveTimeouts += 1;
                        } else {
                            baseIndexAtLastTimeout = baseIndex;
                            consecutiveTimeouts = 1;
                        }

                        if (consecutiveTimeouts >= 3) {
                            System.out.println("Unable to transfer file.");
                            ok = false;
                        } else {
                            // retransmit window from baseIndex up to nextIndex
                            ok = retransmitWindow(cfg, socks, packetsByIndex, baseIndex, nextIndex);
                        }
                    }
                }
            }
        }

        if (!ok) {
            lastDataSeq = -1;
        }

        return lastDataSeq;
    }

    // Retransmit [baseIndex, nextIndex) using same chaos group-of-4 sending rule
    private static boolean retransmitWindow(
            Config cfg,
            Sockets socks,
            DSPacket[] packetsByIndex,
            int baseIndex,
            int nextIndex) {

        boolean ok = true;

        int i = baseIndex;
        while (ok && i < nextIndex) {
            int remaining = nextIndex - i;

            List<DSPacket> group = new ArrayList<>(4);
            int k = 0;
            while (k < 4 && k < remaining) {
                group.add(packetsByIndex[i + k]);
                k += 1;
            }

            if (group.size() == 4) {
                List<DSPacket> permuted = ChaosEngine.permutePackets(group);
                int p = 0;
                while (ok && p < permuted.size()) {
                    ok = sendPacket(cfg, socks, permuted.get(p));
                    p += 1;
                }
            } else {
                int p = 0;
                while (ok && p < group.size()) {
                    ok = sendPacket(cfg, socks, group.get(p));
                    p += 1;
                }
            }

            if (ok) {
                i += group.size();
            }
        }

        return ok;
    }

    // -----------------------------
    // EOT send + wait for ACK(eotSeq)
    // -----------------------------
    private static boolean sendEOT(Config cfg, Sockets socks, int eotSeq) {
        boolean ok = true;

        DSPacket eot = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);

        int consecutiveTimeouts = 0;
        boolean done = false;

        while (ok && !done) {
            ok = sendPacket(cfg, socks, eot);

            boolean gotCorrectAck = false;
            boolean timedOut = false;

            while (ok && !gotCorrectAck && !timedOut) {
                ReceiveResult rr = receiveAckTimeoutAware(socks);

                if (rr.timedOut) {
                    timedOut = true;
                } else if (rr.packet != null) {
                    if (rr.packet.getType() == DSPacket.TYPE_ACK && rr.packet.getSeqNum() == eotSeq) {
                        gotCorrectAck = true;
                    }
                }
            }

            if (ok) {
                if (gotCorrectAck) {
                    done = true;
                } else {
                    if (timedOut) {
                        consecutiveTimeouts += 1;
                        if (consecutiveTimeouts >= 3) {
                            System.out.println("Unable to transfer file.");
                            ok = false;
                        }
                    }
                }
            }
        }

        return ok;
    }

    // -----------------------------
    // Send DS packet to receiver
    // -----------------------------
    private static boolean sendPacket(Config cfg, Sockets socks, DSPacket p) {
        boolean ok = true;

        try {
            byte[] data = p.toBytes(); // always 128 bytes
            DatagramPacket dp = new DatagramPacket(data, data.length, cfg.rcvIp, cfg.rcvDataPort);
            socks.sendSocket.send(dp);

            System.out.printf("SEND type=%d seq=%d len=%d%n", p.getType(), p.getSeqNum(), p.getLength());

        } catch (Exception e) {
            ok = false;
            System.out.println("Send failed: " + e.getMessage());
        }

        return ok;
    }

    // -----------------------------
    // Receive ACK packet with timeout awareness
    // -----------------------------
    private static class ReceiveResult {
        DSPacket packet;
        boolean timedOut;
    }

    private static ReceiveResult receiveAckTimeoutAware(Sockets socks) {
        ReceiveResult rr = new ReceiveResult();
        rr.packet = null;
        rr.timedOut = false;

        try {
            byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);

            socks.ackSocket.receive(dp); // may timeout

            rr.packet = new DSPacket(dp.getData());

            System.out.printf("RECV type=%d seq=%d len=%d%n",
                    rr.packet.getType(), rr.packet.getSeqNum(), rr.packet.getLength());

        } catch (SocketTimeoutException te) {
            rr.timedOut = true;
        } catch (Exception e) {
            rr.packet = null;
            System.out.println("Receive failed or invalid packet: " + e.getMessage());
        }

        return rr;
    }

    // -----------------------------
    // Helpers for seq/index math
    // -----------------------------
    private static int seqForIndex(int index) {
        int seq = (1 + index) % MOD;
        return seq;
    }

}
