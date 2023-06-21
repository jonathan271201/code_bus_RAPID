package report;

import core.DTNHost;
import java.util.HashMap;
import core.Message;
import core.MessageListener;
/**
 *
 * @author Kalis
 */
public class MessageTTLReport extends Report implements MessageListener{
    private HashMap<String, Integer> ttlMap;
    
    public MessageTTLReport() {
        this.ttlMap = new HashMap<String, Integer>();
    }

    @Override
    public void newMessage(Message m) {
        // Get the TTL of the message
        int ttl = m.getTtl();
        // Get the message ID
        String msgId = m.getId();
        
        // Add the message ID and TTL to the map
        ttlMap.put(msgId, ttl);
    }

    @Override
    public void done() {
        // Print the message IDs and their TTLs
        for (String msgId : ttlMap.keySet()) {
            System.out.println("Message ID: " + msgId + ", TTL: " + ttlMap.get(msgId));
        }
    }
    
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {}
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {}
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {}
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean firstDelivery) {}
    
}