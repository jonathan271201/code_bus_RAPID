package report;

import core.DTNHost;
import core.Message;
import core.MessageListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Report delay setiap pesan yang sampai ke tujuan
 * @author Kalis
 */
public class DelayPerMessageReport extends Report implements MessageListener {
	public static final String HEADER = "Message ID \t Delay";

    /** Delay for each message */
    private Map<String, Double> delays;

    /**
     * Constructor.
     */
    public DelayPerMessageReport() {
        init();
    }

    @Override
    public void init() {
        super.init();
        write(HEADER);
        this.delays = new HashMap<>();
    }

    public void newMessage(Message m) {
        // do nothing
    }

    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean firstDelivery) {
        if (m.getTo() == to && firstDelivery) { // hanya untuk pesan yang berhasil dikirim dan sampai ke tujuan
            double currentDelay = getSimTime() - m.getCreationTime();
            delays.put(m.getId(), currentDelay);
            write(m.getId() + "\t" + String.format("%.2f", currentDelay));
        }
    }

    @Override
    public void done() {
        super.done();
    }

    // nothing to implement for the rest
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
    }
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
    }
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
    }
}