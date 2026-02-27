//handshake, data transfer (stop and wait, go back n), EOT 
//if window is omitted -> stop and wait 
//if window size is provided --> go back n


public class Receiver{
  private static final int PACKET_SIZE = DSPacket.MAX_PACKET_SIZE;
  private DatagramSocket socket;
  private int MOD = 128;

  private InetAddress sender_ip;
  private int sender_ack_port;
  private int rcv_data_port;
  private String output_file;
  private int RN;

  
  private int windowSize; 
  private int ackCount = 0;

  public Receiver(InetAddress sender_ip, int sender_ack_port, int rcv_data_port, String output_file, int RN, int windowSize){
    this sender_ip = sender_ip;
    this.sender_ack_port = sender_ack_port;
    this.rcv_data_port = rcv_data_port;
    this.output_file = output_file;
    this.windowSize = windowSize;
    this.RN = RN;

    this.socket = new DataGramSocket(this.rcvDataPort);
    }

    private void sendAck(int seq){
      ackCount++;
      if(ChaosEngine.shouldDrop(ackCount, RN)){
        return;//simulate loss
      }
      DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seq, null);
      byte [] ackBytes = ack.toBytes();
      DatagramPacket datagram_packet = new DatagramPacket(ackBytes, ackBytes.length, sender_ip, sender_ack_port);
      socket.send(datagram_packet);

    }
  //handshake. recieves SOT (type 0, seq 0), sends ACK (type 2, seq 0)
  private void handshake(){
    boolean flag = true;
    while(flag){
      DatagramPacket input = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
      socket.receive(input);
      DSPacket packet = new DSPacket(input.getData());

      if (packet.getType() == DSPacket.TYPE_SOT && packet.getSeqNum() == 0){
        sendAck(0);
        return;

      }
    }

  }
  private void stop_and_wait(){


  }

  private void go_back_n(){

  }


  public void run(){
    handshake();
    if(windowSize <= 0){
      stop_and_wait();
    }else{
      go_back_n();
    }
    socket.close();


public static void main(String[] args){
}
   
        
    
    
