
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


//if window is omitted -> stop and wait 
//if window size is provided --> go back n

//receiver is designed to handle handshake, data transfer (stop and wait or go back n), and EOT
public class Receiver{
  private static final int PACKET_SIZE = DSPacket.MAX_PACKET_SIZE;//fixed packet size
  private DatagramSocket socket;//udp socket receiver
  private int MOD = 128;

  private InetAddress sender_ip;
  private int sender_ack_port;
  private int rcv_data_port;
  private String output_file;
  private int RN;

  
  private  int windowSize; 
  private int ackCount = 0;//counter used by chaosEngine 

  public Receiver(InetAddress sender_ip, int sender_ack_port, int rcv_data_port, String output_file, int RN, int windowSize) throws IOException{
    this.sender_ip = sender_ip;
    this.sender_ack_port = sender_ack_port;
    this.rcv_data_port = rcv_data_port;
    this.output_file = output_file;
    this.windowSize = windowSize;
    this.RN = RN;

    //connect socket so receiver can listen for SOT, data, or EOT
    this.socket = new DatagramSocket(this.rcv_data_port);
    }
  
  
//for sending an ACK paket with given seq number, 
    private void sendAck(int seq) throws IOException{
      ackCount++;
      if(ChaosEngine.shouldDrop(ackCount, RN)){//chaosEngine decides if ACK needs to be dropped
		  System.out.println("receiver: ACK " + seq + " was dropped by ChaosEngine");
          return;//simulate loss
      }
      DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
      byte [] ackBytes = ack.toBytes();
      DatagramPacket datagram_packet = new DatagramPacket(ackBytes, ackBytes.length, sender_ip, sender_ack_port);
      socket.send(datagram_packet);
	  System.out.println("receiver: ACK " + seq + " sent");

    }
    
    
  //handshake. recieves SOT (type 0, seq 0), sends ACK (type 2, seq 0)
  private void handshake()throws IOException{
    while(true){
      DatagramPacket input = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
      socket.receive(input);
      DSPacket packet = new DSPacket(input.getData());

      if (packet.getType() == DSPacket.TYPE_SOT && packet.getSeqNum() == 0){
        sendAck(0);
		System.out.println("receiver: SOT received, ACK 0 sent");
        return;

      }else{
        System.out.println("receiver: non-SOT packet was ignored during handshake. Type was " + packet.getType() + ", seq was " + packet.getSeqNum());
		  
    }
    }

  }
  
  //stop and wait receiver for maintaining expectedseq, write payload, ack seq, and increment expectedseq
  private void stop_and_wait()throws IOException{
    try(FileOutputStream fos = new FileOutputStream(output_file)){
      int expectedSeq = 1;
      int lastInOrder = 0;
	  System.out.println("receiver: Stop-and-wait initiated");

      while (true){
        DatagramPacket input = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
        socket.receive(input);
        DSPacket packet = new DSPacket(input.getData());
        byte type = packet.getType();
        int seq = packet.getSeqNum();

        if (type == DSPacket.TYPE_DATA){
			System.out.println("receiver: DATA received seq " + seq + ", expected " + expectedSeq);
          if(seq == expectedSeq){
            fos.write(packet.getPayload());
			  System.out.println("receiver: Packet " + seq + " accepted and wrote to file");
            lastInOrder = expectedSeq;
            sendAck(seq);
            expectedSeq = (expectedSeq + 1) % MOD;
          }else{
			  System.out.println("receiver: Packet " + seq + " is duplicate or out of order, ACK " + lastInOrder + " is being resent");
			  sendAck(lastInOrder);
          }
          }else if(type == DSPacket.TYPE_EOT){
			System.out.println("receiver: EOT received seq " + seq);
            sendAck(seq);
			System.out.println("receiver: Complete, closing receiver");
            return;
        }else{
			System.out.println("receiver: ignored packet type " + type + ", seq " + seq);
          //ignore 
        
        }
      }
    }

  }
 
//go back n receiver 
//buffers out of order packets within window and deliver in order when able
  private void go_back_n() throws IOException {
        if(windowSize > MOD){
          windowSize = MOD;
        }
        try(FileOutputStream fos = new FileOutputStream(output_file)){
      		int expectedSeq = 1;
      		int lastDelivered = 0;

          	DSPacket[] buffer = new DSPacket[MOD];
          	boolean[] received = new boolean[MOD];

			System.out.println("receiver: Go-back-N initiated with window size " + windowSize);

          while(true){
            DatagramPacket input = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
            socket.receive(input);
            DSPacket packet = new DSPacket(input.getData());
            byte type = packet.getType();
            int seq = packet.getSeqNum();

            if(type == DSPacket.TYPE_DATA){
				System.out.println("receiver: DATA received seq " + seq + ", expected" + expectedSeq);
              	if(((seq - expectedSeq + MOD)%MOD) < windowSize){
                	if(!received[seq]){
                  		buffer[seq] = packet;
                  		received[seq] = true;
						System.out.println("receiver: buffered packet seq " + seq);
                	}else {
						System.out.println("receiver: duplicate packet seq " + seq + " has already been buffered");
					}
				}else{
					System.out.println("receiver: packet seq " + seq + " is outside receiver window and has been discarded");
                	
                }

              
                while(received[expectedSeq]){
                  DSPacket nextUp = buffer[expectedSeq];
                  fos.write(nextUp.getPayload());
                  buffer[expectedSeq] = null;
                  received[expectedSeq] = false;

				  System.out.println("receiver: delivered buffered packet seq " + expectedSeq);

                  lastDelivered = expectedSeq;
                  expectedSeq = (expectedSeq + 1) % MOD;
                }

				System.out.println("receiver: sending cumulative ACK " + lastDelivered);
                sendAck(lastDelivered);
                
            } else if(type == DSPacket.TYPE_EOT){
				System.out.println("receiver: EOT received seq " + seq);
				sendAck(seq);

                  while (received[expectedSeq]){
                    DSPacket nextUp = buffer[expectedSeq];
                    fos.write(nextUp.getPayload());
                    buffer[expectedSeq] = null;
                    received[expectedSeq] = false;
                    lastDelivered = expectedSeq;
                    expectedSeq = (expectedSeq + 1) % MOD;
                  }

				  System.out.println("receiver: complete, closing receiver");
                  return;
                } else {
					System.out.println("receiver: ignored packet type " + type + ", seq " + seq);
                	//ignore others
                }
              }
           

          }
  
    //ensure window size N is multiple of 4, n <= total packet size. 
  
  }

//runs receiver via handshake, choosing between stop and wait or go back n, and closing socket
  public void run()throws IOException{
	System.out.println("receiver is listening on port" + rcv_data_port);
	
    handshake();
    if(windowSize <= 0){
      stop_and_wait();
    }else{
      go_back_n();
    }
    socket.close();
	System.out.println("receiver: socket closed");
  }

public static void main(String[] args) throws Exception{
	 if (args.length < 5) {
		 	System.out.println("receiver: formatted incorrectly");
	    	return; 
	    }
    InetAddress sender_ip = InetAddress.getByName(args[0]);
    int sender_ack_port = Integer.parseInt(args[1]);
    int rcv_data_port = Integer.parseInt(args[2]);
    String output_file = args[3];
    int RN = Integer.parseInt(args[4]);
    int windowSize = 0;
   
    if(args.length >= 6){
      windowSize = Integer.parseInt(args[5]);
    }
    

    Receiver receiver = new Receiver(sender_ip, sender_ack_port, rcv_data_port, output_file, RN, windowSize);
    receiver.run();

}
}
