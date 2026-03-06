import java.nio.ByteBuffer;

/**
 * DSPacket
 * --------
 * Represents a DS-FTP protocol packet used in CP372 Assignment 2.
 *
 * Packet format (128 bytes total):
 *
 *  -----------------------------------------
 *  | type (1) | seq (1) | length (2) | data |
 *  -----------------------------------------
 *
 * type   : packet type (SOT, DATA, ACK, EOT)
 * seq    : sequence number (0–127)
 * length : payload length (0–124)
 * data   : payload bytes (only used for DATA packets)
 *
 * Remaining unused bytes are padded with zeros.
 */
public class DSPacket {

    /* -------------------------
       Protocol constants
       ------------------------- */

    // Maximum packet size required by the protocol
    public static final int MAX_PACKET_SIZE = 128;

    // Maximum payload size allowed in DATA packets
    public static final int MAX_PAYLOAD_SIZE = 124;

    // Packet type identifiers
    public static final byte TYPE_SOT  = 0;   // Start Of Transmission
    public static final byte TYPE_DATA = 1;   // Data packet
    public static final byte TYPE_ACK  = 2;   // Acknowledgment
    public static final byte TYPE_EOT  = 3;   // End Of Transmission


    /* -------------------------
       Packet fields
       ------------------------- */

    private byte type;        // Packet type
    private byte seqNum;      // Sequence number (0–127)
    private short length;     // Payload length
    private byte[] payload;   // Payload data


    /* --------------------------------------------------------
       Constructor used when SENDING packets
       -------------------------------------------------------- */

    /**
     * Creates a packet to be transmitted over the network.
     *
     * DATA packets:
     *  - Must contain payload of 1..124 bytes
     *
     * Control packets (SOT, ACK, EOT):
     *  - Must contain no payload
     */
    public DSPacket(byte type, int seqNum, byte[] data) {

        this.type = type;

        // Sequence numbers operate modulo 128
        this.seqNum = (byte) (seqNum % 128);

        if (type == TYPE_DATA) {

            // Validate DATA payload size
            if (data == null || data.length <= 0 || data.length > MAX_PAYLOAD_SIZE) {
                throw new IllegalArgumentException(
                    "DATA payload must be between 1 and 124 bytes."
                );
            }

            this.payload = data;

        } else {

            // Control packets contain no payload
            this.payload = new byte[0];
        }

        this.length = (short) this.payload.length;
    }


    /* --------------------------------------------------------
       Constructor used when RECEIVING packets
       -------------------------------------------------------- */

    /**
     * Parses a received 128-byte UDP datagram into a DSPacket object.
     */
    public DSPacket(byte[] rawBytes) {

        ByteBuffer bb = ByteBuffer.wrap(rawBytes);

        this.type = bb.get();
        this.seqNum = bb.get();
        this.length = bb.getShort();

        /* ---- Validate packet type ---- */

        if (this.type != TYPE_SOT &&
            this.type != TYPE_DATA &&
            this.type != TYPE_ACK &&
            this.type != TYPE_EOT) {

            throw new IllegalArgumentException(
                "Invalid packet type: " + this.type
            );
        }

        /* ---- Validate payload length ---- */

        if (this.length < 0 || this.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                "Invalid payload length: " + this.length
            );
        }

        /* ---- Control packets must have zero payload ---- */

        if ((this.type == TYPE_SOT ||
             this.type == TYPE_ACK ||
             this.type == TYPE_EOT) && this.length != 0) {

            throw new IllegalArgumentException(
                "Control packets must have payload length 0."
            );
        }

        /* ---- DATA packets must contain payload ---- */

        if (this.type == TYPE_DATA && this.length <= 0) {
            throw new IllegalArgumentException(
                "DATA packets must contain payload."
            );
        }

        /* ---- Read payload ---- */

        this.payload = new byte[this.length];
        bb.get(this.payload);
    }


    /* --------------------------------------------------------
       Convert packet to 128-byte datagram
       -------------------------------------------------------- */

    /**
     * Converts this packet into a fixed 128-byte array suitable
     * for UDP transmission.
     */
    public byte[] toBytes() {

        ByteBuffer bb = ByteBuffer.allocate(MAX_PACKET_SIZE);

        bb.put(type);
        bb.put(seqNum);
        bb.putShort(length);
        bb.put(payload);

        // Remaining bytes are automatically padded with zeros
        return bb.array();
    }


    /* --------------------------------------------------------
       Getters
       -------------------------------------------------------- */

    public byte getType() {
        return type;
    }

    /**
     * Returns sequence number as unsigned integer (0-255).
     * Java bytes are signed, so this prevents negative values.
     */
    public int getSeqNum() {
        return seqNum & 0xFF;
    }

    public int getLength() {
        return length;
    }

    public byte[] getPayload() {
        return payload;
    }
}