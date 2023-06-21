/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import routing.rapid.DelayEntry;
import routing.rapid.DelayTable;
import routing.rapid.MeetingEntry;

import core.*;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.LinkedList;
import movement.Path;
import routing.community.Duration;

/**
 *
 * @author Kalis
 */
public class EstimateKnapsackBusTJRouter extends ActiveRouterForKnapsack {
    // timestamp for meeting a host in seconds

    private double timestamp;
    // utility algorithm (minimizing average delay | minimizing missed deadlines |
    // minimizing maximum delay)
    private Map<Integer, DTNHost> hostMapping;

    private final UtilityAlgorithm ALGORITHM = UtilityAlgorithm.AVERAGE_DELAY;
    private static final double INFINITY = 99999;

    // interval to verify ongoing connections in seconds
    private static final double UTILITY_INTERAL = 100.0;
    private DelayTable delayTable;

    protected Map<DTNHost, Double> startTimestamps;
    protected Map<DTNHost, List<Duration>> connHistory;
    LinkedList<Integer> lengthMsg;
    LinkedList<Integer> lengthMsgDrop;
    LinkedList<Double> utilityMsg;
    LinkedList<Double> utilityMsgDrop;
    LinkedList<Message> tempMsg;
    LinkedList<Message> tempMsgDrop;
    LinkedList<Message> tempMsgTerpilih;
    LinkedList<Message> tempMsgLowersUtil;
    Map<DTNHost, Integer> retrictionPerPeer;
    private DTNHost getOtherHost;
    private static final int bytes = 1000;

    /**
     * Constructor. Creates a new message router based on the settings in the
     * given Settings object.
     *
     * @param s The settings object
     */
    public EstimateKnapsackBusTJRouter(Settings s) {
        super(s);

        delayTable = null;
        timestamp = 0.0;
        hostMapping = new HashMap<Integer, DTNHost>();
        startTimestamps = new HashMap<DTNHost, Double>();
        connHistory = new HashMap<DTNHost, List<Duration>>();
        lengthMsg = new LinkedList<Integer>();
        lengthMsgDrop = new LinkedList<Integer>();
        utilityMsg = new LinkedList<Double>();
        utilityMsgDrop = new LinkedList<Double>();
        tempMsg = new LinkedList<Message>();
        tempMsgDrop = new LinkedList<Message>();
        tempMsgTerpilih = new LinkedList<Message>();
        tempMsgLowersUtil = new LinkedList<Message>();
        retrictionPerPeer = new HashMap<DTNHost, Integer>();
        getOtherHost = null;
    }

    @Override
    public void init(DTNHost host, List<MessageListener> mListeners) {
        super.init(host, mListeners);
        delayTable = new DelayTable(host);
//        System.out.println(host);
    }

    /**
     * Copy constructor.
     *
     * @param r The router prototype where setting values are copied from
     */
    protected EstimateKnapsackBusTJRouter(EstimateKnapsackBusTJRouter r) {
        super(r);

        delayTable = r.delayTable;
        timestamp = r.timestamp;
        hostMapping = r.hostMapping;
        startTimestamps = r.startTimestamps;
        connHistory = r.connHistory;
        lengthMsg = r.lengthMsg;
        lengthMsgDrop = r.lengthMsgDrop;
        utilityMsg = r.utilityMsg;
        utilityMsgDrop = r.utilityMsgDrop;
        tempMsg = r.tempMsg;
        tempMsgDrop = r.tempMsgDrop;
        tempMsgTerpilih = r.tempMsgTerpilih;
        tempMsgLowersUtil = r.tempMsgLowersUtil;
        getOtherHost = r.getOtherHost;
        retrictionPerPeer = r.retrictionPerPeer;
    }

    @Override
    public void changedConnection(Connection con) {
        if (con.isUp()) {

            DTNHost otherHost = con.getOtherNode(getHost());
            this.getOtherHost = null;
            this.getOtherHost = otherHost;
            this.startTimestamps.put(otherHost, SimClock.getTime());

            /* new connection */
            //simulate control channel on connection up without sending any data
            this.timestamp = SimClock.getTime();

            //synchronize all delay table entries
            synchronizeDelayTables(con);

            //synchronize all meeting time entries 
            synchronizeMeetingTimes(con);
            updateDelayTableStat(con);

            //synchronize acked message ids
            synchronizeAckedMessageIDs(con);
            deleteAckedMessages();
            //map DTNHost to their address
            doHostMapping(con);
            this.delayTable.dummyUpdateConnection(con);

            //Perhitungan Estimate Time Duration
            if (!String.valueOf(getHost().toString().charAt(0)).equals("s")) { //Pengecekan apakah dia Sensor atau bukan
                int capacityOFKnapsack = 0;                                    //Jika bukan, dilakukan proses pengiriman pesan
                Tuple<Double, Double> estimateTime = getEstimateTimeDuration(getHost(), otherHost); //Nilai ETD transfer dari host yang sedang aktif dan host tujuan pengiriman
                int tfSpeed = this.getTransferSpeed(getHost()) / bytes;
                capacityOFKnapsack = getCapacityOFKnapsack(estimateTime.getValue(), tfSpeed); //Perhitungan kapasitas knapsack yang dapat digunakan untuk proses pengiriman pesan : ET dan kecepatan transfer data dari host pengirim ke host tujuan
                this.retrictionPerPeer.put(otherHost, capacityOFKnapsack); //Nilai kapasitas disimpan dalam Map (Batasan)
                cekSyaratKnapsackSend(getHost(), otherHost); //Cek syarat untuk pengiriman pesan
            }
        } else {
            /* connection went down */
            //update connection
            double time = SimClock.getTime() - this.timestamp;
            this.delayTable.updateConnection(con, time);

            synchronizeAckedMessageIDs(con);
            deleteAckedMessages();
            updateAckedMessageIds();
            synchronizeDelayTables(con);

            DTNHost otherHost = con.getOtherNode(getHost());
            double times = 0;
            if (this.startTimestamps.containsKey(otherHost)) {
                times = this.startTimestamps.get(otherHost);
            }

            double etime = SimClock.getTime();
            List<Duration> history;
            if (!this.connHistory.containsKey(otherHost)) {
                history = new LinkedList<Duration>();
                this.connHistory.put(otherHost, history);
            } else {
                history = this.connHistory.get(otherHost);
            }

            // add this connection to the list
            if (etime - times > 0) {
                history.add(new Duration(times, etime));
            }

            this.connHistory.put(otherHost, history);
            this.startTimestamps.remove(otherHost);

            this.lengthMsg.clear();
            this.utilityMsg.clear();
            this.tempMsg.clear();
            this.tempMsgTerpilih.clear();
        }
    }

    private void doHostMapping(Connection con) {
        DTNHost host = getHost();
        DTNHost otherHost = con.getOtherNode(host);
        EstimateKnapsackBusTJRouter otherRouter = ((EstimateKnapsackBusTJRouter) otherHost.getRouter());

        // propagate host <-> address mapping
        this.hostMapping.put(host.getAddress(), host);
        this.hostMapping.put(otherHost.getAddress(), otherHost);
        this.hostMapping.putAll(otherRouter.hostMapping);
        otherRouter.hostMapping.putAll(this.hostMapping);
    }

    private void updateDelayTableStat(Connection con) {
        DTNHost otherHost = con.getOtherNode(getHost());
        EstimateKnapsackBusTJRouter otherRouter = ((EstimateKnapsackBusTJRouter) otherHost.getRouter());
        int from = otherHost.getAddress();

        for (Message m : getMessageCollection()) {
            int to = m.getTo().getAddress();
            MeetingEntry entry = otherRouter.getDelayTable().getMeetingEntry(from, to);
            if (entry != null) {
                this.delayTable.getDelayEntryByMessageId(m.getId()).setChanged(true);
            }
        }
    }

    private void synchronizeDelayTables(Connection con) {
        DTNHost otherHost = con.getOtherNode(getHost());
        EstimateKnapsackBusTJRouter otherRouter = (EstimateKnapsackBusTJRouter) otherHost.getRouter();
        DelayEntry delayEntry = null;
        DelayEntry otherDelayEntry = null;

        //synchronize all delay table entries
        for (Map.Entry<String, DelayEntry> entry1 : this.delayTable.getDelayEntries()) {
            Message m = entry1.getValue().getMessage();
            delayEntry = this.delayTable.getDelayEntryByMessageId(m.getId());
            assert (delayEntry != null);
            otherDelayEntry = otherRouter.delayTable.getDelayEntryByMessageId(m.getId());

            if (delayEntry.getDelays() == null) {
                continue;
            }
            //for all hosts check delay entries and create new if they doesn't exist
            for (Map.Entry<DTNHost, Tuple<Double, Double>> entry : delayEntry.getDelays()) {
                DTNHost myHost = entry.getKey();
                Double myDelay = entry.getValue().getKey();
                Double myTime = entry.getValue().getValue();

                //create a new host entry if host entry at other host doesn't exist
                if ((otherDelayEntry == null) || (!otherDelayEntry.contains(myHost))) {
                    //parameters: 
                    //m The message 
                    //myHost The host which contains a copy of this message
                    //myDelay The estimated delay
                    //myTime The entry was last changed
                    otherRouter.updateDelayTableEntry(m, myHost, myDelay, myTime);
                } else {
                    //check last update time of other hosts entry and update it 
                    if (otherDelayEntry.isOlderThan(myHost, delayEntry.getLastUpdate(myHost))) {
                        //parameters: 
                        //m The message 
                        //myHost The host which contains a copy of this message
                        //myDelay The estimated delay
                        //myTime The entry was last changed 

                        otherRouter.updateDelayTableEntry(m, myHost, myDelay, myTime);
                    }

                    if ((otherDelayEntry.isAsOldAs(myHost, delayEntry.getLastUpdate(myHost))) && (delayEntry.getDelayOf(myHost) > otherDelayEntry.getDelayOf(myHost))) {
                        //parameters: 
                        //m The message 
                        //myHost The host which contains a copy of this message
                        //myDelay The estimated delay
                        //myTime The entry was last changed 

                        otherRouter.updateDelayTableEntry(m, myHost, myDelay, myTime);
                    }
                }
            }
        }
    }

    private void synchronizeMeetingTimes(Connection con) {
        DTNHost otherHost = con.getOtherNode(getHost());
        EstimateKnapsackBusTJRouter otherRouter = (EstimateKnapsackBusTJRouter) otherHost.getRouter();
        MeetingEntry meetingEntry = null;
        MeetingEntry otherMeetingEntry = null;

        //synchronize all meeting time entries
        for (int i = 0; i < this.delayTable.getMeetingMatrixDimension(); i++) {
            for (int k = 0; k < this.delayTable.getMeetingMatrixDimension(); k++) {
                meetingEntry = this.delayTable.getMeetingEntry(i, k);

                if (meetingEntry != null) {
                    otherMeetingEntry = otherRouter.delayTable.getMeetingEntry(i, k);
                    //create a new meeting entry if meeting entry at other host doesn't exist
                    if (otherMeetingEntry == null) {
                        otherRouter.delayTable.setAvgMeetingTime(i, k, meetingEntry.getAvgMeetingTime(), meetingEntry.getLastUpdate(), meetingEntry.getWeight());
                    } else {
                        //check last update time of other hosts entry and update it 
                        if (otherMeetingEntry.isOlderThan(meetingEntry.getLastUpdate())) {
                            otherRouter.delayTable.setAvgMeetingTime(i, k, meetingEntry.getAvgMeetingTime(), meetingEntry.getLastUpdate(), meetingEntry.getWeight());
                        }

                        if ((otherMeetingEntry.isAsOldAs(meetingEntry.getLastUpdate())) && (meetingEntry.getAvgMeetingTime() > otherMeetingEntry.getAvgMeetingTime())) {
                            otherRouter.delayTable.setAvgMeetingTime(i, k, meetingEntry.getAvgMeetingTime(), meetingEntry.getLastUpdate(), meetingEntry.getWeight());
                        }
                    }
                }
            }
        }
    }

    private void synchronizeAckedMessageIDs(Connection con) {
        DTNHost otherHost = con.getOtherNode(getHost());
        EstimateKnapsackBusTJRouter otherRouter = (EstimateKnapsackBusTJRouter) otherHost.getRouter();

        this.delayTable.addAllAckedMessageIds(otherRouter.delayTable.getAllAckedMessageIds());
        otherRouter.delayTable.addAllAckedMessageIds(this.delayTable.getAllAckedMessageIds());

        assert (this.delayTable.getAllAckedMessageIds().equals(otherRouter.delayTable.getAllAckedMessageIds()));
    }

    /**
     * Deletes the messages from the message buffer that are known to be ACKed
     * and also delete the message entries of the ACKed messages in the delay
     * table
     */
    private void deleteAckedMessages() {
        for (String id : this.delayTable.getAllAckedMessageIds()) {

            //delete messages from the delay table
            if (this.delayTable.getDelayEntryByMessageId(id) != null) {
                assert (this.delayTable.removeEntry(id) == true);
            }
        }
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);

        /* was this node the final recipient of the message? */
        if (isDeliveredMessage(m)) {
            this.delayTable.addAckedMessageIds(id);
        }
        return m;
    }

    /**
     * Delete old message ids from the acked message id set which time to live
     * was passed
     */
    private void updateAckedMessageIds() {
        ArrayList<String> removableIds = new ArrayList<String>();

        for (String id : this.delayTable.getAllAckedMessageIds()) {
            Message m = this.getMessage(id);
            if ((m != null) && (m.getTtl() <= 0)) {
                removableIds.add(id);
            }
        }

        if (removableIds.size() > 0) {
            this.delayTable.removeAllAckedMessageIds(removableIds);
        }
    }

    /**
     * Updates an delay table entry for given message and host
     *
     * @param m The message
     * @param host The host which contains a copy of this message
     * @param delay The estimated delay
     * @param time The entry was last changed
     */
    public void updateDelayTableEntry(Message m, DTNHost host, double delay, double time) {
        DelayEntry delayEntry;

        if ((delayEntry = this.delayTable.getDelayEntryByMessageId(m.getId())) == null) {
            delayEntry = new DelayEntry(m);
            this.delayTable.addEntry(delayEntry);
        }
        assert ((delayEntry != null) && (this.delayTable.getDelayEntryByMessageId(m.getId()) != null));

        if (delayEntry.contains(host)) {
            delayEntry.setHostDelay(host, delay, time);
        } else {
            delayEntry.addHostDelay(host, delay, time);
        }
    }

    @Override
    public boolean createNewMessage(Message m) {
        boolean stat = super.createNewMessage(m);

        //if message was created successfully add the according delay table entry
        if (stat) {
            updateDelayTableEntry(m, getHost(), estimateDelay(m, getHost(), true), SimClock.getTime());
//            System.out.println("PESAN BARU "+m.getId()+" di buat oleh "+getHost()+" ukuran "+m.getSize()+" Tujuan "+m.getTo());
        }

        return stat;
    }

    @Override
    public int receiveMessage(Message m, DTNHost from) {
        int stat = super.receiveMessage(m, from);
        //if message was received successfully add the according delay table entry
        if (stat == 0) {
            DTNHost host = getHost();
            double time = SimClock.getTime();
            double delay = estimateDelay(m, host, true);
            updateDelayTableEntry(m, host, delay, time);
            ((EstimateKnapsackBusTJRouter) from.getRouter()).updateDelayTableEntry(m, host, delay, time);
        }

        return stat;
    }

    /**
     * Returns the current marginal utility (MU) value for a connection of two
     * nodes
     *
     * @param msg One message of the message queue
     * @param con The connection
     * @param host The host which contains a copy of this message
     * @return the current MU value
     */
    private double getMarginalUtility(Message msg, DTNHost otherHost, DTNHost host) {
        final EstimateKnapsackBusTJRouter otherRouter = (EstimateKnapsackBusTJRouter) (otherHost.getRouter());

        return getMarginalUtility(msg, otherRouter, host);
    }

    /**
     * Returns the current marginal utility (MU) value for a host of a specified
     * router
     *
     * @param msg One message of the message queue
     * @param router The router which the message could be send to
     * @param host The host which contains a copy of this message
     * @return the current MU value
     */
    private double getMarginalUtility(Message msg, EstimateKnapsackBusTJRouter router, DTNHost host) {
        double marginalUtility = 0.0;
        double utility = 0.0;			// U(i): The utility of the message (packet) i
        double utilityOld = 0.0;

        assert (this != router);
        utility = router.computeUtility(msg, host, true);
        utilityOld = this.computeUtility(msg, host, true);

        // s(i): The size of message i
        // delta(U(i)) / s(i)
        if ((utilityOld == -INFINITY) && (utility != -INFINITY)) {
            marginalUtility = (Math.abs(utility) / msg.getSize());
        } else if ((utility == -INFINITY) && (utilityOld != -INFINITY)) {
            marginalUtility = (Math.abs(utilityOld) / msg.getSize());
        } else if ((utility == utilityOld) && (utility != -INFINITY)) {
            marginalUtility = (Math.abs(utility) / msg.getSize());
        } else {
            marginalUtility = (utility - utilityOld) / msg.getSize();
        }

        return marginalUtility;
    }

    private double computeUtility(Message msg, DTNHost host, boolean recompute) {
        double utility = 0.0;		// U(i): The utility of the message (packet) i
        double packetDelay = 0.0;	// D(i): The expected delay of message i

        switch (ALGORITHM) {
            // minimizing average delay
            // U(i) = -D(i)
            case AVERAGE_DELAY: {
                packetDelay = estimateDelay(msg, host, recompute);
                utility = -packetDelay;
                break;
            }
            // minimizing missed deadlines
            // L(i)   : The life time of message (packet) i
            // T(i)   : The time since creation of message i
            // a(i)   : A random variable that determines the remaining time to deliver message i
            // P(a(i)): The probability that the message will be delivered within its deadline
            //
            //  	   / P(a(i) < L(i) - T(i)) , L(i) > T(i)
            // U(i) = <|
            // 		   \           0   		   , otherwise
            case MISSED_DEADLINES: {
                //life time in seconds
                double lifeTime = msg.getTtl() * 60;
                //creation time in seconds
                double timeSinceCreation = SimClock.getTime() - msg.getCreationTime();
                double remainingTime = computeRemainingTime(msg);
                if (lifeTime > timeSinceCreation) {
                    // compute remaining time by using metadata
                    if (remainingTime < lifeTime - timeSinceCreation) {
                        utility = lifeTime - timeSinceCreation - remainingTime;
                    } else {
                        utility = 0;
                    }
                } else {
                    utility = 0;
                }
                packetDelay = computePacketDelay(msg, host, remainingTime);
                break;
            }
            // minimizing maximum delay
            // S   : Set of all message in buffer of X
            //
            // 		   / -D(i) , D(i) >= D(j) for all j element of S
            // U(i) = <|
            // 		   \   0   , otherwise
            case MAXIMUM_DELAY: {
                packetDelay = estimateDelay(msg, host, recompute);
                Collection<Message> msgCollection = getMessageCollection();
                for (Message m : msgCollection) {
                    if (m.equals(msg)) {
                        continue;	//skip 
                    }
                    if (packetDelay < estimateDelay(m, host, recompute)) {
                        packetDelay = 0;
                        break;
                    }
                }
                utility = -packetDelay;
                break;
            }
            default:
        }
        return utility;
    }

    /*
	 * Estimate-Delay Algorithm
	 * 
	 * 1) Sort packets in Q in decreasing order of T(i). Let b(i) be the sum of 
	 * sizes of packets that precede i, and B the expected transfer opportunity 
	 * in bytes between X and Z.
	 * 
	 * 2. X by itself requires b(i)/B meetings with Z to deliver i. Compute the 
	 * random variable MX(i) for the corresponding delay as
	 * MX(i) = MXZ + MXZ + . . . b(i)/B times (4)
	 *  
	 * 3. Let X1 , . . . , Xk ⊇ X be the set of nodes possessing a replica of i. 
	 * Estimate remaining time a(i) as  
	 * a(i) = min(MX1(i), . . . , MXk(i))
	 * 
	 * 4. Expected delay D(i) = T(i) + E[a(i)]
     */
    /**
     * Returns the expected delay for a message if this message was sent to a
     * specified host
     *
     * @param msg The message which will be transfered
     * @return the expected packet delay
     */
    private double estimateDelay(Message msg, DTNHost host, boolean recompute) {
        double remainingTime = 0.0;	//a(i): random variable that determines the remaining time to deliver message i
        double packetDelay = 0.0;	//D(i): expected delay of message i

        //if delay table entry for this message doesn't exist or the delay table
        //has changed recompute the delay entry
        if ((recompute) && ((this.delayTable.delayHasChanged(msg.getId())) || (this.delayTable.getDelayEntryByMessageId(msg.getId()).getDelayOf(host) == null) /*|| (delayTable.getEntryByMessageId(msg.getId()).getDelayOf(host) == INFINITY))*/)) {
            // compute remaining time by using metadata
            remainingTime = computeRemainingTime(msg);
            packetDelay = Math.min(this.INFINITY, computePacketDelay(msg, host, remainingTime));

            //update delay table
            updateDelayTableEntry(msg, host, packetDelay, SimClock.getTime());
        } else {
            packetDelay = this.delayTable.getDelayEntryByMessageId(msg.getId()).getDelayOf(host);
        }

        return packetDelay; //Nilai perkiraan delay dari Messages
    }

    private double computeRemainingTime(Message msg) {
        double transferTime = this.INFINITY;	//MX(i):random variable for corresponding transfer time delay
        double remainingTime = 0.0;		//a(i): random variable that determines the	remaining time to deliver message i

        remainingTime = computeTransferTime(msg, msg.getTo());
        if (this.delayTable.getDelayEntryByMessageId(msg.getId()) != null) {
            for (Map.Entry<DTNHost, Tuple<Double, Double>> entry : this.delayTable.getDelayEntryByMessageId(msg.getId()).getDelays()) {
                DTNHost host = entry.getKey();
                if (host == getHost()) {
                    continue;	// skip
                }
                transferTime = ((EstimateKnapsackBusTJRouter) host.getRouter()).computeTransferTime(msg, msg.getTo()); //MXm(i)	with m element of [0...k]
                remainingTime = Math.min(transferTime, remainingTime);	//a(i) = min(MX0(i), MX1(i), ... ,MXk(i)) 
            }
        }

        return remainingTime; //Sisa waktu untuk mengirim Messages
    }

    private double computeTransferTime(Message msg, DTNHost host) {
        Collection<Message> msgCollection = getMessageCollection();
        double transferOpportunity = 0;		//B:    expected transfer opportunity in bytes between X and Z
        double packetsSize = 0.0;		//b(i): sum of sizes of packets that precede the actual message
        double transferTime = this.INFINITY;	//MX(i):random variable for corresponding transfer time delay
        double meetingTime = 0.0;		//MXZ:  random variable that represent the meeting Time between X and Y

        // packets are already sorted in decreasing order of receive time
        // sorting packets in decreasing order of time since creation is optional
        // sort packets in decreasing order of time since creation
        // compute sum of sizes of packets that precede the actual message 
        for (Message m : msgCollection) {
            if (m.equals(msg)) {
                continue; //skip
            }
            packetsSize = packetsSize + m.getSize();
        }

        // compute transfer time  
        transferOpportunity = this.delayTable.getAvgTransferOpportunity();	//B in bytes

        MeetingEntry entry = this.delayTable.getIndirectMeetingEntry(this.getHost().getAddress(), host.getAddress());
        // a meeting entry of null means that these hosts have never met -> set transfer time to maximum
        if (entry == null) {
            transferTime = this.INFINITY;		//MX(i)
        } else {
            meetingTime = entry.getAvgMeetingTime();	//MXZ
            transferTime = meetingTime * Math.ceil(packetsSize / transferOpportunity);	// MX(i) = MXZ * ceil[b(i) / B]
        }

        return transferTime; //Waktu untuk transfer Messages
    }

    private double computePacketDelay(Message msg, DTNHost host, double remainingTime) {
        double timeSinceCreation = 0.0;					//T(i): time since creation of message i 
        double expectedRemainingTime = 0.0;				//A(i): expected remaining time E[a(i)]=A(i)
        double packetDelay = 0.0;						//D(i): expected delay of message i

        //TODO E[a(i)]=Erwartungswert von a(i) mit a(i)=remainingTime
        expectedRemainingTime = remainingTime;

        // compute packet delay
        timeSinceCreation = SimClock.getTime() - msg.getCreationTime();
        packetDelay = timeSinceCreation + expectedRemainingTime;

        return packetDelay; //Nilai delay sebuah Messages
    }

    @Override
    public void update() {
        super.update();
        //Cek sedang transfer atau tidak
        if (isTransferring() || !canStartTransfer()) {
            return;
        }
        //Melakukan pertukaran pesan secara langsung
        // Try messages that can be delivered to final recipient
        if (exchangeDeliverableMessages() != null) {
            return;
        }
        // Otherwise do RAPID-style message exchange 
        // (Mengoptimalkan pengiriman pesan di lingkungan dengan mobilitas tinggi dan topologi jaringan yang dinamis)
        if (tryOtherMessages() != null) {
            return;
        }
    }

    @Override
    protected void transferDone(Connection con) {
        Message transferred = this.getMessage(con.getMessage().getId());
        if (String.valueOf(getHost().toString().charAt(0)).equals("s")) {
            this.deleteMessage(transferred.getId(), false);
        }
//        System.out.println(String.valueOf(getHost().toString().charAt(0)).equals("d"));
    }

    private Tuple<Tuple<Message, Connection>, Double> tryOtherMessages() {
        List<Tuple<Tuple<Message, Connection>, Double>> messages = new ArrayList<Tuple<Tuple<Message, Connection>, Double>>();

        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            EstimateKnapsackBusTJRouter otherRouter = (EstimateKnapsackBusTJRouter) other.getRouter();
            //Memeriksa apakah host tersebut sedang dalam proses mentransfer pesan atau tidak
            if (otherRouter.isTransferring()) {
                continue; // skip hosts that are transferring
            }
            //Memeriksa apakah sensor atau tidak
            if (String.valueOf(other.toString().charAt(0)).equals("s")) {
                continue; // skip kalau sensor
            }

            if (String.valueOf(getHost().toString().charAt(0)).equals("s")) {
                for (Message m : getHost().getMessageCollection()) {
                    double u = getMarginalUtility(m, other, getHost());
                    Tuple<Message, Connection> t1 = new Tuple<Message, Connection>(m, con);
                    Tuple<Tuple<Message, Connection>, Double> t2 = new Tuple<Tuple<Message, Connection>, Double>(t1, u);
                    messages.add(t2);
                }
            } else {

                double mu = 0.0;
                for (Message m : this.tempMsgTerpilih) {
                    if (otherRouter.hasMessage(m.getId())) {
                        continue; // skip messages that the other one already has
                    }

                    mu = getMarginalUtility(m, other, getHost());

                    if ((mu) <= 0) {
                        continue; // skip messages with a marginal utility smaller or equals to 0.
                    }
                    Tuple<Message, Connection> t1 = new Tuple<Message, Connection>(m, con);
                    Tuple<Tuple<Message, Connection>, Double> t2 = new Tuple<Tuple<Message, Connection>, Double>(t1, mu);
                    messages.add(t2);
                }
//            }
            }
        }

        this.delayTable.setChanged(false);
        if (messages== null) {
            return null;
        }
        //Mencoba mengirimkan pesan-pesan tersebut ke host penerima
        return tryTupleMessagesForConnected(messages);	// try to send messages
//        return tryAllMessagesToAllConnections();
    }

    /**
     * Tries to send messages for the connections that are mentioned in the
     * Tuples in the order they are in the list until one of the connections
     * starts transferring or all tuples have been tried.
     *
     * @param tuples The tuples to try
     * @return The tuple whose connection accepted the message or null if none
     * of the connections accepted the message that was meant for them.
     */
    // Mencoba untuk mengirimkan setiap pesan pada list pesan yang belum terkirim ke setiap koneksi pada list koneksi yang tersedia
    private Tuple<Tuple<Message, Connection>, Double> tryTupleMessagesForConnected(
            List<Tuple<Tuple<Message, Connection>, Double>> tuples) {
        //Memeriksa apakah kosong atau tidak
        if (tuples.size() == 0) {
            return null;
        }

        for (Tuple<Tuple<Message, Connection>, Double> t : tuples) {
            Message m = (t.getKey()).getKey();
            Connection con = (t.getKey()).getValue();
            if (startTransfer(m, con) == RCV_OK) { //Proses transfer pesan telah dimulai, dan method akan mengembalikan tuple tersebut sebagai hasil
                return t;
            }
        }
        return null;
    }

    public DelayTable getDelayTable() {
        return this.delayTable;
    }

    @Override
    public EstimateKnapsackBusTJRouter replicate() {
        return new EstimateKnapsackBusTJRouter(this);

    }

    private enum UtilityAlgorithm {
        AVERAGE_DELAY,
        MISSED_DEADLINES,
        MAXIMUM_DELAY;
    }

    public Map<Integer, DTNHost> getHostMapping() {
        return this.hostMapping;
    }

    public DTNHost getOtherHostt() {
        return this.getOtherHost;
    }

    public double getMeetingProb(DTNHost host) {
        MeetingEntry entry = this.delayTable.getIndirectMeetingEntry(getHost().getAddress(), host.getAddress());
        if (entry != null) {
            double prob = (entry.getAvgMeetingTime() * entry.getWeight()) / SimClock.getTime();
            return prob;
        }
        return 0.0;
    }

    public double getAvgDurations(DTNHost nodes) {
        List<Duration> list = getListDuration(nodes);
        Iterator<Duration> duration = list.iterator();
        double hasil = 0;
        //Durasi dihitung dengan cara mengambil selisih antara waktu akhir dan waktu awal pada setiap objek Duration pada List
        //lalu dijumlahkan dan dibagi dengan jumlah objek Duration pada List
        while (duration.hasNext()) {
            Duration d = duration.next();
            hasil += (d.getEnd() - d.getStart());
        }
        int avgDuration = (int) (hasil / list.size());
        if (avgDuration == 0) { //Jika hasilnya 0, maka nilai rata-rata akan diatur menjadi 1
            avgDuration = 1;
        }
        return avgDuration;
    }

    public List<Duration> getListDuration(DTNHost nodes) {
        if (this.connHistory.containsKey(nodes)) {
            return this.connHistory.get(nodes);
        } else {
            List<Duration> d = new LinkedList<>();
            return d;
        }
    }

    public void cekSyaratKnapsackSend(DTNHost thisHost, DTNHost otherHost) {
//        System.out.println(getSyaratKnapsack(thisHost, otherHost));
        if (getSyaratKnapsack(thisHost, otherHost)) {
            getUtilityMsgToArrForSend(thisHost, otherHost); //mendapatkan nilai utilitas setiap pesan
            knapsackSend(thisHost, otherHost); //mengirim pesan ke node tujuan menggunakan algoritma Knapsack
        } else {
//            System.out.println("Msg :"+getMessageCollection().toString());
//            System.out.println("Msg terpilih : " + this.tempMsgTerpilih.toString());
            this.tempMsgTerpilih.addAll(getMessageCollection());
        }
    }

    public boolean getSyaratKnapsack(DTNHost thisHost, DTNHost otherHost) {
        int retriction = getRetrictionForSend(thisHost, otherHost);
//        System.out.println("Retriction " + thisHost + ":" + retriction);
        int isiBuffer = ((thisHost.getRouter().getBufferSize() - thisHost.getRouter().getFreeBufferSize()) / bytes);
//        System.out.println("isiBuffer :" + isiBuffer);
        return isiBuffer > retriction;
    }

    public int getRetrictionForSend(DTNHost thisHost, DTNHost otherHost) {
        return retrictionPerPeer.get(otherHost);
    }

    public int getTransferSpeed(DTNHost thisHost) {
        return thisHost.getInterfaces().get(0).getTransmitSpeed();
    }

    public void getUtilityMsgToArrForSend(DTNHost thisHost, DTNHost otherHost) {
        for (Message m : thisHost.getMessageCollection()) {
            this.lengthMsg.add(m.getSize() / bytes); //in kiloBytes
            this.utilityMsg.add(getMarginalUtility(m, otherHost, thisHost));
        }
    }

    public void getUtilityMsgToArrForDrop(LinkedList<Message> msg, DTNHost thisHost, DTNHost otherHost) {
        for (Message m : msg) {
            this.lengthMsgDrop.add(m.getSize() / bytes); //in kiloBytes
            this.utilityMsgDrop.add(getMarginalUtility(m, otherHost, thisHost));
        }
    }

    /**
     * Melakukan proses knapsack untuk menentukan pesan mana yang akan dikirim saat melakukan transfer data.
     * Metode ini mengimplementasikan algoritma knapsack 0/1 dengan pendekatan pemrograman dinamis.
     * Pesan-pesan yang memiliki utilitas tinggi akan dipilih untuk dikirimkan berdasarkan kapasitas knapsack yang tersedia.
     *
     * @param thisHost   Host pengirim
     * @param otherHost  Host penerima
     */
    public void knapsackSend(DTNHost thisHost, DTNHost otherHost) {
        this.tempMsg.addAll(this.getMessageCollection());
        int jumlahMsg = 0;
        int retriction = 0;
        jumlahMsg = this.tempMsg.size();
        retriction = this.getRetrictionForSend(thisHost, otherHost);
        double[][] bestSolutionSend = new double[jumlahMsg + 1][retriction + 1];

        // Inisialisasi tabel solusi dengan nilai 0
        for (int i = 0; i <= jumlahMsg; i++) {
            for (int length = 0; length <= retriction; length++) {
                if (i == 0 || length == 0) {
                    bestSolutionSend[i][length] = 0;
                } else if (this.lengthMsg.get(i - 1) <= length) {
                    // Jika panjang pesan lebih kecil atau sama dengan kapasitas knapsack yang tersisa, pilih pesan dengan utilitas maksimum
                    bestSolutionSend[i][length] = Math.max(bestSolutionSend[i - 1][length],
                            this.utilityMsg.get(i - 1) + bestSolutionSend[i - 1][length - this.lengthMsg.get(i - 1)]);
                } else {
                    // Jika panjang pesan lebih besar dari kapasitas knapsack yang tersisa, lewati pesan ini
                    bestSolutionSend[i][length] = bestSolutionSend[i - 1][length];
                }
            }
        }
        // Mengumpulkan pesan-pesan yang akan dikirim berdasarkan solusi terbaik
        int temp = retriction;
        for (int j = jumlahMsg; j >= 1; j--) {
            if (bestSolutionSend[j][temp] > bestSolutionSend[j - 1][temp]) {
                this.tempMsgTerpilih.add(this.tempMsg.get(j - 1));
                temp = temp - this.lengthMsg.get(j - 1);
            }
        }
    }

    /**
     * Melakukan proses knapsack untuk menentukan pesan mana yang akan dijatuhkan (dropped) saat kapasitas buffer terbatas.
     * Metode ini mengimplementasikan algoritma knapsack 0/1 dengan pendekatan pemrograman dinamis.
     * Pesan-pesan yang memiliki utilitas rendah akan dijatuhkan terlebih dahulu untuk memberikan ruang bagi pesan dengan utilitas yang lebih tinggi.
     *
     * @param m Pesan yang akan ditambahkan ke daftar pesan yang dipertimbangkan saat knapsack
     */
    public void knapsackDrop(Message m) {
        this.tempMsgDrop.addAll(this.getMessageCollection());
        this.tempMsgDrop.add(m);
        getUtilityMsgToArrForDrop(tempMsgDrop, getHost(), getOtherHostt());
        int jumlahMsg = 0;
        int retriction = 0;
        jumlahMsg = this.tempMsgDrop.size();
        int bufferSize = getHost().getRouter().getBufferSize() / bytes; //in kiloBytes
        retriction = bufferSize;
        double[][] bestSolutionDrop = new double[jumlahMsg + 1][retriction + 1];
        
        // Inisialisasi tabel solusi dengan nilai 0
        for (int i = 0; i <= jumlahMsg; i++) {
            for (int length = 0; length <= retriction; length++) {
                if (i == 0 || length == 0) {
                    bestSolutionDrop[i][length] = 0;
                } else if (this.lengthMsgDrop.get(i - 1) <= length) {
                    // Jika panjang pesan lebih kecil atau sama dengan panjang sisa buffer, pilih pesan dengan utilitas maksimum
                    bestSolutionDrop[i][length] = Math.max(bestSolutionDrop[i - 1][length],
                            this.utilityMsgDrop.get(i - 1) + bestSolutionDrop[i - 1][length - this.lengthMsgDrop.get(i - 1)]);
                } else {
                    // Jika panjang pesan lebih besar dari panjang sisa buffer, lewati pesan ini
                    bestSolutionDrop[i][length] = bestSolutionDrop[i - 1][length];
                }
            }
        }
        // Mengumpulkan pesan-pesan yang akan di-drop berdasarkan solusi terbaik
        int temp = retriction;
        for (int j = jumlahMsg; j >= 1; j--) {
            if (bestSolutionDrop[j][temp] > bestSolutionDrop[j - 1][temp]) {
                temp = temp - this.lengthMsgDrop.get(j - 1);
            } else {
                this.tempMsgLowersUtil.add(this.tempMsgDrop.get(j - 1));
            }
        }
    }

    @Override
    protected boolean makeRoomForMessage(Message m) {
        if (m.getSize() > this.getBufferSize()) {
            return false; // message too big for the buffer
        }

        int freeBuffer = this.getFreeBufferSize();

        if (freeBuffer < m.getSize()) {
            this.tempMsgDrop.clear();
            this.utilityMsgDrop.clear();
            this.lengthMsgDrop.clear();
            this.tempMsgLowersUtil.clear();
            knapsackDrop(m);
            if (m.equals(tempMsgLowersUtil)) {
                return false;
            }
            for (Message msg : this.tempMsgLowersUtil) {
                if (this.hasMessage(msg.getId()) && !isSending(msg.getId())) {
                    deleteMessage(msg.getId(), true);
                }
            }
        }
        return true;
    }

    
    /**
    * Menghitung sudut vektor antara dua koordinat.
    *
    * @param now  Koordinat saat ini
    * @param next Koordinat berikutnya
    * @return Nilai sudut dalam radian
    */
    //method hitung Sudut Vektor return nilai radiant
    public double hitungSudut(Coord now, Coord next) {
        double x = Math.abs(next.getX() - now.getX());
        double y = Math.abs(next.getY() - now.getY());
        //java dalam radiant bukan degree
        return Math.atan(y / x);
    }

    /**
    * Menghitung kecepatan vektor dalam sumbu X.
    *
    * @param speed Kecepatan vektor
    * @param sudut Sudut vektor dalam radian
    * @return Kecepatan vektor dalam sumbu X
    */
    //method hitung vector speedX (ux)
    public double speedX(double speed, double sudut) {
        //return Math.cos(sudut) * speed;
        return Math.cos(sudut) * Math.abs(speed);
    }

    /**
    * Menghitung kecepatan vektor dalam sumbu Y.
    *
    * @param speed Kecepatan vektor
    * @param sudut Sudut vektor dalam radian
    * @return Kecepatan vektor dalam sumbu Y
    */
    //method hitung vector speedY (uy)
    public double speedY(double speed, double sudut) {
        //return Math.sin(sudut) * speed;
        return Math.sin(sudut) * Math.abs(speed);
    }

    /**
    * Mengestimasi durasi waktu yang dibutuhkan untuk transfer data antara dua host.
    *
    * @param thisHost   Host pengirim
    * @param otherHost Host penerima
    * @return Tuple berisi perkiraan waktu minimum dan maksimum yang dibutuhkan
    */
    public Tuple<Double, Double> getEstimateTimeDuration(DTNHost thisHost, DTNHost otherHost) {
    // Mendapatkan koordinat berikutnya dari pergerakan host pengirim dan penerima
    Coord thisNextWaypoint;
    double uxThis, uyThis, uxOther, uyOther;

    try {
        thisNextWaypoint = thisHost.getMovement().getPath().getNextWaypoint();
    } catch (Exception e) {
        thisNextWaypoint = thisHost.getLocation();
    }

    Coord otherNextWaypoint;

    try {
        otherNextWaypoint = otherHost.getMovement().getPath().getNextWaypoint();
    } catch (Exception e) {
        otherNextWaypoint = otherHost.getLocation();
    }

    // get sudut vektor menggunakan atan2
    double sudutThis = Math.atan2(thisNextWaypoint.getY() - thisHost.getLocation().getY(), thisNextWaypoint.getX() - thisHost.getLocation().getX());
    double sudutOther = Math.atan2(otherNextWaypoint.getY() - otherHost.getLocation().getY(), otherNextWaypoint.getX() - otherHost.getLocation().getX());

    // get vektor speedX dan speedY
    // Menghitung vektor speedX dan speedY menggunakan method speedX dan speedY
    uxThis = speedX(thisHost.getMovement().getPath().getSpeed(), sudutThis);
    uyThis = speedY(thisHost.getMovement().getPath().getSpeed(), sudutThis);
    uxOther = speedX(otherHost.getMovement().getPath().getSpeed(), sudutOther);
    uyOther = speedY(otherHost.getMovement().getPath().getSpeed(), sudutOther);

    // Menghitung delta VelocityVector dan variabel lainnya
    double deltaUX = uxThis - uxOther;
    double deltaUY = uyThis - uyOther;
    double deltaX = thisHost.getLocation().getX() - otherHost.getLocation().getX();
    double deltaY = thisHost.getLocation().getY() - otherHost.getLocation().getY();
    double r = thisHost.getInterfaces().get(0).getTransmitRange();
    double l1 = 1 / (Math.pow(deltaUX, 2) + Math.pow(deltaUY, 2));
//    double l2 = (0.0 - (deltaX * deltaUX)) - (deltaY * deltaUY);
    double l2 = - (deltaX * deltaUX) - (deltaY * deltaUY);
    double l3 = Math.pow(r, 2) * (Math.pow(deltaUX, 2) + Math.pow(deltaUY, 2));
    double l4 = Math.pow((deltaX * deltaUY) - (deltaY * deltaUX), 2);
//    double l5 = l3 - l4;
    double l5 = Math.abs(l3 - l4);
    
    // Menghitung waktu minimum dan maksimum
    double max = l1 * (l2 + Math.sqrt(l5));
    double min = l1 * (l2 - Math.sqrt(l5));
    
    return new Tuple<>(min, max);
}

    public int getCapacityOFKnapsack(double time, int tfSpeed) {
        return (int) Math.ceil(time * tfSpeed);
    }
}