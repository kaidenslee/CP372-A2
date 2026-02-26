//handshake, data transfer (stop and wait, go back n), EOT 
//if window is omitted -> stop and wait 
//if window size is provided --> go back n


public class Receiver{
//make these private as DSpacket has authority 
  private static final packetSOT = 0;
  private static final packetDATA = 1;
  private static final packetACK = 2;
  private static final packetEOT = 3;
  private static final packetMOD = 128;


  //handshake. recieves SOT (type 0, seq 0), sends ACK (type 2, seq 0)
  public class handshake{

  //stop and wait:
  //if input is 0 bytes, sender sends EOT with seq = 1 immediately after handshake, ack and close
  //otherwise, maintain expectedSeq, if recieved seq == expected seq, write payload, send ack for that seq, increment expectedseq
    //if dup or out of order, resend ack for last-in-order packet
  public class data_transfer{
    
    

  

  


}
